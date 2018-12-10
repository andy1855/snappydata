UPDATE [testdatabase].[dbo].[ADJUSTMENT]          SET descr ='ADJDecrp' WHERE ADJ_ID = ?;
UPDATE [testdatabase].[dbo].[AGREEMENT]           SET agree_cd = 'AGREECD' WHERE AGREE_ID = ?;
UPDATE [testdatabase].[dbo].[BANK]                SET bnk_full_nm = 'BANKNM' WHERE BNK_ID = ?;
UPDATE [testdatabase].[dbo].[BANK_ACCOUNT]        SET acct_nm = 'BANKActNm'  WHERE BNK_ID = ?;
UPDATE [testdatabase].[dbo].[CHECKS]              SET CHK_NUM = 'CHECKNUM' WHERE CHK_ID=?;
UPDATE [testdatabase].[dbo].[CHECK_STATUS]        SET CHK_STAT = 'CHQStat' WHERE CHK_STAT_ID = ?;
UPDATE [testdatabase].[dbo].[CLAIM]               SET CLM_RLS_IND = 'CLRLSIND' WHERE CLM_ID = ?;
UPDATE [testdatabase].[dbo].[CODE_VALUE]          SET CD_VAL = 'CODEVALUE' WHERE CD_VAL_ID = ? ;
UPDATE [testdatabase].[dbo].[TOPIC_COMMUNICATION] SET INSRT_USR = 'SomeUser' WHERE TPC_INQ_ID =?;
UPDATE [testdatabase].[dbo].[COMPLAINT]           SET TRACK_NUM = 'TngNum' WHERE  COMPLAINT_ID = ?;
UPDATE [testdatabase].[dbo].[FDI_CORRESPONDENCE]  SET CORR_NUM_POST_PRINT = 'FDORRS' WHERE  ENTITY_ID = ?;
UPDATE [testdatabase].[dbo].[FDI_TX_IDCARD]       SET DOCUMENT_ID  = 'FDOCUM' WHERE ACN_ID = ? ;
UPDATE [testdatabase].[dbo].[FDI_TX_LETTER]       SET DOCUMENT_ID = 'FDDOCUM' WHERE ACN_ID = ?;
UPDATE [testdatabase].[dbo].[GROUP_RELATION]      SET GRP_RELN_TYP_REF_ID = 8888 WHERE  GRP_RELN_ID = ?;
UPDATE [testdatabase].[dbo].[GROUPS]              SET GRP_CD = 'GROUPSCD' WHERE GRP_ID = ?;
UPDATE [testdatabase].[dbo].[INQUIRY]             SET TRACK_NUM='INQM' WHERE INQ_ID = ?;