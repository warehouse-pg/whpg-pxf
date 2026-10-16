-- FDW test
CREATE SERVER multi_block_data_smoke_test_server
	FOREIGN DATA WRAPPER {{ HCFS_PROTOCOL }}_pxf_fdw
	OPTIONS (config '{{ SERVER_CONFIG }}');
CREATE USER MAPPING FOR CURRENT_USER SERVER multi_block_data_smoke_test_server;
CREATE FOREIGN TABLE multi_block_data_smoke_test_foreign_table (
		t1 TEXT,
		a1 INTEGER
	) SERVER multi_block_data_smoke_test_server
	OPTIONS (resource '{{ HCFS_BUCKET }}{{ TEST_LOCATION }}', format 'csv');

-- write to writable table
INSERT INTO multi_block_data_smoke_test_foreign_table
	SELECT format('t%s', i::varchar(255)), i
		from generate_series(1, 32000000) s(i);

-- Verify data entered HCFS correctly, no distributed by in FDW yet
\! { for i in $({{ HCFS_CMD }} dfs -ls {{ HCFS_SCHEME }}{{ HCFS_BUCKET }}{{ TEST_LOCATION }} 2>/dev/null | tail -n +2 | awk '{print $NF}'); do {{ HCFS_CMD }} dfs -cat $i 2>/dev/null | head -1; done } | sort | head -1

-- @description query01 for PXF test on Multi Blocked data
SELECT count(*) FROM multi_block_data_smoke_test_foreign_table;

-- @description query02 for PXF test on Multi Blocked data
SELECT sum(a1) FROM multi_block_data_smoke_test_foreign_table;

-- @description query03 for PXF test on Multi Blocked data
SELECT t1, a1 FROM multi_block_data_smoke_test_foreign_table ORDER BY t1 LIMIT 10;

-- @description query04 for PXF test on Multi Blocked data
SELECT cnt < 32000000 AS check FROM (
	SELECT COUNT(*) AS cnt
		FROM multi_block_data_smoke_test_foreign_table
		WHERE gp_segment_id = 0
	) AS a;

{{ CLEAN_UP }}-- clean up HCFS
{{ CLEAN_UP }}\! {{ HCFS_CMD }} dfs -rm -r -f {{ HCFS_SCHEME }}{{ HCFS_BUCKET }}{{ TEST_LOCATION }}
