-- @description query01 for writing numeric with precision above 38 to Parquet. NUMERIC(76,61) and NUMERIC(76,61)[] keep every digit, and precision above 76 is rejected.
SELECT e.id, r.d, e.d = r.d AS same_d, e.a = r.a AS same_a
FROM numeric_max_precision e JOIN pxf_parquet_read_numeric_max_precision r USING (id) ORDER BY id;

SELECT id, unnest(a) AS a FROM pxf_parquet_read_numeric_max_precision ORDER BY id, a;

INSERT INTO pxf_parquet_write_numeric_precision_77 SELECT id, d FROM numeric_max_precision;
