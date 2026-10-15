-- @description query01 for writing numeric arrays to Parquet. The scale comes from the writable table column: NUMERIC[] is written as DECIMAL(38,18) and rounds values with more than 18 decimal places, NUMERIC(38,20)[] keeps 20 decimal places, TEXT[] keeps every digit and NUMERIC(45,25)[] is rejected.
SELECT e.id, e.v[1] AS original_value, r.v[1] AS parquet_value, e.v[1] = r.v[1] AS same_value
FROM numeric_array_scale e JOIN pxf_parquet_read_numeric_array_undefined_precision r USING (id) ORDER BY id;

SELECT e.id, e.v[1] AS original_value, r.v[1] AS parquet_value, e.v[1] = r.v[1] AS same_value
FROM numeric_array_scale e JOIN pxf_parquet_read_numeric_array_38_20 r USING (id) ORDER BY id;

SELECT e.id, r.v[1]::numeric AS parquet_value, e.v[1] = r.v[1]::numeric AS same_value
FROM numeric_array_scale e JOIN pxf_parquet_read_numeric_array_text r USING (id) ORDER BY id;

INSERT INTO pxf_parquet_write_numeric_array_45_25 SELECT * FROM numeric_array_scale;
