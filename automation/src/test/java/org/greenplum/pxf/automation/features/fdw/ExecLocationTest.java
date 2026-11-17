package org.greenplum.pxf.automation.features.fdw;

import annotations.WorksWithFDW;
import jsystem.framework.system.SystemManagerImpl;
import org.greenplum.pxf.automation.components.pxf.Pxf;
import org.greenplum.pxf.automation.features.BaseFeature;
import org.greenplum.pxf.automation.structures.tables.basic.Table;
import org.greenplum.pxf.automation.utils.system.FDWUtils;
import org.greenplum.pxf.automation.utils.system.ProtocolEnum;
import org.greenplum.pxf.automation.utils.system.ProtocolUtils;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.Thread.sleep;

/**
 * Reads of pxf_fdw foreign tables whose mpp_execute is not 'all segments'.
 * <p>
 * With 'coordinator' (or 'master') one reader on the coordinator reads every
 * fragment. With 'any' each copy the planner runs reads every fragment, as the
 * planner treats such a table like a replicated one. Every read is compared
 * with the same read of an 'all segments' table over the same data
 * (el_digest prints the row count and whether the rows equal the default
 * read). log_errors needs 'all segments'. Writes are unchanged.
 * <p>
 * Foreign tables only, and only on HDFS: the FDW pass runs with PROTOCOL=hdfs.
 */
@WorksWithFDW
public class ExecLocationTest extends BaseFeature {

    private static final String COLUMNS = "(num int, word text, amount numeric, day date, flag boolean)";
    private static final String SMALL_ROWS = "15";

    private ProtocolEnum protocol;
    private String base;
    private Pxf pxf;
    private boolean prepared;

    @Override
    protected void beforeClass() throws Exception {
        if (!FDWUtils.useFDW || ProtocolUtils.getProtocol() != ProtocolEnum.HDFS) {
            return;
        }
        protocol = ProtocolUtils.getProtocol();
        pxf = (Pxf) SystemManagerImpl.getInstance().getSystemObject("pxf");
        base = hdfs.getWorkingDirectory() + "/exec_location";
        writeData();
        createObjects();
        prepared = true;
    }

    @Override
    protected void afterClass() throws Exception {
        super.afterClass();
        if (pxf != null) {
            pxf.close();
        }
    }

    private void requirePrepared() {
        if (!prepared) {
            throw new SkipException("foreign tables on HDFS only: mpp_execute is a foreign-table option");
        }
    }

    // ===================================================================
    // data
    // ===================================================================

    private void writeData() throws Exception {
        // small: 3 files, 15 rows, every type, NULLs in each column but num
        int[][] starts = {{11, 1}, {21, 2}, {31, 3}};
        for (int[] s : starts) {
            Table t = new Table("small" + s[1], null);
            for (int i = 0; i < 5; i++) {
                int n = s[0] + i;
                t.addRow(new String[]{
                        String.valueOf(n),
                        n % 7 == 0 ? "\\N" : "w" + n,
                        n % 5 == 0 ? "\\N" : n + ".25",
                        n % 6 == 0 ? "\\N" : String.format("2026-%02d-%02d", s[1], i + 1),
                        n % 4 == 0 ? "\\N" : (n % 2 == 0 ? "t" : "f")});
            }
            hdfs.writeTableToFile(base + "/small/part" + s[1] + ".txt", t, ",");
        }
        // many: 10 files (more fragments than segments), 20 rows
        for (int f = 1; f <= 10; f++) {
            Table t = new Table("many" + f, null);
            for (int i = 0; i < 2; i++) {
                int n = 100 + (f - 1) * 2 + i + 1;
                t.addRow(new String[]{String.valueOf(n), "m" + n, n + ".5", "2026-04-01", "t"});
            }
            hdfs.writeTableToFile(String.format("%s/many/part%02d.txt", base, f), t, ",");
        }
        // one: 1 file (fewer fragments than segments), 5 rows
        Table one = new Table("one", null);
        for (int n = 201; n <= 205; n++) {
            one.addRow(new String[]{String.valueOf(n), "o" + n, "1", "2026-05-01", "f"});
        }
        hdfs.writeTableToFile(base + "/one/data.txt", one, ",");
        // empty: 1 file, 0 rows
        hdfs.writeTableToFile(base + "/empty/data.txt", new Table("empty", null), ",");
        // bad: 10 rows, 4 of them with a non-integer num
        Table bad = new Table("bad", null);
        String[] nums = {"1", "2", "x3", "4", "y5", "6", "7", "z8", "9", "q10"};
        for (String n : nums) {
            bad.addRow(new String[]{n, "b", "1", "2026-06-01", "t"});
        }
        hdfs.writeTableToFile(base + "/bad/data.txt", bad, ",");
        // big: 100000 rows in one file
        Table big = new Table("big", null);
        for (int n = 1; n <= 100000; n++) {
            big.addRow(new String[]{String.valueOf(n), "g", "1", "2026-07-01", "t"});
        }
        hdfs.writeTableToFile(base + "/big/data.txt", big, ",");
    }

    private String resource(String dir) {
        return protocol.getExternalTablePath(hdfs.getBasePath(), base + "/" + dir);
    }

    // ===================================================================
    // servers, tables, helpers
    // ===================================================================

    private void createObjects() throws Exception {
        // drop what an earlier run left behind
        for (String s : new String[]{"el_all", "el_coord", "el_master", "el_any",
                "el_m_all", "el_m_coord", "el_m_any", "el_j_all", "el_j_coord", "el_j_any", "el_oc_srv", "el_own"}) {
            gpdb.runQuery("DROP SERVER IF EXISTS " + s + " CASCADE", true, false);
        }
        gpdb.runQuery("DROP FOREIGN DATA WRAPPER IF EXISTS el_own_pxf_fdw CASCADE", true, false);

        server("el_all", "hdfs_pxf_fdw", "config 'default'");
        server("el_coord", "hdfs_pxf_fdw", "config 'default', mpp_execute 'coordinator'");
        server("el_master", "hdfs_pxf_fdw", "config 'default', mpp_execute 'master'");
        server("el_any", "hdfs_pxf_fdw", "config 'default', mpp_execute 'any'");
        // one server per placement for the metrics checks, used by nothing else
        server("el_m_all", "hdfs_pxf_fdw", "config 'default'");
        server("el_m_coord", "hdfs_pxf_fdw", "config 'default', mpp_execute 'coordinator'");
        server("el_m_any", "hdfs_pxf_fdw", "config 'default', mpp_execute 'any'");
        // a wrapper that sets no mpp_execute: the core default is the coordinator
        gpdb.runQuery("CREATE FOREIGN DATA WRAPPER el_own_pxf_fdw HANDLER pxf_fdw_handler "
                + "VALIDATOR pxf_fdw_validator OPTIONS (protocol 'hdfs')");
        server("el_own", "el_own_pxf_fdw", "config 'default'");
        // ALTERed by option_changes, used by nothing else
        server("el_oc_srv", "hdfs_pxf_fdw", "config 'default'");
        server("el_j_all", "jdbc_pxf_fdw", "config 'database'");
        server("el_j_coord", "jdbc_pxf_fdw", "config 'database', mpp_execute 'coordinator'");
        server("el_j_any", "jdbc_pxf_fdw", "config 'database', mpp_execute 'any'");

        String text = "format 'text', delimiter ','";
        // small: every way to say where the scan runs
        table("s_all", "el_all", "small", text);
        table("s_coord_t", "el_all", "small", text + ", mpp_execute 'coordinator'");
        table("s_coord_s", "el_coord", "small", text);
        table("s_coord_upper", "el_all", "small", text + ", mpp_execute 'COORDINATOR'");
        table("s_master", "el_master", "small", text);
        table("s_own", "el_own", "small", text);
        table("s_override", "el_coord", "small", text + ", mpp_execute 'all segments'");
        table("s_any_t", "el_all", "small", text + ", mpp_execute 'any'");
        table("s_any_s", "el_any", "small", text);
        // fragment counts
        for (String d : new String[]{"many", "one", "empty"}) {
            String p = d.substring(0, 1);
            table(p + "_all", "el_all", d, text);
            table(p + "_coord", "el_coord", d, text);
            table(p + "_any", "el_any", d, text);
        }
        table("g_all", "el_all", "big", text);
        table("g_coord", "el_coord", "big", text);
        table("g_any", "el_any", "big", text);
        table("x_coord", "el_coord", "does_not_exist", text);
        // bad rows
        table("b_coord_10", "el_coord", "bad", text + ", reject_limit '10'");
        table("b_coord_3", "el_coord", "bad", text + ", reject_limit '3'");
        table("b_coord_none", "el_coord", "bad", text);
        table("b_any_10", "el_any", "bad", text + ", reject_limit '10'");
        table("b_any_3", "el_any", "bad", text + ", reject_limit '3'");
        table("b_all_log", "el_all", "bad", text + ", reject_limit '10', log_errors 'true'");
        table("b_coord_log", "el_all", "bad", text + ", mpp_execute 'coordinator', reject_limit '10', log_errors 'true'");
        table("b_any_log", "el_all", "bad", text + ", mpp_execute 'any', reject_limit '10', log_errors 'true'");
        table("b_srv_log", "el_coord", "bad", text + ", reject_limit '10', log_errors 'true'");
        table("small_coord_10", "el_coord", "small", text + ", reject_limit '10'");
        table("el_oc", "el_oc_srv", "small", text);
        // metrics
        table("mt_all", "el_m_all", "small", text);
        table("mt_coord", "el_m_coord", "small", text);
        table("mt_any", "el_m_any", "small", text);
        // Parquet written through the default path, read through each placement
        table("pq_write", "el_all", "parquet", "format 'parquet'");
        table("pq_all", "el_all", "parquet", "format 'parquet'");
        table("pq_coord", "el_coord", "parquet", "format 'parquet'");
        table("pq_any", "el_any", "parquet", "format 'parquet'");
        // writes through each placement, read back through the default path
        for (String w : new String[]{"all", "coord", "any"}) {
            String srv = w.equals("all") ? "el_all" : "el_" + w;
            table("w_" + w, srv, "w_" + w, text);
            table("w_" + w + "_back", "el_all", "w_" + w, text);
        }

        // JDBC source in this database, read through each placement
        gpdb.runQuery("DROP TABLE IF EXISTS el_jdbc_src", true, false);
        gpdb.runQuery("CREATE TABLE el_jdbc_src AS SELECT g AS num, 'j' || g AS word, "
                + "(g || '.5')::numeric AS amount, date '2026-01-01' + (g * 3) AS day, g % 2 = 0 AS flag "
                + "FROM generate_series(1, 30) g DISTRIBUTED BY (num)");
        for (String p : new String[]{"all", "coord", "any"}) {
            jdbcTable("j_" + p, "el_j_" + p, "");
            jdbcTable("jp_" + p, "el_j_" + p, ", partition_by 'num:int', range '1:30', interval '10'");
            jdbcTable("jd_" + p, "el_j_" + p, ", partition_by 'day:date', range '2026-01-01:2026-04-30', interval '1:month'");
        }

        // local tables to join with
        gpdb.runQuery("DROP TABLE IF EXISTS el_h", true, false);
        gpdb.runQuery("CREATE TABLE el_h AS SELECT g AS num FROM generate_series(1, 300) g DISTRIBUTED BY (num)");
        gpdb.runQuery("DROP TABLE IF EXISTS el_r", true, false);
        gpdb.runQuery("CREATE TABLE el_r AS SELECT g AS num FROM generate_series(1, 300) g DISTRIBUTED REPLICATED");

        // el_digest(query): "<rows> rows <md5 of the sorted rows>"; the .sql
        // files compare it with the same query over the 'all segments' table
        gpdb.runQuery("CREATE OR REPLACE FUNCTION el_digest(q text) RETURNS text AS $$ "
                + "DECLARE n bigint; h text; BEGIN "
                + "EXECUTE format('SELECT count(*), md5(coalesce(string_agg(t::text, ''|'' ORDER BY t::text), '''')) FROM (%s) t', q) INTO n, h; "
                + "RETURN n || ' rows ' || h; END $$ LANGUAGE plpgsql");
    }

    private void server(String name, String wrapper, String options) throws Exception {
        gpdb.runQuery(String.format("CREATE SERVER %s FOREIGN DATA WRAPPER %s OPTIONS (%s)", name, wrapper, options));
        gpdb.runQuery("CREATE USER MAPPING FOR CURRENT_USER SERVER " + name);
    }

    private void table(String name, String server, String dir, String options) throws Exception {
        gpdb.runQuery("DROP FOREIGN TABLE IF EXISTS " + name, true, false);
        gpdb.runQuery(String.format("CREATE FOREIGN TABLE %s %s SERVER %s OPTIONS (resource '%s', %s)",
                name, COLUMNS, server, resource(dir), options));
    }

    private void jdbcTable(String name, String server, String options) throws Exception {
        gpdb.runQuery("DROP FOREIGN TABLE IF EXISTS " + name, true, false);
        gpdb.runQuery(String.format("CREATE FOREIGN TABLE %s %s SERVER %s OPTIONS (resource 'public.el_jdbc_src'%s)",
                name, COLUMNS, server, options));
    }

    // ===================================================================
    // tests that compare with the default read (sqlrepo)
    // ===================================================================

    /** Every way to set 'coordinator' or 'any', fragment counts, types, NULLs, projection, pushdown. */
    @Test(groups = {"features", "gpdb", "hcfs"})
    public void reads() throws Exception {
        requirePrepared();
        runSqlTest("features/fdw/exec_location/reads");
    }

    /** Joins with distributed and replicated tables, self-joins, set operations, ETL statements. */
    @Test(groups = {"features", "gpdb", "hcfs"})
    public void joinsAndStatements() throws Exception {
        requirePrepared();
        runSqlTest("features/fdw/exec_location/joins");
    }

    /** JDBC without partitions (one fragment) and with int and date partitions. */
    @Test(groups = {"features", "gpdb", "jdbc"})
    public void jdbcReads() throws Exception {
        requirePrepared();
        runSqlTest("features/fdw/exec_location/jdbc");
    }

    /** Parquet written through the default path and read through each placement. */
    @Test(groups = {"features", "gpdb", "hcfs"})
    public void parquetReads() throws Exception {
        requirePrepared();
        runSqlTest("features/fdw/exec_location/parquet");
    }

    /** ALTER of the option, prepared statements and cached plans follow the new value. */
    @Test(groups = {"features", "gpdb", "hcfs"})
    public void optionChanges() throws Exception {
        requirePrepared();
        runSqlTest("features/fdw/exec_location/option_changes");
    }

    /** Rescans, LIMIT, cursors, transactions, a missing resource, a large read. */
    @Test(groups = {"features", "gpdb", "hcfs"})
    public void scanLifecycle() throws Exception {
        requirePrepared();
        runSqlTest("features/fdw/exec_location/lifecycle");
    }

    /** Reject limits per placement; log_errors needs 'all segments'. */
    @Test(groups = {"features", "gpdb", "hcfs"})
    public void badRows() throws Exception {
        requirePrepared();
        runSqlTest("features/fdw/exec_location/bad_rows");
    }

    // ===================================================================
    // where the scan ran: records PXF sent, per foreign server
    // ===================================================================

    /**
     * A coordinator read sends every row once, as segment 0 of 1; an 'any'
     * scan joined with a distributed table sends every row once per segment,
     * and the coordinator, which prepares that scan but does not run it, sends
     * nothing. EXPLAIN and a cursor that is never fetched from send nothing.
     */
    @Test(groups = {"features", "gpdb", "hcfs"})
    public void readsPerPlacement() throws Exception {
        requirePrepared();
        long segments = Long.parseLong(queryValue(
                "SELECT count(*) FROM gp_segment_configuration WHERE role = 'p' AND content >= 0"));
        long rows = Long.parseLong(SMALL_ROWS);

        for (String optimizer : new String[]{"on", "off"}) {
            String set = "SET optimizer = " + optimizer + "; ";

            Assert.assertEquals(recordsSent("el_m_all", () -> gpdb.runQuery(set + "SELECT count(*) FROM mt_all")),
                    rows, "'all segments' read, optimizer " + optimizer);
            Assert.assertEquals(recordsSent("el_m_coord", () -> gpdb.runQuery(set + "SELECT count(*) FROM mt_coord")),
                    rows, "coordinator read, optimizer " + optimizer);
            Assert.assertEquals(recordsSent("el_m_any", () -> gpdb.runQuery(set + "SELECT count(*) FROM mt_any")),
                    rows, "'any' read on its own, optimizer " + optimizer);
            Assert.assertEquals(recordsSent("el_m_any",
                            () -> gpdb.runQuery(set + "SELECT count(*) FROM mt_any JOIN el_h USING (num)")),
                    segments * rows, "'any' joined with a distributed table, optimizer " + optimizer);
            Assert.assertEquals(recordsSent("el_m_all",
                            () -> gpdb.runQuery(set + "SELECT count(*) FROM mt_all JOIN el_h USING (num)")),
                    rows, "'all segments' joined with a distributed table, optimizer " + optimizer);
            Assert.assertEquals(recordsSent("el_m_coord",
                            () -> gpdb.runQuery(set + "SELECT count(*) FROM mt_coord JOIN el_h USING (num)")),
                    rows, "coordinator joined with a distributed table, optimizer " + optimizer);
        }

        // a coordinator read reports itself as segment 0
        Assert.assertEquals(recordsSent("el_m_coord", "0", () -> gpdb.runQuery("SELECT count(*) FROM mt_coord")),
                rows, "coordinator read as segment 0");

        Assert.assertEquals(recordsSent("el_m_coord", () -> gpdb.runQuery("EXPLAIN SELECT * FROM mt_coord")),
                0L, "EXPLAIN");
        Assert.assertEquals(recordsSent("el_m_any", () -> gpdb.runQuery("EXPLAIN SELECT * FROM mt_any")),
                0L, "EXPLAIN");
        Assert.assertEquals(recordsSent("el_m_coord",
                        () -> gpdb.runQuery("BEGIN; DECLARE c CURSOR FOR SELECT * FROM mt_coord; CLOSE c; COMMIT")),
                0L, "cursor closed without a fetch");
    }

    /**
     * Writes are unchanged: each placement writes every row, the coordinator
     * writes the files of a 'coordinator' or 'any' table, and file names keep
     * the real segment id (<xid>_-1 for the coordinator).
     */
    @Test(groups = {"features", "gpdb", "hcfs"})
    public void writtenFileNames() throws Exception {
        requirePrepared();
        gpdb.runQuery("INSERT INTO w_coord SELECT g, 'c', 1, date '2026-08-01', true FROM generate_series(1, 10) g");
        gpdb.runQuery("INSERT INTO w_any SELECT g, 'a', 1, date '2026-08-01', true FROM generate_series(1, 10) g");
        gpdb.runQuery("INSERT INTO w_all SELECT num, 's', 1, date '2026-08-01', true FROM el_h");
        Assert.assertEquals(queryValue("SELECT count(*) FROM w_coord_back"), "10", "rows written by w_coord");
        Assert.assertEquals(queryValue("SELECT count(*) FROM w_any_back"), "10", "rows written by w_any");
        Assert.assertEquals(queryValue("SELECT count(*) FROM w_all_back"), "300", "rows written by w_all");
        for (String w : new String[]{"coord", "any"}) {
            List<String> files = hdfs.list(base + "/w_" + w);
            Assert.assertFalse(files.isEmpty(), "w_" + w + " wrote no file");
            for (String f : files) {
                Assert.assertTrue(f.matches(".*/[^/]+_-1(\\.[a-z0-9]+)?$"),
                        "w_" + w + " file written by the coordinator: " + f);
            }
        }
        List<String> segmentFiles = new ArrayList<>();
        for (String f : hdfs.list(base + "/w_all")) {
            Assert.assertTrue(f.matches(".*/[^/]+_[0-9]+(\\.[a-z0-9]+)?$"), "w_all file written by a segment: " + f);
            segmentFiles.add(f);
        }
        Assert.assertTrue(segmentFiles.size() > 1, "w_all written by several segments: " + segmentFiles);
    }

    // ===================================================================
    // helpers
    // ===================================================================

    private interface Action {
        void run() throws Exception;
    }

    private String queryValue(String query) throws Exception {
        Table result = new Table("result", null);
        gpdb.queryResults(result, query);
        return result.getData().get(0).get(0);
    }

    private long recordsSent(String server, Action action) throws Exception {
        return recordsSent(server, null, action);
    }

    /** Records PXF sent for the foreign server (and segment, if given) while the action ran. */
    private long recordsSent(String server, String segment, Action action) throws Exception {
        long before = settledCount(server, segment);
        action.run();
        return settledCount(server, segment) - before;
    }

    /** pxf.records.sent for the tags, read until two reads a second apart agree. */
    private long settledCount(String server, String segment) throws Exception {
        long previous = metricCount(server, segment);
        for (int i = 0; i < 10; i++) {
            sleep(1000);
            long current = metricCount(server, segment);
            if (current == previous) {
                return current;
            }
            previous = current;
        }
        return previous;
    }

    private static final Pattern COUNT = Pattern.compile("\"statistic\"\\s*:\\s*\"COUNT\"\\s*,\\s*\"value\"\\s*:\\s*([0-9.Ee+]+)");

    private long metricCount(String server, String segment) throws Exception {
        String path = "actuator/metrics/pxf.records.sent?tag=server:" + server
                + (segment == null ? "" : "&tag=segment:" + segment);
        String json = pxf.curl(pxf.getHost(), pxf.getPort(), path);
        Matcher m = COUNT.matcher(json);
        // no request with these tags yet: the endpoint answers 404
        return m.find() ? (long) Double.parseDouble(m.group(1)) : 0L;
    }
}
