-- data prep
-- start_ignore
{{ GPDB_REMOTE }}\! ssh {{ PGHOST }} mkdir -p {{ TEST_LOCATION }}
\! mkdir -p {{ TEST_LOCATION }}
COPY (
	SELECT format('row_%s',i::varchar(255)),
		i,
		i*0.0001,
		i*100000000000,
		CASE WHEN (i%2) = 0 THEN 'true' ELSE 'false' END
		from generate_series(1, 100) s(i)
	) TO '{{ TEST_LOCATION }}/data.csv'
	WITH (FORMAT 'csv');
{{ GPDB_REMOTE }}-- if GPDB is remote, will need to scp file down from there for beeline
{{ GPDB_REMOTE }}\! scp {{ PGHOST }}:{{ TEST_LOCATION }}/data.csv {{ TEST_LOCATION }}
{{ HIVE_REMOTE }}-- if hive is remote, will need to scp file up there to load it in
{{ HIVE_REMOTE }}\! cat {{ TEST_LOCATION }}/data.csv | ssh {{ HIVE_HOST }} 'mkdir -p {{ TEST_LOCATION }} && cat > {{ TEST_LOCATION }}/data.csv'
\! {{ BEELINE_CMD }} -f {{ SCRIPT create_hive_smoke_test_database.sql }} -u 'jdbc:hive2://{{ HIVE_HOST }}:10000/default{{ HIVE_PRINCIPAL }}'
\! {{ BEELINE_CMD }} -f {{ SCRIPT load_small_data.sql }} -u 'jdbc:hive2://{{ HIVE_HOST }}:10000/hive_smoke_test_database_{{ FULL_TESTNAME }}{{ HIVE_PRINCIPAL }}'
-- end_ignore

-- FDW test
CREATE SERVER hive_smoke_test_server
	FOREIGN DATA WRAPPER hive_pxf_fdw
	OPTIONS (config '{{ SERVER_CONFIG }}');
CREATE USER MAPPING FOR CURRENT_USER SERVER hive_smoke_test_server;
CREATE FOREIGN TABLE hive_smoke_test_foreign_table (
		name TEXT,
		num INTEGER,
		dub DOUBLE PRECISION,
		longNum BIGINT,
		bool BOOLEAN
	)
	SERVER hive_smoke_test_server
	OPTIONS (resource 'hive_smoke_test_database_{{ FULL_TESTNAME }}.hive_small_data_table_{{ FULL_TESTNAME }}');

-- @description query01 for PXF test on small data
SELECT * FROM hive_smoke_test_foreign_table ORDER BY name;

-- @description query02 for PXF test on small data
SELECT name, num FROM hive_smoke_test_foreign_table WHERE num > 50 ORDER BY name;

-- start_ignore
{{ CLEAN_UP }}-- clean up Hive and local disk
{{ CLEAN_UP }}\! rm -rf {{ TEST_LOCATION }}
{{ CLEAN_UP }}\! {{ BEELINE_CMD }} -f {{ SCRIPT cleanup_hive_smoke_test.sql }}
{{ CLEAN_UP }}\! rm -rf {{ SCRIPT cleanup_hive_smoke_test.sql }} {{ SCRIPT load_small_data.sql }} {{ SCRIPT create_hive_smoke_test_database.sql }}
{{ CLEAN_UP }}{{ GPDB_REMOTE }}\! ssh {{ PGHOST }} rm -rf {{ TEST_LOCATION }}
-- end_ignore
