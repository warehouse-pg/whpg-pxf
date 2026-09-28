------------------------------------------------------------------
-- PXF column projection on external tables that are partitions (PTT-1850)
------------------------------------------------------------------
-- gp_exttable_fdw checks every row of an external table partition against
-- the partition constraint. The partition key must therefore be fetched from
-- PXF even when the query does not reference it, otherwise PXF sends NULL for
-- it and every row of the partition is silently dropped.
CREATE EXTERNAL TABLE pxf_partition_test (a TEXT, b TEXT, c TEXT)
LOCATION ('pxf://tmp/dummy1'
'?FRAGMENTER=org.greenplum.pxf.api.examples.DemoFragmenter'
'&ACCESSOR=org.greenplum.pxf.api.examples.DemoAccessor'
'&RESOLVER=org.greenplum.pxf.api.examples.DemoResolver')
FORMAT 'CUSTOM' (formatter='pxfwritable_import');

CREATE TABLE pxf_partition_parent (a TEXT, b TEXT, c TEXT)
DISTRIBUTED BY (a) PARTITION BY LIST (b);
ALTER TABLE pxf_partition_parent ATTACH PARTITION pxf_partition_test FOR VALUES IN ('value1');

-- projection without the partition key, through the parent
SELECT a FROM pxf_partition_parent ORDER BY a;
SELECT c FROM pxf_partition_parent ORDER BY c;
SELECT count(*), count(a), count(c) FROM pxf_partition_parent;

-- projection without the partition key, directly on the partition
SELECT a, c FROM pxf_partition_test ORDER BY a;

-- projection with the partition key
SELECT a, b FROM pxf_partition_parent ORDER BY a;

-- same with the Postgres planner
SET optimizer = off;
SELECT a FROM pxf_partition_parent ORDER BY a;
SELECT count(*), count(a), count(c) FROM pxf_partition_parent;
RESET optimizer;

ALTER TABLE pxf_partition_parent DETACH PARTITION pxf_partition_test;
DROP TABLE pxf_partition_parent;
DROP EXTERNAL TABLE pxf_partition_test;
