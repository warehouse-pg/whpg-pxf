SET optimizer = off;
-- JDBC: one fragment, and int and date partitions (several fragments)
SELECT name, split_part(el_digest(q), ' ', 1) AS rows, el_digest(q) = el_digest(twin) AS same_as_default
FROM (VALUES
  ('01 plain coordinator', $q$SELECT * FROM j_coord$q$, $q$SELECT * FROM j_all$q$),
  ('02 plain any', $q$SELECT * FROM j_any$q$, $q$SELECT * FROM j_all$q$),
  ('03 int partitions coordinator', $q$SELECT * FROM jp_coord$q$, $q$SELECT * FROM jp_all$q$),
  ('04 int partitions any', $q$SELECT * FROM jp_any$q$, $q$SELECT * FROM jp_all$q$),
  ('05 date partitions coordinator', $q$SELECT * FROM jd_coord$q$, $q$SELECT * FROM jd_all$q$),
  ('06 date partitions any', $q$SELECT * FROM jd_any$q$, $q$SELECT * FROM jd_all$q$),
  ('07 int partitions any join distributed', $q$SELECT * FROM jp_any JOIN el_h USING (num)$q$, $q$SELECT * FROM jp_all JOIN el_h USING (num)$q$),
  ('08 date partitions coordinator join distributed', $q$SELECT * FROM jd_coord JOIN el_h USING (num)$q$, $q$SELECT * FROM jd_all JOIN el_h USING (num)$q$),
  ('09 pushdown coordinator', $q$SELECT * FROM jp_coord WHERE num < 10 AND flag$q$, $q$SELECT * FROM jp_all WHERE num < 10 AND flag$q$),
  ('10 pushdown any join', $q$SELECT * FROM jd_any JOIN el_h USING (num) WHERE day > date '2026-02-01'$q$, $q$SELECT * FROM jd_all JOIN el_h USING (num) WHERE day > date '2026-02-01'$q$)
) v(name, q, twin) ORDER BY name;
SELECT el_digest('SELECT * FROM j_all') = el_digest('SELECT * FROM el_jdbc_src') AS default_jdbc_read_same_as_source;
-- enum partitions
SELECT name, split_part(el_digest(q), ' ', 1) AS rows, el_digest(q) = el_digest(twin) AS same_as_default
FROM (VALUES
  ('11 enum partitions coordinator', $q$SELECT * FROM je_coord$q$, $q$SELECT * FROM je_all$q$),
  ('12 enum partitions any join distributed', $q$SELECT * FROM je_any JOIN el_h USING (num)$q$, $q$SELECT * FROM je_all JOIN el_h USING (num)$q$)
) v(name, q, twin) ORDER BY name;
