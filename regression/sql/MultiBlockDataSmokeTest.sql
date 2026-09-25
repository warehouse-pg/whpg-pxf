-- Create writable table to insert data into HCFS
CREATE WRITABLE EXTERNAL TABLE multi_block_data_smoke_test_writable_external_table (
		t1 TEXT,
		a1 INTEGER
	)
	LOCATION('pxf://{{ HCFS_BUCKET }}{{ TEST_LOCATION }}?PROFILE={{ HCFS_PROTOCOL }}:csv{{ SERVER_PARAM }}')
	FORMAT 'CSV' (DELIMITER ',')
	DISTRIBUTED BY (t1);

-- write to writable table
INSERT INTO multi_block_data_smoke_test_writable_external_table
	SELECT format('t%s', i::varchar(255)), i
		from generate_series(1, 32000000) s(i);

-- Verify data entered HCFS correctly
\! {{ HCFS_CMD }} dfs -cat '{{ HCFS_SCHEME }}{{ HCFS_BUCKET }}{{ TEST_LOCATION }}/*_0' 2>/dev/null | head -1

-- External Table test
CREATE EXTERNAL TABLE multi_block_data_smoke_test_external_table (
		t1 TEXT,
		a1 INTEGER
	)
	LOCATION('pxf://{{ HCFS_BUCKET }}{{ TEST_LOCATION }}?PROFILE={{ HCFS_PROTOCOL }}:csv{{ SERVER_PARAM }}')
	FORMAT 'CSV' (DELIMITER ',');

-- @description query01 for PXF test on Multi Blocked data
SELECT count(*) FROM multi_block_data_smoke_test_external_table;

-- @description query02 for PXF test on Multi Blocked data
SELECT sum(a1) FROM multi_block_data_smoke_test_external_table;

-- @description query03 for PXF test on Multi Blocked data
SELECT t1, a1 FROM multi_block_data_smoke_test_external_table ORDER BY t1 LIMIT 10;

-- @description query04 for PXF test on Multi Blocked data
SELECT cnt < 32000000 AS check FROM (
	SELECT COUNT(*) AS cnt
		FROM multi_block_data_smoke_test_external_table
		WHERE gp_segment_id = 0
	) AS a;

{{ CLEAN_UP }}-- clean up HCFS
{{ CLEAN_UP }}\! {{ HCFS_CMD }} dfs -rm -r -f {{ HCFS_SCHEME }}{{ HCFS_BUCKET }}{{ TEST_LOCATION }}
