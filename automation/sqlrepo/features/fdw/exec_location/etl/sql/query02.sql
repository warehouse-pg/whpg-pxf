SET optimizer = off;
-- copy rows read on the coordinator and through 'any' into foreign tables
INSERT INTO etl_hdfs_off SELECT * FROM s_coord_s;
INSERT INTO etl_hdfs_off SELECT s_any_s.* FROM s_any_s JOIN el_h USING (num);
INSERT INTO etl_jdbc_off SELECT * FROM s_coord_s;
INSERT INTO etl_jdbc_off SELECT * FROM s_any_s;
INSERT INTO etl_jdbc_off SELECT * FROM jp_coord WHERE num <= 10;
-- what landed equals the same rows read through 'all segments'
SELECT el_digest('SELECT * FROM etl_hdfs_off_back') = el_digest('SELECT * FROM s_all UNION ALL SELECT * FROM s_all') AS hdfs_copy_same,
       split_part(el_digest('SELECT * FROM etl_hdfs_off_back'), ' ', 1) AS hdfs_rows;
SELECT el_digest('SELECT * FROM el_etl_dst_off') = el_digest('SELECT * FROM s_all UNION ALL SELECT * FROM s_all UNION ALL SELECT * FROM jp_all WHERE num <= 10') AS jdbc_copy_same,
       split_part(el_digest('SELECT * FROM el_etl_dst_off'), ' ', 1) AS jdbc_rows;
