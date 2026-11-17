SET optimizer = off;
-- joins: with a distributed table (the coordinator scan runs in a coordinator
-- reader; an 'any' scan runs once on every segment), a replicated table,
-- itself and the other placements
SELECT name, split_part(el_digest(q), ' ', 1) AS rows, el_digest(q) = el_digest(twin) AS same_as_default
FROM (VALUES
  ('01 coordinator join distributed', $q$SELECT * FROM s_coord_s JOIN el_h USING (num)$q$, $q$SELECT * FROM s_all JOIN el_h USING (num)$q$),
  ('02 any join distributed', $q$SELECT * FROM s_any_s JOIN el_h USING (num)$q$, $q$SELECT * FROM s_all JOIN el_h USING (num)$q$),
  ('03 coordinator join replicated', $q$SELECT * FROM s_coord_s JOIN el_r USING (num)$q$, $q$SELECT * FROM s_all JOIN el_r USING (num)$q$),
  ('04 any join replicated', $q$SELECT * FROM s_any_s JOIN el_r USING (num)$q$, $q$SELECT * FROM s_all JOIN el_r USING (num)$q$),
  ('05 coordinator self join', $q$SELECT a.num, b.word FROM s_coord_s a JOIN s_coord_s b USING (num)$q$, $q$SELECT a.num, b.word FROM s_all a JOIN s_all b USING (num)$q$),
  ('06 any self join', $q$SELECT a.num, b.word FROM s_any_s a JOIN s_any_s b USING (num)$q$, $q$SELECT a.num, b.word FROM s_all a JOIN s_all b USING (num)$q$),
  ('07 any join coordinator', $q$SELECT * FROM s_any_s JOIN s_coord_s USING (num)$q$, $q$SELECT * FROM s_all JOIN s_all x USING (num)$q$),
  ('08 any join all segments', $q$SELECT * FROM s_any_s JOIN s_all USING (num)$q$, $q$SELECT * FROM s_all JOIN s_all x USING (num)$q$),
  ('09 many any join distributed', $q$SELECT * FROM m_any JOIN el_h ON m_any.num - 100 = el_h.num$q$, $q$SELECT * FROM m_all JOIN el_h ON m_all.num - 100 = el_h.num$q$),
  ('10 one coordinator join distributed', $q$SELECT * FROM o_coord JOIN el_h ON o_coord.num - 200 = el_h.num$q$, $q$SELECT * FROM o_all JOIN el_h ON o_all.num - 200 = el_h.num$q$),
  ('11 empty any join distributed', $q$SELECT * FROM e_any JOIN el_h USING (num)$q$, $q$SELECT * FROM e_all JOIN el_h USING (num)$q$),
  ('12 any join distributed, pushdown', $q$SELECT s_any_s.word FROM s_any_s JOIN el_h USING (num) WHERE num > 20$q$, $q$SELECT s_all.word FROM s_all JOIN el_h USING (num) WHERE num > 20$q$),
  ('13 any group by join', $q$SELECT flag, count(*) FROM s_any_s JOIN el_h USING (num) GROUP BY flag$q$, $q$SELECT flag, count(*) FROM s_all JOIN el_h USING (num) GROUP BY flag$q$)
) v(name, q, twin) ORDER BY name;
-- set operations, CTEs, subqueries, window functions
SELECT name, split_part(el_digest(q), ' ', 1) AS rows, el_digest(q) = el_digest(twin) AS same_as_default
FROM (VALUES
  ('20 union all', $q$SELECT num FROM s_coord_s UNION ALL SELECT num FROM s_any_s$q$, $q$SELECT num FROM s_all UNION ALL SELECT num FROM s_all$q$),
  ('21 cte twice', $q$WITH c AS (SELECT * FROM s_coord_s) SELECT a.num FROM c a JOIN c b USING (num)$q$, $q$WITH c AS (SELECT * FROM s_all) SELECT a.num FROM c a JOIN c b USING (num)$q$),
  ('22 in subquery', $q$SELECT num FROM el_h WHERE num IN (SELECT num FROM s_coord_s)$q$, $q$SELECT num FROM el_h WHERE num IN (SELECT num FROM s_all)$q$),
  ('23 not exists any', $q$SELECT num FROM el_h WHERE num < 40 AND NOT EXISTS (SELECT 1 FROM s_any_s WHERE s_any_s.num = el_h.num)$q$, $q$SELECT num FROM el_h WHERE num < 40 AND NOT EXISTS (SELECT 1 FROM s_all WHERE s_all.num = el_h.num)$q$),
  ('24 window', $q$SELECT num, rank() OVER (PARTITION BY flag ORDER BY num) FROM s_coord_s$q$, $q$SELECT num, rank() OVER (PARTITION BY flag ORDER BY num) FROM s_all$q$)
) v(name, q, twin) ORDER BY name;
-- statements that move the rows somewhere else
DROP TABLE IF EXISTS el_etl_insert;
CREATE TABLE el_etl_insert (num int, word text, amount numeric, day date, flag boolean) DISTRIBUTED BY (num);
INSERT INTO el_etl_insert SELECT * FROM s_coord_s;
INSERT INTO el_etl_insert SELECT * FROM s_any_s;
SELECT el_digest('SELECT * FROM el_etl_insert') = el_digest('SELECT * FROM s_all UNION ALL SELECT * FROM s_all') AS insert_select_same;
DROP TABLE IF EXISTS el_etl_ctas;
CREATE TABLE el_etl_ctas AS SELECT * FROM s_coord_s DISTRIBUTED BY (num);
SELECT el_digest('SELECT * FROM el_etl_ctas') = el_digest('SELECT * FROM s_all') AS ctas_same;
COPY (SELECT num, word FROM s_coord_s ORDER BY num) TO STDOUT;
COPY (SELECT num, word FROM s_any_s ORDER BY num LIMIT 3) TO STDOUT;
DROP TABLE el_etl_insert;
DROP TABLE el_etl_ctas;
