-- @description query01 for PXF HDFS Readable: with a reject limit, a WHERE-clause error still fails the query
-- start_matchsubs
--
-- m/invalid input syntax for/
-- s/invalid input syntax for.*/invalid input syntax for integer/
--
-- end_matchsubs
SELECT * FROM reject_limit_where_cast WHERE words::int > 0 ORDER BY num;
