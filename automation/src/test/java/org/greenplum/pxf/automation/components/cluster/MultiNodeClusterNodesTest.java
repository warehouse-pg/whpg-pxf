package org.greenplum.pxf.automation.components.cluster;

import org.greenplum.pxf.automation.components.cluster.PhdCluster.EnumClusterServices;
import org.greenplum.pxf.automation.components.cluster.installer.nodes.CoordinatorNode;
import org.greenplum.pxf.automation.components.cluster.installer.nodes.Node;
import org.greenplum.pxf.automation.components.cluster.installer.nodes.SegmentNode;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.testng.Assert.assertEquals;

/**
 * Pins {@link MultiNodeCluster#nodesForOperation} on the layout of MultiNodesCluster.xml. Pure-logic
 * test, no cluster needed: {@code mvn -Dtest=MultiNodeClusterNodesTest test} from automation/.
 */
public class MultiNodeClusterNodesTest {

    private static Node node(Node node, String host, String services) {
        node.setHost(host);
        node.setServices(services);
        return node;
    }

    private static List<String> hosts(List<Node> nodes) {
        return nodes.stream().map(Node::getHost).collect(Collectors.toList());
    }

    private static MultiNodeCluster cluster() {
        MultiNodeCluster cluster = new MultiNodeCluster(true);
        cluster.setNodes(new Node[]{
                node(new CoordinatorNode(), "cdw", "gpdb,pxf,hive"),
                node(new SegmentNode(), "sdw1", "pxf"),
                node(new SegmentNode(), "sdw2", "pxf"),
                node(new SegmentNode(), "hadoop", "hbase,hive"),
        });
        return cluster;
    }

    @Test
    public void pxfRunsOnEveryPxfNodeIncludingTheCoordinator() {
        assertEquals(hosts(cluster().nodesForOperation(EnumClusterServices.pxf)), Arrays.asList("cdw", "sdw1", "sdw2"));
    }

    @Test
    public void hiveRunsOnTheCoordinatorOnly() {
        assertEquals(hosts(cluster().nodesForOperation(EnumClusterServices.hive)), Arrays.asList("cdw"));
    }

    @Test
    public void otherServicesRunOnEveryNodeThatListsThem() {
        assertEquals(hosts(cluster().nodesForOperation(EnumClusterServices.hbase)), Arrays.asList("hadoop"));
    }
}
