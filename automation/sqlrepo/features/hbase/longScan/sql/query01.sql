-- @description query01 for PXF HBase long-scan test
-- Asserts that scanning ~100K rows from a single region returns the full
-- row count without surfacing the HBase-2.x server-side scan RPC time-limit
-- error as a client failure (HBASE-16981 family).

SELECT count(*) FROM pxf_hbase_table_long_scan;
