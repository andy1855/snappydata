/*
 * Copyright (c) 2018 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package io.snappydata.sql.catalog

import java.util.concurrent.ExecutionException

import scala.collection.mutable

import com.gemstone.gemfire.internal.cache.LocalRegion
import com.google.common.util.concurrent.UncheckedExecutionException
import com.pivotal.gemfirexd.internal.engine.diag.SysVTIs
import io.snappydata.Property
import io.snappydata.sql.catalog.SnappyExternalCatalog._

import org.apache.spark.SparkEnv
import org.apache.spark.jdbc.{ConnectionConf, ConnectionUtil}
import org.apache.spark.sql.catalyst.catalog.CatalogTypes.TablePartitionSpec
import org.apache.spark.sql.catalyst.catalog.{CatalogDatabase, CatalogStorageFormat, CatalogTable, CatalogTablePartition, CatalogTableType, ExternalCatalog}
import org.apache.spark.sql.catalyst.{FunctionIdentifier, TableIdentifier}
import org.apache.spark.sql.collection.Utils
import org.apache.spark.sql.execution.columnar.ExternalStoreUtils
import org.apache.spark.sql.execution.datasources.DataSource
import org.apache.spark.sql.hive.HiveExternalCatalog
import org.apache.spark.sql.internal.SnappySharedState
import org.apache.spark.sql.policy.PolicyProperties
import org.apache.spark.sql.sources.JdbcExtendedUtils
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{AnalysisException, SnappyContext, SnappyParserConsts, TableNotFoundException}

trait SnappyExternalCatalog extends ExternalCatalog {

  // Overrides for better exceptions that say "schema" instead of "database"

  protected def schemaNotFoundException(schema: String): AnalysisException =
    Utils.analysisException(s"Schema '$schema' not found")

  override def requireDbExists(schema: String): Unit = {
    if (!databaseExists(schema)) throw schemaNotFoundException(schema)
  }

  override def requireTableExists(schema: String, table: String): Unit = {
    if (!tableExists(schema, table)) {
      throw new TableNotFoundException(schema, table)
    }
  }

  override protected def requireFunctionExists(schema: String, funcName: String): Unit = {
    if (!functionExists(schema, funcName)) {
      throw Utils.analysisException(s"Undefined function '$funcName'. This function is neither " +
          s"a temporary function nor a permanent function registered in the schema '$schema'.")
    }
  }

  override protected def requireFunctionNotExists(schema: String, funcName: String): Unit = {
    if (functionExists(schema, funcName)) {
      throw Utils.analysisException(s"Function '$funcName' already exists in schema '$schema'")
    }
  }

  override def alterDatabase(schemaDefinition: CatalogDatabase): Unit = {
    throw new IllegalStateException("Not expected to be invoked")
  }

  /**
   * Create catalog table object for a BaseRelation backed by a Region in store.
   */
  def createTableForRegion(fullTableName: String, provider: String, schema: StructType,
      options: Map[String, String]): Unit = {
    assert(CatalogObjectType.isTableBackedByRegion(SnappyContext.getProviderType(provider)))
    val (schemaName, tableName) = getTableWithSchema(fullTableName, defaultSchema = "")
    assert(schemaName.length > 0)
    val catalogTable = CatalogTable(new TableIdentifier(tableName, Some(schemaName)),
      CatalogTableType.EXTERNAL, DataSource.buildStorageFormatFromOptions(
        options + (DBTABLE_PROPERTY -> fullTableName)), schema, Some(provider))
    createTable(catalogTable, ignoreIfExists = false)
  }

  override def getTable(schema: String, table: String): CatalogTable = {
    if (schema == SYS_SCHEMA) {
      // check for a system table/VTI in store
      val session = Utils.getActiveSession
      val conn = ConnectionUtil.getPooledConnection(schema, new ConnectionConf(
        ExternalStoreUtils.validateAndGetAllProps(session, mutable.Map.empty)))
      try {
        if (table == MEMBERS_VTI || JdbcExtendedUtils.tableExistsInMetaData(
          schema, table, conn, SysVTIs.LOCAL_VTI)) {
          val cols = JdbcExtendedUtils.getTableSchema(SYS_SCHEMA, table, conn, session)
          CatalogTable(identifier = TableIdentifier(table, Option(SYS_SCHEMA)),
            tableType = CatalogTableType.EXTERNAL,
            storage = CatalogStorageFormat.empty.copy(
              properties = Map(DBTABLE_PROPERTY -> s"$schema.$table")),
            schema = StructType(cols),
            provider = Some(SnappyParserConsts.ROW_SOURCE),
            partitionColumnNames = Nil,
            owner = "PUBLIC",
            createTime = 0,
            lastAccessTime = 0,
            unsupportedFeatures = Nil)
        } else throw new TableNotFoundException(schema, table)
      } finally {
        conn.close()
      }
    } else {
      try {
        getCachedCatalogTable(schema, table)
      } catch {
        case e@(_: UncheckedExecutionException | _: ExecutionException) => throw e.getCause
      }
    }
  }

  protected def getCachedCatalogTable(schema: String, table: String): CatalogTable

  def systemSchemaDefinition: CatalogDatabase =
    CatalogDatabase(SYS_SCHEMA, "System schema", SYS_SCHEMA, Map.empty) // path is dummy

  /**
   * Get RelationInfo for given table with underlying region in embedded mode.
   */
  def getRelationInfo(qualifiedTableName: String,
      rowTable: Boolean): (RelationInfo, Option[LocalRegion])

  def getDependents(schema: String, table: String, includeTypes: Seq[CatalogObjectType.Type] = Nil,
      excludeTypes: Seq[CatalogObjectType.Type] = Nil): Seq[CatalogTable] = {
    getDependents(schema, getTable(schema, table).properties, includeTypes, excludeTypes)
  }

  protected def getDependents(schema: String, properties: Map[String, String],
      includeTypes: Seq[CatalogObjectType.Type],
      excludeTypes: Seq[CatalogObjectType.Type]): Seq[CatalogTable] = {
    val allDependents = SnappyExternalCatalog.getDependents(properties)
    // scan through dependents even if includes/excludes are empty to skip dependents
    // not present (e.g. intermediate cluster failure before dependent was recorded
    // in base table entry and actual table entry creation)
    val dependents = new mutable.ArrayBuffer[CatalogTable]
    for (dep <- allDependents) {
      val (depSchema, depTable) = getTableWithSchema(dep, schema)
      getTableOption(depSchema, depTable) match {
        case None => // skip tables no longer present
        case Some(t) =>
          val tableType = CatalogObjectType.getTableType(t)
          val include = if (includeTypes.nonEmpty) includeTypes.contains(tableType)
          else if (excludeTypes.nonEmpty) !excludeTypes.contains(tableType) else true
          if (include) dependents += t
      }
    }
    dependents
  }

  def createPolicy(schemaName: String, policyName: String, targetTable: String,
      policyFor: String, policyApplyTo: Seq[String], expandedPolicyApplyTo: Seq[String],
      owner: String, filterString: String): Unit

  /**
   * Get the list of policies defined for a given table
   *
   * @param schema     schema name of the table
   * @param table      name of the table
   * @param properties CatalogTable.properties for the table
   * @return list of policy CatalogTables
   */
  def getPolicies(schema: String, table: String,
      properties: Map[String, String]): Seq[CatalogTable] = {
    // for older releases having TABLETYPE property, use full scan else use dependent relations
    if (properties.contains(TABLETYPE_PROPERTY)) {
      val fullTableName = s"$schema.$table"
      getAllTables().filter(t => CatalogObjectType.isPolicy(t) &&
          t.properties(PolicyProperties.targetTable).equalsIgnoreCase(fullTableName))
    } else {
      // search policies in the dependent relations
      getDependents(schema, properties, CatalogObjectType.Policy :: Nil, Nil)
    }
  }

  override def alterTableSchema(schemaName: String, table: String, schema: StructType): Unit = {
    throw new IllegalStateException("Not expected to be invoked")
  }

  override def renameTable(schema: String, oldName: String, newName: String): Unit = {
    throw new IllegalStateException("Not expected to be invoked")
  }

  override def createPartitions(schema: String, table: String, parts: Seq[CatalogTablePartition],
      ignoreIfExists: Boolean): Unit = {
    throw new IllegalStateException("Not expected to be invoked")
  }

  override def dropPartitions(schema: String, table: String, parts: Seq[TablePartitionSpec],
      ignoreIfNotExists: Boolean, purge: Boolean, retainData: Boolean): Unit = {
    throw new IllegalStateException("Not expected to be invoked")
  }

  override def renamePartitions(schema: String, table: String, specs: Seq[TablePartitionSpec],
      newSpecs: Seq[TablePartitionSpec]): Unit = {
    throw new IllegalStateException("Not expected to be invoked")
  }

  override def alterPartitions(schema: String, table: String,
      parts: Seq[CatalogTablePartition]): Unit = {
    throw new IllegalStateException("Not expected to be invoked")
  }

  override def loadTable(schema: String, table: String, loadPath: String,
      isOverwrite: Boolean, holdDDLTime: Boolean): Unit = {
    throw new IllegalStateException("Not expected to be invoked")
  }

  override def loadPartition(schema: String, table: String, loadPath: String,
      partition: TablePartitionSpec, isOverwrite: Boolean, holdDDLTime: Boolean,
      inheritTableSpecs: Boolean): Unit = {
    throw new IllegalStateException("Not expected to be invoked")
  }

  override def loadDynamicPartitions(schema: String, table: String, loadPath: String,
      partition: TablePartitionSpec, replace: Boolean, numDP: Int, holdDDLTime: Boolean): Unit = {
    throw new IllegalStateException("Not expected to be invoked")
  }

  /**
   * Get all the tables in the catalog skipping given schema names. By default
   * the inbuilt SYS schema is skipped.
   */
  def getAllTables(skipSchemas: Seq[String] = SYS_SCHEMA :: Nil): Seq[CatalogTable] = {
    listDatabases().flatMap(schema =>
      if (skipSchemas.nonEmpty && skipSchemas.contains(schema)) Nil
      else listTables(schema).map(table => getTable(schema, table)))
  }

  /**
   * Check for baseTable in both properties and storage.properties (older releases used a mix).
   */
  def getBaseTable(tableDefinition: CatalogTable): Option[String] = {
    tableDefinition.properties.get(BASETABLE_PROPERTY) match {
      case None => tableDefinition.storage.properties.get(BASETABLE_PROPERTY) match {
        // older released didn't have base table entry for indexes
        case None => tableDefinition.storage.properties.get(INDEXED_TABLE)
        case s => s
      }
      case s => s
    }
  }

  protected def getTableWithBaseTable(table: CatalogTable): Seq[(String, String)] = {
    var tableWithBase = (table.database -> table.identifier.table) :: Nil
    getBaseTable(table) match {
      case None =>
      case Some(baseTable) =>
        val withSchema = getTableWithSchema(baseTable, table.database)
        // add base table to the list of relations to be invalidated
        tableWithBase = withSchema :: tableWithBase
    }
    tableWithBase
  }

  def invalidateCaches(relations: Seq[(String, String)]): Unit

  def invalidate(name: (String, String)): Unit

  def invalidateAll(): Unit

  def close(): Unit
}

object SnappyExternalCatalog {
  val SYS_SCHEMA: String = "SYS"
  val SYS_SCHEMA_DOT: String = SYS_SCHEMA + '.'
  val MEMBERS_VTI: String = "MEMBERS"
  val SPARK_DEFAULT_SCHEMA: String = SnappySharedState.SPARK_DEFAULT_SCHEMA

  // Table properties below are a mix of CatalogTable.properties and
  // CatalogTable.storage.properties due to backward compatibility reasons

  // -------- Properties that go in CatalogTable.properties --------
  val TABLE_SCHEMA: String = HiveExternalCatalog.DATASOURCE_SCHEMA
  val SPLIT_VIEW_PREFIX = "snappydata.view."
  val SPLIT_VIEW_SCHEMA: String = SPLIT_VIEW_PREFIX + TABLE_SCHEMA
  val SPLIT_VIEW_TEXT_PROPERTY: String = SPLIT_VIEW_PREFIX + "text"
  val SPLIT_VIEW_ORIGINAL_TEXT_PROPERTY: String = SPLIT_VIEW_PREFIX + "originalText"
  // internal properties stored as hive table parameters
  val DEPENDENT_RELATIONS = "DEPENDENT_RELATIONS"
  // obsolete property used for backward compatibility only during reads
  val TABLETYPE_PROPERTY = "EXTERNAL_SNAPPY"

  // -------- Properties that go in CatalogTable.storage.properties --------
  // "dbtable" lower case since some other code including Spark's depends on the case
  val DBTABLE_PROPERTY = "dbtable"
  val BASETABLE_PROPERTY = "BASETABLE"
  val SCHEMADDL_PROPERTY = "SCHEMADDL"
  val INDEXED_TABLE = "INDEXED_TABLE"

  val currentFunctionIdentifier = new ThreadLocal[FunctionIdentifier]

  def cacheSize: Int = {
    SparkEnv.get match {
      case null => Property.CatalogCacheSize.defaultValue.get
      case env => Property.CatalogCacheSize.get(env.conf)
    }
  }

  def getDependents(properties: Map[String, String]): Array[String] = {
    properties.get(DEPENDENT_RELATIONS) match {
      case None => Utils.EMPTY_STRING_ARRAY
      case Some(d) => d.split(",")
    }
  }

  def getTableWithSchema(table: String, defaultSchema: String): (String, String) = {
    val dotIndex = table.indexOf('.')
    if (dotIndex > 0) table.substring(0, dotIndex) -> table.substring(dotIndex + 1)
    else defaultSchema -> table
  }
}

object CatalogObjectType extends Enumeration {
  type Type = Value

  val Row: Type = Value("ROW")
  val Column: Type = Value("COLUMN")
  val View: Type = Value("VIEW")
  val Index: Type = Value("INDEX")
  val Stream: Type = Value("STREAM")
  val Sample: Type = Value("SAMPLE")
  val TopK: Type = Value("TOPK")
  val External: Type = Value("EXTERNAL")
  val Policy: Type = Value("POLICY")
  val Hive: Type = Value("HIVE")

  def getTableType(table: CatalogTable): CatalogObjectType.Type = {
    getTableType(table.tableType.name, table.properties, table.storage.properties, table.provider)
  }

  def getTableType(tableType: String, properties: Map[String, String],
      storageProperties: Map[String, String], provider: Option[String]): CatalogObjectType.Type = {
    tableType match {
      case CatalogTableType.VIEW.name => View
      case _ =>
        if (storageProperties.contains(INDEXED_TABLE)) Index
        else if (properties.contains(PolicyProperties.policyApplyTo)) Policy
        else provider match {
          case Some(p) => SnappyContext.getProviderType(p)
          // check the obsolete TABLETYPE_PROPERTY
          case None => properties.get(TABLETYPE_PROPERTY) match {
            case None => Hive // assume a managed hive table when no provider has been specified
            case Some(p) => CatalogObjectType.withName(p)
          }
        }
    }
  }

  def isColumnTable(tableType: CatalogObjectType.Type): Boolean = tableType match {
    case Column | Index | Sample => true
    case _ => false
  }

  def isTableBackedByRegion(tableType: CatalogObjectType.Type): Boolean = {
    tableType == Row || isColumnTable(tableType)
  }

  def isPolicy(table: CatalogTable): Boolean = {
    table.properties.contains(PolicyProperties.policyApplyTo)
  }
}
