SET optimizer = off;
-- the default read every other read is compared with
SELECT el_digest('SELECT * FROM s_all') AS default_read;
-- where mpp_execute is set
SELECT name, split_part(el_digest(q), ' ', 1) AS rows, el_digest(q) = el_digest(twin) AS same_as_default
FROM (VALUES
  ('01 table coordinator', $q$SELECT * FROM s_coord_t$q$, $q$SELECT * FROM s_all$q$),
  ('02 server coordinator', $q$SELECT * FROM s_coord_s$q$, $q$SELECT * FROM s_all$q$),
  ('03 table COORDINATOR', $q$SELECT * FROM s_coord_upper$q$, $q$SELECT * FROM s_all$q$),
  ('04 server master', $q$SELECT * FROM s_master$q$, $q$SELECT * FROM s_all$q$),
  ('05 wrapper without mpp_execute', $q$SELECT * FROM s_own$q$, $q$SELECT * FROM s_all$q$),
  ('06 table all segments over server coordinator', $q$SELECT * FROM s_override$q$, $q$SELECT * FROM s_all$q$),
  ('07 table any', $q$SELECT * FROM s_any_t$q$, $q$SELECT * FROM s_all$q$),
  ('08 server any', $q$SELECT * FROM s_any_s$q$, $q$SELECT * FROM s_all$q$)
) v(name, q, twin) ORDER BY name;
-- more fragments than segments, one fragment, no rows
SELECT name, split_part(el_digest(q), ' ', 1) AS rows, el_digest(q) = el_digest(twin) AS same_as_default
FROM (VALUES
  ('10 many coordinator', $q$SELECT * FROM m_coord$q$, $q$SELECT * FROM m_all$q$),
  ('11 many any', $q$SELECT * FROM m_any$q$, $q$SELECT * FROM m_all$q$),
  ('12 one coordinator', $q$SELECT * FROM o_coord$q$, $q$SELECT * FROM o_all$q$),
  ('13 one any', $q$SELECT * FROM o_any$q$, $q$SELECT * FROM o_all$q$),
  ('14 empty coordinator', $q$SELECT * FROM e_coord$q$, $q$SELECT * FROM e_all$q$),
  ('15 empty any', $q$SELECT * FROM e_any$q$, $q$SELECT * FROM e_all$q$)
) v(name, q, twin) ORDER BY name;
-- projection, pushed-down and local predicates, NULLs, aggregates
SELECT name, split_part(el_digest(q), ' ', 1) AS rows, el_digest(q) = el_digest(twin) AS same_as_default
FROM (VALUES
  ('20 projection coordinator', $q$SELECT day, word FROM s_coord_s$q$, $q$SELECT day, word FROM s_all$q$),
  ('21 projection any', $q$SELECT amount FROM s_any_s$q$, $q$SELECT amount FROM s_all$q$),
  ('22 range coordinator', $q$SELECT * FROM s_coord_s WHERE num BETWEEN 21 AND 25$q$, $q$SELECT * FROM s_all WHERE num BETWEEN 21 AND 25$q$),
  ('23 range any', $q$SELECT * FROM s_any_s WHERE num BETWEEN 21 AND 25$q$, $q$SELECT * FROM s_all WHERE num BETWEEN 21 AND 25$q$),
  ('24 like coordinator', $q$SELECT * FROM s_coord_s WHERE word LIKE '%3'$q$, $q$SELECT * FROM s_all WHERE word LIKE '%3'$q$),
  ('25 is null any', $q$SELECT * FROM s_any_s WHERE word IS NULL OR amount IS NULL$q$, $q$SELECT * FROM s_all WHERE word IS NULL OR amount IS NULL$q$),
  ('26 boolean and date coordinator', $q$SELECT * FROM s_coord_s WHERE flag AND day > date '2026-01-03'$q$, $q$SELECT * FROM s_all WHERE flag AND day > date '2026-01-03'$q$),
  ('27 aggregates coordinator', $q$SELECT count(*), count(word), sum(num), sum(amount), min(day), max(day), bool_or(flag) FROM s_coord_s$q$, $q$SELECT count(*), count(word), sum(num), sum(amount), min(day), max(day), bool_or(flag) FROM s_all$q$),
  ('28 aggregates any', $q$SELECT count(*), count(word), sum(num), sum(amount), min(day), max(day), bool_or(flag) FROM s_any_s$q$, $q$SELECT count(*), count(word), sum(num), sum(amount), min(day), max(day), bool_or(flag) FROM s_all$q$),
  ('29 group by coordinator', $q$SELECT flag, count(*), sum(num) FROM s_coord_s GROUP BY flag$q$, $q$SELECT flag, count(*), sum(num) FROM s_all GROUP BY flag$q$)
) v(name, q, twin) ORDER BY name;
-- text:multi: one fragment per file, also with file_as_row
SELECT name, split_part(el_digest(q), ' ', 1) AS rows, el_digest(q) = el_digest(twin) AS same_as_default
FROM (VALUES
  ('30 text multi coordinator', $q$SELECT * FROM tm_coord$q$, $q$SELECT * FROM tm_all$q$),
  ('31 text multi any join distributed', $q$SELECT * FROM tm_any JOIN el_h ON tm_any.num - 300 = el_h.num$q$, $q$SELECT * FROM tm_all JOIN el_h ON tm_all.num - 300 = el_h.num$q$),
  ('32 file as row coordinator', $q$SELECT * FROM tf_coord$q$, $q$SELECT * FROM tf_all$q$),
  ('33 file as row any', $q$SELECT * FROM tf_any$q$, $q$SELECT * FROM tf_all$q$)
) v(name, q, twin) ORDER BY name;
-- CSV: quoted fields with the delimiter and doubled quotes, empty fields
SELECT name, split_part(el_digest(q), ' ', 1) AS rows, el_digest(q) = el_digest(twin) AS same_as_default
FROM (VALUES
  ('40 csv coordinator', $q$SELECT * FROM csv_coord$q$, $q$SELECT * FROM csv_all$q$),
  ('41 csv any', $q$SELECT * FROM csv_any$q$, $q$SELECT * FROM csv_all$q$),
  ('42 csv any join distributed', $q$SELECT * FROM csv_any JOIN el_h ON csv_any.num - 400 = el_h.num$q$, $q$SELECT * FROM csv_all JOIN el_h ON csv_all.num - 400 = el_h.num$q$),
  ('43 csv quoted values coordinator', $q$SELECT word FROM csv_coord WHERE word LIKE '%,%' OR word LIKE '%"%'$q$, $q$SELECT word FROM csv_all WHERE word LIKE '%,%' OR word LIKE '%"%'$q$)
) v(name, q, twin) ORDER BY name;
-- the CSV values as read on the coordinator
SELECT num, word FROM csv_coord WHERE num % 10 IN (1, 2, 3) ORDER BY num;
