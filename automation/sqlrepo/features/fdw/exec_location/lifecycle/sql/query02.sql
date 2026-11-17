-- start_matchsubs
--
-- m/\S*pxf_automation_data\/[0-9a-fA-F-]{36}\//
-- s/\S*pxf_automation_data\/[0-9a-fA-F-]{36}\//WORKDIR\//
--
-- end_matchsubs
SET optimizer = off;
-- rescans: the subquery restarts the scan for each outer row
SELECT g, (SELECT count(*) FROM s_coord_s WHERE num > g) FROM generate_series(10, 12) g ORDER BY g;
SELECT g, (SELECT count(*) FROM s_any_s WHERE num > g) FROM generate_series(10, 12) g ORDER BY g;
-- LIMIT stops the scan early; the session reads normally afterwards
SELECT count(*) FROM (SELECT num FROM g_coord LIMIT 3) l;
SELECT count(*) FROM (SELECT num FROM g_any LIMIT 3) l;
SELECT count(*), sum(num) FROM g_coord;
SELECT count(*), sum(num) FROM g_any;
SELECT count(*) FROM g_any JOIN el_h USING (num);
SELECT el_digest('SELECT * FROM g_coord') = el_digest('SELECT * FROM g_all') AS big_coordinator_same;
-- cursors: fetched in steps, closed without a fetch, and in a rolled back transaction
BEGIN;
DECLARE el_c1 CURSOR FOR SELECT num FROM s_coord_s ORDER BY num;
FETCH 3 FROM el_c1;
FETCH 3 FROM el_c1;
CLOSE el_c1;
DECLARE el_c2 CURSOR FOR SELECT * FROM s_any_s;
CLOSE el_c2;
COMMIT;
BEGIN;
DECLARE el_c3 CURSOR FOR SELECT num FROM g_coord;
FETCH 2 FROM el_c3;
ROLLBACK;
SELECT count(*) FROM g_coord;
-- several reads in one transaction
BEGIN;
SELECT count(*) FROM s_coord_s;
SELECT count(*) FROM s_any_s JOIN el_h USING (num);
SELECT count(*) FROM s_all;
COMMIT;
-- EXPLAIN ANALYZE runs the scan
DO $$ BEGIN EXECUTE 'EXPLAIN ANALYZE SELECT * FROM s_coord_s'; END $$;
DO $$ BEGIN EXECUTE 'EXPLAIN ANALYZE SELECT * FROM s_any_s JOIN el_h USING (num)'; END $$;
-- a resource that does not exist fails with the foreign table named; the session goes on
SELECT * FROM x_coord;
SELECT 1 AS still_connected;
