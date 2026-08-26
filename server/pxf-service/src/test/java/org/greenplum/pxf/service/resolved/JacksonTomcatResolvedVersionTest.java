package org.greenplum.pxf.service.resolved;

import com.fasterxml.jackson.core.json.PackageVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.catalina.util.ServerInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Security tripwires: jackson and the embedded Tomcat are overridden
 * above the Spring Boot BOM's advisory-carrying versions; fail loudly
 * if BOM machinery ever resolves either back down. Update together
 * with the overrides.
 */
public class JacksonTomcatResolvedVersionTest {

    @Test
    public void resolvedJacksonMatchesTheSecurityOverride() {
        assertEquals("2.22.2", PackageVersion.VERSION.toString(), "jackson-core drifted");
        assertEquals("2.22.2", new ObjectMapper().version().toString(), "jackson-databind drifted");
    }

    @Test
    public void resolvedTomcatMatchesTheSecurityOverride() {
        assertEquals("9.0.121.0", ServerInfo.getServerNumber(), "embedded Tomcat drifted"); // getServerNumber appends a build component
    }
}
