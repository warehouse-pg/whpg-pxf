-- start_matchsubs
--
-- m/\S*pxf_automation_data\/[0-9a-fA-F-]{36}\//
-- s/\S*pxf_automation_data\/[0-9a-fA-F-]{36}\//WORKDIR\//
--
-- end_matchsubs
SET optimizer = on;
-- reject limits on reads that run on the coordinator or once per segment
SELECT count(*) FROM b_coord_10;
SELECT count(*) FROM b_coord_3;
SELECT count(*) FROM b_coord_none;
SELECT count(*) FROM b_any_10;
SELECT count(*) FROM b_any_10 JOIN el_h USING (num);
SELECT count(*) FROM b_any_3 JOIN el_h USING (num);
-- a failing cast in the WHERE clause is not a rejected row
SELECT count(*) FROM small_coord_10 WHERE word::int > 0;
-- log_errors needs mpp_execute 'all segments'; with it bad rows are logged
SELECT gp_truncate_error_log('b_all_log');
SELECT count(*) FROM b_all_log;
SELECT count(*) AS logged FROM gp_read_error_log('b_all_log');
SELECT count(*) FROM b_coord_log;
SELECT count(*) FROM b_any_log;
SELECT count(*) FROM b_any_log JOIN el_h USING (num);
SELECT count(*) FROM b_srv_log;
SELECT count(*) FROM b_srv_log JOIN el_h USING (num);
