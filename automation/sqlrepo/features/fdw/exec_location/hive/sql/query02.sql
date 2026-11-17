SET optimizer = off;
-- Hive: the hive profile, format 'text', and a partitioned table
SELECT el_digest('SELECT * FROM hv_all') AS default_read;
SELECT name, split_part(el_digest(q), ' ', 1) AS rows, el_digest(q) = el_digest(twin) AS same_as_default
FROM (VALUES
  ('01 hive coordinator', $q$SELECT * FROM hv_coord$q$, $q$SELECT * FROM hv_all$q$),
  ('02 hive any', $q$SELECT * FROM hv_any$q$, $q$SELECT * FROM hv_all$q$),
  ('03 hive any join distributed', $q$SELECT * FROM hv_any JOIN el_h USING (num)$q$, $q$SELECT * FROM hv_all JOIN el_h USING (num)$q$),
  ('04 hive coordinator join distributed', $q$SELECT * FROM hv_coord JOIN el_h USING (num)$q$, $q$SELECT * FROM hv_all JOIN el_h USING (num)$q$),
  ('05 hive text coordinator', $q$SELECT * FROM hvt_coord$q$, $q$SELECT * FROM hvt_all$q$),
  ('06 hive text any join distributed', $q$SELECT * FROM hvt_any JOIN el_h USING (num)$q$, $q$SELECT * FROM hvt_all JOIN el_h USING (num)$q$),
  ('07 hive text same as hive profile', $q$SELECT * FROM hvt_all$q$, $q$SELECT * FROM hv_all$q$),
  ('08 partitioned coordinator', $q$SELECT * FROM hvp_coord$q$, $q$SELECT * FROM hvp_all$q$),
  ('09 partitioned any', $q$SELECT * FROM hvp_any$q$, $q$SELECT * FROM hvp_all$q$),
  ('10 partition filter coordinator', $q$SELECT * FROM hvp_coord WHERE part = 'b'$q$, $q$SELECT * FROM hvp_all WHERE part = 'b'$q$),
  ('11 partition filter any join distributed', $q$SELECT * FROM hvp_any JOIN el_h USING (num) WHERE part <> 'a'$q$, $q$SELECT * FROM hvp_all JOIN el_h USING (num) WHERE part <> 'a'$q$),
  ('12 pushdown coordinator', $q$SELECT word FROM hv_coord WHERE num > 20$q$, $q$SELECT word FROM hv_all WHERE num > 20$q$)
) v(name, q, twin) ORDER BY name;
