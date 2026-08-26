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
        // linkage smoke for the fragile pair: class-init woodstox against
        // the resolved stax2-api, then parse a document end to end. The
        // JAXP factory-id system property forces this specific provider
        // through the standard XMLInputFactory.newFactory() lookup path,
        // rather than relying on whichever XMLInputFactory provider the
        // ServiceLoader happens to enumerate first -- with only one
        // provider on the classpath today that's the same outcome, but a
        // future dependency (e.g. one not declared transitive = false)
        // could add a second provider and make plain service-loader
        // selection order-dependent. The property is a String, so this
        // avoids a direct reference to the woodstox class: its 6.x class
        // files carry a class-retention bnd annotation that trips
        // -Werror when compiled against directly.
        final String factoryIdProperty = "javax.xml.stream.XMLInputFactory";
        final String previous = System.getProperty(factoryIdProperty);
        System.setProperty(factoryIdProperty, "com.ctc.wstx.stax.WstxInputFactory");
        XMLInputFactory factory;
        try {
            factory = XMLInputFactory.newFactory();
        } finally {
            if (previous == null) {
                System.clearProperty(factoryIdProperty);
            } else {
                System.setProperty(factoryIdProperty, previous);
            }
        }
        assertEquals("com.ctc.wstx.stax.WstxInputFactory", factory.getClass().getName());
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
