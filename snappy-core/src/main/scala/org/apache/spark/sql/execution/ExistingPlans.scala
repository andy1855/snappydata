package org.apache.spark.sql.execution

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.plans.physical.{HashPartitioning, Partitioning, SinglePartition}
import org.apache.spark.sql.sources.{BaseRelation, PrunedFilteredScan}


/** Physical plan node for scanning data from an DataSource scan RDD.
  * If user knows that the data is partitioned or replicated across
  * all nodes this SparkPla can be used to avoid expensive shuffle
  * and Broadcast joins. This plan ovverrides outputPartitioning and
  * make it inline with the partitioning of the underlying DataSource */
private[sql] case class PartitionedPhysicalRDD(
    output: Seq[Attribute],
    rdd: RDD[InternalRow],
    numPartition: Int,
    partitionColumns: Seq[Attribute],
    extraInformation: String) extends LeafNode {

  protected override def doExecute(): RDD[InternalRow] = rdd

  /** Specifies how data is partitioned across different nodes in the cluster. */
  override def outputPartitioning: Partitioning = {
    if (numPartition == 1) SinglePartition
    else {
      val expr = partitionColumns // TDOD ordering of partition column is important. This needs to be handled in a better manner
      HashPartitioning(expr, numPartition)
    }

  }

  override def simpleString: String = "Scan " + extraInformation + output.mkString("[", ",", "]")
}

private[sql] object PartitionedPhysicalRDD {
  def createFromDataSource(
      output: Seq[Attribute],
      numPartition: Int,
      partitionColumns: Seq[Attribute],
      rdd: RDD[InternalRow],
      relation: BaseRelation): PartitionedPhysicalRDD = {
    PartitionedPhysicalRDD(output, rdd, numPartition, partitionColumns, relation.toString)
  }
}


trait PartitionedDataSourceScan extends PrunedFilteredScan {
  def numPartitions: Int

  def partitionColumns: Seq[String]
}






