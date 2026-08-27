package org.apache.hadoop.hive.metastore;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.utils.FilterUtils;
import org.apache.hadoop.hive.metastore.utils.MetaStoreUtils;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;

/**
 * A {@link HiveMetaStoreClient} that can talk to Hive 2.3+ metastores
 * (the floor is 2.3, not 2.x more broadly: getHiveTable's get_table_req
 * call first appears in the thrift IDL in 2.3.0).
 * <p>
 * The Hive 4.x client issues request-object RPCs (get_partitions_req,
 * get_partitions_by_filter_req) that older metastore servers do not
 * implement — such servers answer with
 * {@code TApplicationException: Invalid method name}. The legacy
 * positional RPCs are still part of the 4.x thrift bindings, so when the
 * modern call is rejected this client retries through them. This mirrors
 * the compatibility client PXF used to carry for Hive 1.x metastores
 * (whose raw get_table call no longer exists in the 4.x bindings).
 * <p>
 * Lives in the org.apache.hadoop.hive.metastore package to reach the
 * package-private thrift {@code client} field.
 * <p>
 * Implements {@link IMetaStoreClient} explicitly (although the superclass
 * already does) because RetryingMetaStoreClient builds its dynamic proxy
 * from {@code baseClass.getInterfaces()}, which only reports interfaces
 * the class itself declares.
 */
@SuppressWarnings("deprecation") // re-declaring IMetaStoreClient re-surfaces its deprecated members
public class HiveMetaStoreClientCompatibility2x extends HiveMetaStoreClient implements IMetaStoreClient {

    private static final Logger LOG = LoggerFactory.getLogger(HiveMetaStoreClientCompatibility2x.class);

    // Set the first time a modern request-object RPC is rejected as
    // unknown, so every later partition call against the same (necessarily
    // still pre-3.x) metastore goes straight to the legacy RPC instead of
    // paying a doomed modern-RPC round trip first, forever. One shared flag
    // for both RPC pairs: get_partitions_req and get_partitions_by_filter_req
    // were added to the thrift IDL together, so a server missing one is
    // missing both -- this is a property of the server's Hive version, not
    // of which specific call happened to be tried first.
    private volatile boolean useLegacyPartitionRpcs = false;

    public HiveMetaStoreClientCompatibility2x(Configuration conf) throws MetaException {
        super(conf);
    }

    public HiveMetaStoreClientCompatibility2x(Configuration conf, HiveMetaHookLoader hookLoader) throws MetaException {
        super(conf, hookLoader);
    }

    public HiveMetaStoreClientCompatibility2x(Configuration conf, HiveMetaHookLoader hookLoader, Boolean allowEmbedded)
            throws MetaException {
        super(conf, hookLoader, allowEmbedded);
    }

    @Override
    public List<Partition> listPartitions(String dbName, String tblName, short maxParts) throws TException {
        if (useLegacyPartitionRpcs) {
            return legacyListPartitions(dbName, tblName, maxParts, null);
        }
        try {
            return super.listPartitions(dbName, tblName, maxParts);
        } catch (TException modernRpcFailure) {
            if (!isUnknownMethod(modernRpcFailure)) {
                throw modernRpcFailure;
            }
            LOG.debug("Metastore rejected the partitions request RPC, falling back to get_partitions", modernRpcFailure);
            useLegacyPartitionRpcs = true;
            return legacyListPartitions(dbName, tblName, maxParts, modernRpcFailure);
        }
    }

    @Override
    public List<Partition> listPartitionsByFilter(String dbName, String tblName, String filter, short maxParts)
            throws TException {
        if (useLegacyPartitionRpcs) {
            return legacyListPartitionsByFilter(dbName, tblName, filter, maxParts, null);
        }
        try {
            return super.listPartitionsByFilter(dbName, tblName, filter, maxParts);
        } catch (TException modernRpcFailure) {
            if (!isUnknownMethod(modernRpcFailure)) {
                throw modernRpcFailure;
            }
            LOG.debug("Metastore rejected the partitions-by-filter request RPC, falling back to get_partitions_by_filter",
                    modernRpcFailure);
            useLegacyPartitionRpcs = true;
            return legacyListPartitionsByFilter(dbName, tblName, filter, maxParts, modernRpcFailure);
        }
    }

    private List<Partition> legacyListPartitions(String dbName, String tblName, short maxParts,
                                                   TException modernRpcFailure) throws TException {
        try {
            return deepCopyPartitions(FilterUtils.filterPartitionsIfEnabled(isClientFilterEnabled(), filterHook(),
                    client.get_partitions(dbName, tblName, maxParts)));
        } catch (TException fallbackFailure) {
            if (modernRpcFailure != null) {
                fallbackFailure.addSuppressed(modernRpcFailure);
            }
            throw fallbackFailure;
        }
    }

    private List<Partition> legacyListPartitionsByFilter(String dbName, String tblName, String filter, short maxParts,
                                                           TException modernRpcFailure) throws TException {
        try {
            return deepCopyPartitions(FilterUtils.filterPartitionsIfEnabled(isClientFilterEnabled(), filterHook(),
                    client.get_partitions_by_filter(dbName, tblName, filter, maxParts)));
        } catch (TException fallbackFailure) {
            if (modernRpcFailure != null) {
                fallbackFailure.addSuppressed(modernRpcFailure);
            }
            throw fallbackFailure;
        }
    }

    /**
     * super.getDatabases(pattern) always issues get_databases with the
     * pattern prefixed by the configured catalog name (bytecode-verified:
     * MetaStoreUtils.prependCatalogToDbName), e.g. "@hive#mypattern". A
     * pre-3.0 metastore has no concept of catalogs and pattern-matches
     * that literally against real database names, so it always returns
     * an empty list -- not an exception, so there is nothing an
     * isUnknownMethod-style try/catch could detect here. PXF has no
     * multi-catalog support anywhere else in this codebase, so
     * unconditionally issuing the legacy, un-prefixed get_databases RPC
     * is correct for both pre-3.0 and 4.x metastores in PXF's case.
     */
    @Override
    public List<String> getDatabases(String databasePattern) throws TException {
        return FilterUtils.filterDbNamesIfEnabled(isClientFilterEnabled(), filterHook(),
                client.get_databases(databasePattern));
    }

    /**
     * super.getTables(dbName, tablePattern) issues get_table_objects_by_name_req
     * with tblNames=null and the pattern in a Hive-4-only tablesPattern
     * field (bytecode-verified). A pre-3.0 metastore ignores the unknown
     * field and sees only tblNames=null, which its implementation treats
     * as an error ("<db> cannot find null tables", InvalidOperationException) --
     * verified against Hive 2.3.8's bytecode. Same rationale as
     * getDatabases above for going straight to the legacy RPC
     * unconditionally rather than trying the modern call first.
     */
    @Override
    public List<String> getTables(String dbName, String tablePattern) throws MetaException {
        // super's own getTables(String,String) declares only throws
        // MetaException (narrower than the interface) and follows the
        // same catch-and-rethrow-as-MetaException convention for the raw
        // TException the thrift call can throw.
        try {
            return FilterUtils.filterTableNamesIfEnabled(isClientFilterEnabled(), filterHook(), dbName, tablePattern,
                    client.get_tables(dbName, tablePattern));
        } catch (TException e) {
            MetaStoreUtils.throwMetaException(e);
            return null; // unreachable -- throwMetaException always throws
        }
    }

    /**
     * True when the server answered "I do not implement this RPC" — the
     * signature of a pre-3.x metastore receiving a request-object call.
     * Restricted to the two thrift type codes that actually mean this
     * (UNKNOWN_METHOD, or UNKNOWN -- the default when a TApplicationException
     * is built from a message alone, e.g. the single-arg constructor real
     * thrift 0.9.3 servers' generated code uses). A broader message-text
     * match ("contains 'Invalid method name'") used to be ORed in here,
     * but that checked the message regardless of type, so an unrelated
     * TApplicationException whose text happened to mention that phrase
     * would also have silently triggered the fallback.
     */
    private boolean isUnknownMethod(TException e) {
        if (!(e instanceof TApplicationException)) {
            return false;
        }
        int type = ((TApplicationException) e).getType();
        return type == TApplicationException.UNKNOWN_METHOD || type == TApplicationException.UNKNOWN;
    }

    /*
     * filterHook and isClientFilterEnabled are private on
     * HiveMetaStoreClient with no accessor, so the legacy-RPC paths above
     * (which bypass the modern methods that would otherwise apply
     * FilterUtils themselves) read them via reflection to reproduce the
     * same client-side MetaStoreFilterHook authorization the modern path
     * gets. Fetched fresh (not cached) since neither is set more than
     * once per client lifetime, keeping this simple.
     */
    private MetaStoreFilterHook filterHook() {
        return (MetaStoreFilterHook) getSuperclassPrivateField("filterHook");
    }

    private boolean isClientFilterEnabled() {
        return (boolean) getSuperclassPrivateField("isClientFilterEnabled");
    }

    private Object getSuperclassPrivateField(String name) {
        try {
            Field field = HiveMetaStoreClient.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(this);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Unable to read HiveMetaStoreClient." + name + " via reflection", e);
        }
    }
}
