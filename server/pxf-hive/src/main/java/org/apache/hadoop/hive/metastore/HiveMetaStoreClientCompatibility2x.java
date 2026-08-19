package org.apache.hadoop.hive.metastore;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * A {@link HiveMetaStoreClient} that can talk to Hive 2.x metastores.
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
        try {
            return super.listPartitions(dbName, tblName, maxParts);
        } catch (TException e) {
            if (!isUnknownMethod(e)) {
                throw e;
            }
            LOG.debug("Metastore rejected the partitions request RPC, falling back to get_partitions", e);
            return client.get_partitions(dbName, tblName, maxParts);
        }
    }

    @Override
    public List<Partition> listPartitionsByFilter(String dbName, String tblName, String filter, short maxParts)
            throws TException {
        try {
            return super.listPartitionsByFilter(dbName, tblName, filter, maxParts);
        } catch (TException e) {
            if (!isUnknownMethod(e)) {
                throw e;
            }
            LOG.debug("Metastore rejected the partitions-by-filter request RPC, falling back to get_partitions_by_filter", e);
            return client.get_partitions_by_filter(dbName, tblName, filter, maxParts);
        }
    }

    /**
     * True when the server answered "I do not implement this RPC" — the
     * signature of a pre-3.x metastore receiving a request-object call.
     */
    private boolean isUnknownMethod(TException e) {
        if (!(e instanceof TApplicationException)) {
            return false;
        }
        TApplicationException tae = (TApplicationException) e;
        return tae.getType() == TApplicationException.UNKNOWN_METHOD
                || String.valueOf(tae.getMessage()).contains("Invalid method name");
    }
}
