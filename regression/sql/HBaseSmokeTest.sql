-- PG 12 (WHPG 7) changed extra_float_digits default
-- from 1 (rounded display) to 3 (full IEEE-754 precision). The original .out
-- was generated against older GPDB where 10.01 rendered as "10.01" not
-- "10.009999999999998". Pin display rounding so the test is version-agnostic.
SET extra_float_digits = 0;

-- Create Hbase tables hbase_table and pxflookup
\!{{ HBASE_CMD }} shell {{ SCRIPT create_pxflookup.rb }} >/dev/null 2>&1
-- HBase 2.x shell is quieter for put() than 1.x —
-- 1.x emitted "0 row(s) in -.---- seconds" after each put, 2.x doesn't.
-- Silence to keep the .out file version-agnostic.
\!{{ HBASE_CMD }} shell {{ SCRIPT gen_small_data.rb }} >/dev/null 2>&1

-- External Table test
CREATE EXTERNAL TABLE h_base_smoke_test_external_table
	(name TEXT, num INTEGER, dub DOUBLE PRECISION, longnum BIGINT, bool BOOLEAN)
	LOCATION('pxf://hbase_small_data_table_{{ FULL_TESTNAME }}?PROFILE=HBase{{ SERVER_PARAM }}')
	FORMAT 'CUSTOM' (FORMATTER='pxfwritable_import');

SELECT * FROM h_base_smoke_test_external_table ORDER BY name;
SELECT name, num FROM h_base_smoke_test_external_table WHERE num > 50 ORDER BY name;

-- clean up HBase
{{ CLEAN_UP }}\!{{ HBASE_CMD }} shell {{ SCRIPT drop_small_data.rb }} >/dev/null 2>&1
{{ CLEAN_UP }}\!rm -rf {{ SCRIPT drop_small_data.rb }} {{ SCRIPT gen_small_data.rb }} {{ SCRIPT create_pxflookup.rb }}
