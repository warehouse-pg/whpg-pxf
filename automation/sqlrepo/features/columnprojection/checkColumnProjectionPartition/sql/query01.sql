-- @description query01 for PXF column projection on an external table that is a partition (PTT-1850)
-- The queries below never reference the partition key a1, but gp_exttable_fdw checks
-- every row of an external table partition against the partition constraint, so PXF
-- has to project a1 anyway. Without that, a1 is returned as NULL, the constraint
-- evaluates to NULL and every row is silently dropped.

SET optimizer = off;

SELECT t0, colprojvalue FROM test_column_projection_parent ORDER BY t0;

SELECT colprojvalue FROM test_column_projection_parent ORDER BY colprojvalue;

SELECT t0, colprojvalue FROM test_column_projection_parent WHERE b2 ORDER BY t0;

SELECT count(*), count(t0), count(colprojvalue) FROM test_column_projection_parent;

-- querying the partition directly still applies the partition constraint
SELECT t0, colprojvalue FROM test_column_projection_part ORDER BY t0;

SELECT colprojvalue FROM test_column_projection_part ORDER BY colprojvalue;

SET optimizer = on;

SELECT t0, colprojvalue FROM test_column_projection_parent ORDER BY t0;

SELECT colprojvalue FROM test_column_projection_parent ORDER BY colprojvalue;

SELECT t0, colprojvalue FROM test_column_projection_parent WHERE b2 ORDER BY t0;

SELECT count(*), count(t0), count(colprojvalue) FROM test_column_projection_parent;

-- querying the partition directly still applies the partition constraint
SELECT t0, colprojvalue FROM test_column_projection_part ORDER BY t0;

SELECT colprojvalue FROM test_column_projection_part ORDER BY colprojvalue;
