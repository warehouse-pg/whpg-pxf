SET optimizer = off;
-- Parquet written through an 'all segments' table, read through each placement
SELECT name, split_part(el_digest(q), ' ', 1) AS rows, el_digest(q) = el_digest(twin) AS same_as_default
FROM (VALUES
  ('01 parquet coordinator', $q$SELECT * FROM pq_coord$q$, $q$SELECT * FROM pq_all$q$),
  ('02 parquet any', $q$SELECT * FROM pq_any$q$, $q$SELECT * FROM pq_all$q$),
  ('03 parquet any join distributed', $q$SELECT * FROM pq_any JOIN el_h USING (num)$q$, $q$SELECT * FROM pq_all JOIN el_h USING (num)$q$),
  ('04 parquet projection coordinator', $q$SELECT word, flag FROM pq_coord WHERE num > 20$q$, $q$SELECT word, flag FROM pq_all WHERE num > 20$q$)
) v(name, q, twin) ORDER BY name;
-- the default Parquet read has every row
SELECT count(*), sum(num), count(word) FROM pq_all;
