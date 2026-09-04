package org.greenplum.pxf.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Pins the documented operator escape hatch for the shutdown actuator
 * endpoint.
 * <p>
 * The endpoint is disabled by default (pinned in {@link PxfMetricsIT}, whose
 * context runs without the override properties), and the documentation names
 * the two properties that restore it. This test applies exactly those two
 * documented properties and asserts the endpoint becomes available again,
 * so the published recovery procedure cannot silently rot.
 * <p>
 * This test pins the property <em>semantics</em>. Operators apply the same
 * properties via {@code $PXF_BASE/conf/pxf-application.properties}, which
 * takes effect because the pxf CLI passes
 * {@code --spring.config.location=classpath:/application.properties,file:$PXF_BASE/conf/pxf-application.properties}
 * at launch and later locations win; that precedence rests on the
 * {@code RUN_ARGS} assembly in {@code server/pxf-service/src/scripts/pxf}.
 * <p>
 * Availability is asserted through the actuator discovery index rather than
 * by POSTing to the endpoint: a POST would shut down the test's application
 * context.
 * <p>
 * Contract pin (not a quirk pin): the default-off behavior and the
 * documented way to override it are both promises to operators.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = PxfServiceApplication.class,
        properties = {
                "management.endpoints.web.exposure.include=health,info,shutdown,metrics,prometheus",
                "management.endpoint.shutdown.enabled=true"
        })
// Unlike PxfMetricsIT, this class carries no OS-based skip: the sibling's
// JVM-crash workaround dates to Intel Macs on early JDK 8 builds, and the
// crash did not reproduce when this class was run on macOS under either an
// x86_64/JDK 8 or an arm64/JDK 11 JVM. Keeping it runnable locally matters:
// OS-skipped tests here can only ever fail in CI.
public class PxfActuatorExposureIT {

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    public void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    public void test_ShutdownEndpoint_CanBeReEnabledAsDocumented() {
        // with the documented properties applied, the discovery index must
        // advertise the shutdown endpoint again
        client.get().uri("/actuator")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$._links.shutdown").exists();
    }

    @Test
    public void test_DocumentedEndpoints_StillExposedWhenShutdownReEnabled() {
        // re-enabling shutdown must not disturb the documented monitoring set.
        //
        // prometheus is deliberately NOT asserted here: this test class does
        // not carry @AutoConfigureMetrics, so the test context has only the
        // default SimpleMeterRegistry - /actuator/metrics is present but the
        // prometheus endpoint (which needs the Prometheus registry supplied by
        // the metrics export auto-configuration, switched off in tests) is not.
        // On a running service all four are exposed. PxfMetricsIT, which does
        // enable metrics, owns the prometheus assertions.
        client.get().uri("/actuator")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$._links.health").exists()
                .jsonPath("$._links.info").exists()
                .jsonPath("$._links.metrics").exists();
    }
}
