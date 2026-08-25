package org.greenplum.pxf.service.resolved;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Security tripwire: snakeyaml is overridden above the Spring Boot
 * BOM's advisory-carrying 1.30; fail loudly if BOM machinery ever
 * resolves it back down. Update together with the override.
 */
public class SnakeYamlResolvedVersionTest {

    @Test
    public void resolvedSnakeYamlMatchesTheSecurityOverride() throws Exception {
        Properties props = new Properties();
        try (InputStream in = SnakeYamlResolvedVersionTest.class
                .getResourceAsStream("/META-INF/maven/org.yaml/snakeyaml/pom.properties")) {
            assertNotNull(in, "no snakeyaml pom.properties on the classpath");
            props.load(in);
        }
        assertEquals("2.5", props.getProperty("version"));
    }
}
