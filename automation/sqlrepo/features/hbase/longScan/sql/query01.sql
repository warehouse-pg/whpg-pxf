-- @description query01 for PXF HBase long-scan test
-- Asserts that scanning ~10,000 rows from a single region returns the full
-- row count without surfacing the HBase-2.x server-side scan RPC time-limit
-- error as a client failure (HBASE-16981 family). 10,000 chosen over 100,000
-- because HBaseDataPreparer.q11 = BigInteger(10).pow(i) overflows HBase's
-- 2GB cell-block ByteBuf at N~63,000; see HBaseTest.longScan() comment.

SELECT count(*) FROM pxf_hbase_table_long_scan;
