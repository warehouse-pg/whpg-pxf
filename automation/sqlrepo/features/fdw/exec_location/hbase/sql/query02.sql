SET optimizer = off;
-- HBase: 30 rows in three regions
SELECT el_digest('SELECT * FROM hb_all') AS default_read;
SELECT name, split_part(el_digest(q), ' ', 1) AS rows, el_digest(q) = el_digest(twin) AS same_as_default
FROM (VALUES
  ('01 hbase coordinator', $q$SELECT * FROM hb_coord$q$, $q$SELECT * FROM hb_all$q$),
  ('02 hbase any', $q$SELECT * FROM hb_any$q$, $q$SELECT * FROM hb_all$q$),
  ('03 hbase any join distributed', $q$SELECT * FROM hb_any JOIN el_h ON el_h.num = hb_any."cf1:num"$q$, $q$SELECT * FROM hb_all JOIN el_h ON el_h.num = hb_all."cf1:num"$q$),
  ('04 hbase coordinator join distributed', $q$SELECT * FROM hb_coord JOIN el_h ON el_h.num = hb_coord."cf1:num"$q$, $q$SELECT * FROM hb_all JOIN el_h ON el_h.num = hb_all."cf1:num"$q$),
  ('05 row key filter coordinator', $q$SELECT * FROM hb_coord WHERE recordkey > 'row00000015'$q$, $q$SELECT * FROM hb_all WHERE recordkey > 'row00000015'$q$),
  ('06 row key filter any join distributed', $q$SELECT * FROM hb_any JOIN el_h ON el_h.num = hb_any."cf1:num" WHERE recordkey <= 'row00000020'$q$, $q$SELECT * FROM hb_all JOIN el_h ON el_h.num = hb_all."cf1:num" WHERE recordkey <= 'row00000020'$q$),
  ('07 column filter coordinator', $q$SELECT recordkey FROM hb_coord WHERE "cf1:num" < 5$q$, $q$SELECT recordkey FROM hb_all WHERE "cf1:num" < 5$q$)
) v(name, q, twin) ORDER BY name;
