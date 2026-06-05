-- PTT-1135 Phase 5 [5.6]: PG 12 (WHPG 7) changed extra_float_digits default
-- 1 → 3. Pin display rounding so the test is version-agnostic. Same fix as
-- HBaseSmokeTest.sql; see commentary there.
SET extra_float_digits = 0;

-- Create Hbase tables hbase_table and pxflookup
\!{{ HBASE_CMD }} shell {{ SCRIPT create_pxflookup.rb }} >/dev/null 2>&1
-- PTT-1135 Phase 5 [5.6]: HBase 2.x shell silenced put() output that 1.x emitted.
\!{{ HBASE_CMD }} shell {{ SCRIPT gen_small_data.rb }} >/dev/null 2>&1

-- FDW test
CREATE SERVER h_base_smoke_test_server
	FOREIGN DATA WRAPPER hbase_pxf_fdw
	OPTIONS (config '{{ SERVER_CONFIG }}');
CREATE USER MAPPING FOR CURRENT_USER
	SERVER h_base_smoke_test_server;
CREATE FOREIGN TABLE h_base_smoke_test_foreign_table  (
		name text,
		num int,
		dub double precision,
		longnum bigint,
		bool boolean
	)
	SERVER h_base_smoke_test_server OPTIONS (resource 'hbase_small_data_table_{{ FULL_TESTNAME }}');

SELECT * FROM h_base_smoke_test_foreign_table ORDER BY name;
SELECT name, num FROM h_base_smoke_test_foreign_table WHERE num > 50 ORDER BY name;

-- clean up HBase
{{ CLEAN_UP }}\!{{ HBASE_CMD }} shell {{ SCRIPT drop_small_data.rb }} >/dev/null 2>&1
{{ CLEAN_UP }}\!rm -rf {{ SCRIPT drop_small_data.rb }} {{ SCRIPT gen_small_data.rb }} {{ SCRIPT create_pxflookup.rb }}
