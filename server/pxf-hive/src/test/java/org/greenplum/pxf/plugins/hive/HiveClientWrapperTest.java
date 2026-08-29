package org.greenplum.pxf.plugins.hive;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.HiveMetaHookLoader;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClientCompatibility2x;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.greenplum.pxf.api.model.Metadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

public class HiveClientWrapperTest {

    private Metadata.Item tblDesc;
    private HiveClientWrapper hiveClientWrapper;

    @BeforeEach
    public void setup() {
        HiveClientWrapper.HiveClientFactory factory = mock(HiveClientWrapper.HiveClientFactory.class);
        hiveClientWrapper = new HiveClientWrapper();
        hiveClientWrapper.setHiveClientFactory(factory);
    }

    @Test
    public void parseTableQualifiedNameNoDbName() {
        String name = "orphan";
        tblDesc = hiveClientWrapper.extractTableFromName(name);

        assertEquals("default", tblDesc.getPath());
        assertEquals(name, tblDesc.getName());
    }

    @Test
    public void parseTableQualifiedName() {
        String name = "not.orphan";
        tblDesc = hiveClientWrapper.extractTableFromName(name);

        assertEquals("not", tblDesc.getPath());
        assertEquals("orphan", tblDesc.getName());
    }

    @Test
    public void parseTableQualifiedNameEmpty() {
        String name = "";
        String errorMsg = "empty string is not a valid Hive table name. "
                + "Should be either <table_name> or <db_name.table_name>";

        parseTableQualifiedNameNegative(name, errorMsg, "empty string");

        name = null;
        parseTableQualifiedNameNegative(name, errorMsg, "null string");

        name = ".";
        errorMsg = surroundByQuotes(name) + " is not a valid Hive table name. "
                + "Should be either <table_name> or <db_name.table_name>";
        parseTableQualifiedNameNegative(name, errorMsg, "empty db and table names");

        name = " . ";
        errorMsg = surroundByQuotes(name) + " is not a valid Hive table name. "
                + "Should be either <table_name> or <db_name.table_name>";
        parseTableQualifiedNameNegative(name, errorMsg, "only white spaces in string");
    }

    @Test
    public void parseTableQualifiedNameTooManyQualifiers() {
        String name = "too.many.parents";
        String errorMsg = surroundByQuotes(name) + " is not a valid Hive table name. "
                + "Should be either <table_name> or <db_name.table_name>";

        parseTableQualifiedNameNegative(name, errorMsg, "too many qualifiers");
    }

    @Test
    public void invalidTableName() {

        IMetaStoreClient metaStoreClient = mock(IMetaStoreClient.class);
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> hiveClientWrapper.extractTablesFromPattern(metaStoreClient, "t.r.o.u.b.l.e.m.a.k.e.r"));
        assertEquals("\"t.r.o.u.b.l.e.m.a.k.e.r\" is not a valid Hive table name. Should be either <table_name> or <db_name.table_name>", e.getMessage());
    }

    @Test
    public void hiveClientFactoryConstructorSignatureMatchesTheMetastoreCompatibilityClient() {
        // Regression test for HiveClientWrapper.HiveClientFactory.initHiveClient's
        // RetryingMetaStoreClient.getProxy(..., new Class[]{Configuration.class,
        // HiveMetaHookLoader.class, Boolean.class}, ...) call: getProxy resolves
        // that Class[] to a constructor via reflection at proxy-creation time, so
        // a signature that doesn't match a real constructor on the target class
        // fails there, not at compile time. This is exactly the coverage that was
        // lost when HiveMetastoreCompatibilityTest.java (which exercised this via
        // a live embedded metastore) was deleted for the Hive 1.x removal.
        assertDoesNotThrow(() -> HiveMetaStoreClientCompatibility2x.class
                        .getConstructor(Configuration.class, HiveMetaHookLoader.class, Boolean.class),
                "HiveMetaStoreClientCompatibility2x must expose a (Configuration, HiveMetaHookLoader, Boolean) "
                        + "constructor matching the Class[] HiveClientWrapper.HiveClientFactory passes to "
                        + "RetryingMetaStoreClient.getProxy");
    }

    private void parseTableQualifiedNameNegative(String name, String errorMsg, String reason) {
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> hiveClientWrapper.extractTableFromName(name),
                "test should fail because of " + reason);
        assertEquals(errorMsg, e.getMessage());
    }

    private String surroundByQuotes(String str) {
        return "\"" + str + "\"";
    }
}
