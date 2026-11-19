-- el_oc reads the small data through server el_oc_srv; nothing else uses either.
-- Each change of mpp_execute takes effect on the next query, including for a
-- prepared statement and a plpgsql function that cached a plan.
ALTER FOREIGN TABLE el_oc OPTIONS (ADD mpp_execute 'all segments');
PREPARE el_oc_p AS SELECT count(*), sum(num) FROM el_oc JOIN el_h USING (num);
CREATE OR REPLACE FUNCTION el_oc_rows() RETURNS bigint AS $$
BEGIN RETURN (SELECT count(*) FROM el_oc JOIN el_h USING (num)); END $$ LANGUAGE plpgsql;
SELECT el_digest('SELECT * FROM el_oc') = el_digest('SELECT * FROM s_all') AS all_segments_same;
EXECUTE el_oc_p;
SELECT el_oc_rows();
-- table option: coordinator
ALTER FOREIGN TABLE el_oc OPTIONS (SET mpp_execute 'coordinator');
SELECT el_digest('SELECT * FROM el_oc') = el_digest('SELECT * FROM s_all') AS coordinator_same;
EXECUTE el_oc_p;
SELECT el_oc_rows();
-- table option: any
ALTER FOREIGN TABLE el_oc OPTIONS (SET mpp_execute 'any');
SELECT el_digest('SELECT * FROM el_oc') = el_digest('SELECT * FROM s_all') AS any_same;
EXECUTE el_oc_p;
SELECT el_oc_rows();
-- no table option: the server's value applies, first the default, then 'coordinator', then 'any'
ALTER FOREIGN TABLE el_oc OPTIONS (DROP mpp_execute);
EXECUTE el_oc_p;
ALTER SERVER el_oc_srv OPTIONS (ADD mpp_execute 'coordinator');
SELECT el_digest('SELECT * FROM el_oc') = el_digest('SELECT * FROM s_all') AS server_coordinator_same;
EXECUTE el_oc_p;
SELECT el_oc_rows();
ALTER SERVER el_oc_srv OPTIONS (SET mpp_execute 'any');
EXECUTE el_oc_p;
SELECT el_oc_rows();
-- log_errors on the table with mpp_execute from the server
ALTER FOREIGN TABLE el_oc OPTIONS (ADD reject_limit '10', ADD log_errors 'true');
SELECT count(*) FROM el_oc;
ALTER SERVER el_oc_srv OPTIONS (DROP mpp_execute);
SELECT count(*) FROM el_oc;
ALTER FOREIGN TABLE el_oc OPTIONS (DROP reject_limit, DROP log_errors);
-- two tables over the same files with different settings, in one query and one transaction
BEGIN;
SELECT count(*) FROM s_coord_s a JOIN s_any_s b USING (num) JOIN s_all c USING (num);
SELECT count(*) FROM s_coord_t;
SELECT count(*) FROM s_any_t JOIN el_h USING (num);
COMMIT;
DEALLOCATE el_oc_p;
DROP FUNCTION el_oc_rows();
