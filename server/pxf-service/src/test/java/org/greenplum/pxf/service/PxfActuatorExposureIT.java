package org.greenplum.pxf.service;

import org.greenplum.pxf.api.model.RequestContext;
import org.greenplum.pxf.service.controller.ReadService;
import org.greenplum.pxf.service.controller.WriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.mockito.Mock;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.MultiValueMap;

import static org.junit.jupiter.api.condition.OS.MAC;

/**
 * Pins the documented operator escape hatch for the shutdown actuator
 * endpoint.
 * <p>
 * The endpoint is disabled by default (see
 * {@code PxfMetricsIT#test_ShutdownEndpoint_NotExposed}), and
 * {@code $PXF_BASE/conf/pxf-application.properties} documents the two
 * properties that restore it. This test applies exactly those two
 * documented properties and asserts the endpoint becomes available again,
 * so the published recovery procedure cannot silently rot.
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
// same JVM-crash reason as PxfMetricsIT: disabled on MacOS, runs in CI
@DisabledOnOs(MAC)
public class PxfActuatorExposureIT {

    @LocalServerPort
    private int port;

    @MockBean
    private RequestParser<MultiValueMap<String, String>> mockParser;

    @MockBean
    private ReadService readService;

    @MockBean
    private WriteService mockWriteService;

    @Mock
    private RequestContext mockContext;

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
