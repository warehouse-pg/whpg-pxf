package org.greenplum.pxf.plugins.hdfs.resolved;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.io.StringReader;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Security tripwires for the dependency floor bumps: fail loudly if
 * dependency machinery ever resolves these below their security
 * floors, and touch the woodstox/stax2 pairing at real linkage level.
 * Update the expected versions together with the pins.
 */
public class FloorsResolvedVersionTest {

    private static String versionOf(String groupId, String artifactId) throws Exception {
        String path = "/META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties";
        Properties props = new Properties();
        try (InputStream in = FloorsResolvedVersionTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "no pom.properties for " + groupId + ":" + artifactId + " on the classpath");
            props.load(in);
        }
        return props.getProperty("version");
    }

    @Test
    public void resolvedFloorsMatchTheSecurityPins() throws Exception {
        assertEquals("1.11.4", versionOf("org.apache.avro", "avro"));
        assertEquals("1.28.0", versionOf("org.apache.commons", "commons-compress"));
        assertEquals("6.7.0", versionOf("com.fasterxml.woodstox", "woodstox-core"));
    }

    @Test
    public void woodstoxAndStax2PairLinkAndParse() throws Exception {
        // linkage smoke for the fragile pair: the StAX service loader must
        // select woodstox (proving it is the active provider), which
        // class-inits it against the resolved stax2-api; then parse a
        // document end to end. Loaded via the service loader rather than
        // a direct reference because woodstox 6.x class files carry a
        // class-retention bnd annotation that trips -Werror when compiled
        // against directly.
        XMLInputFactory factory = XMLInputFactory.newFactory();
        assertEquals("com.ctc.wstx.stax.WstxInputFactory", factory.getClass().getName(),
                "woodstox is not the active StAX provider");
        XMLStreamReader reader = factory
                .createXMLStreamReader(new StringReader("<a><b>ok</b></a>"));
        int events = 0;
        while (reader.hasNext()) {
            if (reader.next() == XMLStreamConstants.CHARACTERS) {
                assertEquals("ok", reader.getText());
            }
            events++;
        }
        assertEquals(6, events); // 2 starts + chars + 2 ends + END_DOCUMENT
    }
}
