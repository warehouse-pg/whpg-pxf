-- start_matchsubs
--
-- m/\S*pxf_automation_data\/[0-9a-fA-F-]{36}\//
-- s/\S*pxf_automation_data\/[0-9a-fA-F-]{36}\//WORKDIR\//
--
-- end_matchsubs
-- A coordinator or 'any' read runs in the session's own backend, so whatever
-- a scan leaves open stays open until the session ends.  The backend's open
-- file descriptors are counted before and after scans that end in every way a
-- scan can end: at the end of the data, stopped early by LIMIT, rescanned, run
-- again from a cached plan, and failed (/proc on Linux, lsof elsewhere).
CREATE TEMP TABLE el_fds (at text, n int) DISTRIBUTED RANDOMLY;
DO $$
BEGIN
    EXECUTE format($c$COPY el_fds (n) FROM PROGRAM
        'n=$(ls /proc/%1$s/fd 2>/dev/null | wc -l); [ $n -gt 0 ] || n=$(lsof -p %1$s 2>/dev/null | wc -l); echo $n'$c$,
        pg_backend_pid());
    UPDATE el_fds SET at = 'before' WHERE at IS NULL;
END $$;
-- reads to the end and reads stopped early by LIMIT (100000 rows in big);
-- plpgsql runs each statement from its cached plan after the first time
DO $$
BEGIN
    FOR i IN 1..20 LOOP
        PERFORM count(*) FROM s_coord_s;
        PERFORM count(*) FROM s_any_s;
        PERFORM num FROM g_coord LIMIT 3;
        PERFORM num FROM g_any LIMIT 3;
    END LOOP;
END $$;
-- rescans: with the Postgres planner the subquery rescans the scan for each
-- outer row (GPORCA reads it once into a Materialize)
SET optimizer = off;
SELECT count(*) FROM (SELECT g, (SELECT count(*) FROM s_coord_s WHERE num > g) c
                      FROM generate_series(1, 50) g) x WHERE c >= 0;
SELECT count(*) FROM (SELECT g, (SELECT count(*) FROM s_any_s WHERE num > g) c
                      FROM generate_series(1, 50) g) x WHERE c >= 0;
-- rescans that stop each read early: LIMIT 1 ends the subquery's scan in the
-- middle of the stream, so the rescan closes a read that is still open
SELECT count(*) FROM (SELECT g, (SELECT num FROM g_coord WHERE num > g LIMIT 1) c
                      FROM generate_series(1, 50) g) x WHERE c >= 0;
SELECT count(*) FROM (SELECT g, (SELECT num FROM g_any WHERE num > g LIMIT 1) c
                      FROM generate_series(1, 50) g) x WHERE c >= 0;
-- a prepared statement runs the same plan again
PREPARE el_p AS SELECT count(*), sum(num) FROM s_coord_s;
EXECUTE el_p;
EXECUTE el_p;
EXECUTE el_p;
RESET optimizer;
PREPARE el_q AS SELECT count(*), sum(num) FROM s_any_s;
EXECUTE el_q;
EXECUTE el_q;
EXECUTE el_q;
-- failed reads, each in its own subtransaction
DO $$
BEGIN
    FOR i IN 1..20 LOOP
        BEGIN
            PERFORM * FROM x_coord;
        EXCEPTION WHEN OTHERS THEN
            NULL;
        END;
    END LOOP;
END $$;
DO $$
BEGIN
    EXECUTE format($c$COPY el_fds (n) FROM PROGRAM
        'n=$(ls /proc/%1$s/fd 2>/dev/null | wc -l); [ $n -gt 0 ] || n=$(lsof -p %1$s 2>/dev/null | wc -l); echo $n'$c$,
        pg_backend_pid());
    UPDATE el_fds SET at = 'after' WHERE at IS NULL;
END $$;
SELECT (SELECT n FROM el_fds WHERE at = 'after')
     - (SELECT n FROM el_fds WHERE at = 'before') < 10 AS scans_release_their_connections;
SELECT 1 AS still_connected;
