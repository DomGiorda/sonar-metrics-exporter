package co.etam.sonar.prometheus;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Gauge;
import io.prometheus.client.exporter.common.TextFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sonar.api.config.Configuration;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.Metric;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.server.ws.WebService.Handler; // Explicit import for Handler
import org.sonarqube.ws.Components;
import org.sonarqube.ws.Measures;
import org.sonarqube.ws.client.LocalConnector;
import org.sonarqube.ws.client.WsClient;
import org.sonarqube.ws.client.WsClientFactories;
import org.sonarqube.ws.client.WsClientFactory;
import org.sonarqube.ws.client.components.SearchRequest;
import org.sonarqube.ws.client.components.WsComponents;
import org.sonarqube.ws.client.measures.ComponentRequest;
import org.sonarqube.ws.client.measures.WsMeasures;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PrometheusWebServiceTest {

    // --- Mocks ---
    @Mock Configuration configuration;
    @Mock WebService.Context context;
    @Mock WebService.NewController controller;
    @Mock WebService.NewAction action;
    @Mock Request request;
    @Mock Response response;
    @Mock WsClient wsClient;
    @Mock WsClientFactory wsClientFactory; // For mocking the factory chain
    @Mock WsComponents wsComponents; // Mock for wsClient.components()
    @Mock WsMeasures wsMeasures; // Mock for wsClient.measures()
    @Mock LocalConnector localConnector; // Mock for request.localConnector()

    // --- Argument Captors ---
    @Captor ArgumentCaptor<Handler> handlerCaptor;
    @Captor ArgumentCaptor<String> stringCaptor;
    @Captor ArgumentCaptor<List<String>> listStringCaptor;

    // --- Test Subject ---
    PrometheusWebService service;

    // --- Static Mocks ---
    // Needs mockito-inline dependency
    MockedStatic<WsClientFactories> mockedStaticWsClientFactories;
    MockedStatic<TextFormat> mockedStaticTextFormat;

    // --- Test Data ---
    static final Metric<Integer> METRIC_BUGS = CoreMetrics.BUGS;
    static final Metric<Integer> METRIC_VULN = CoreMetrics.VULNERABILITIES;
    static final Metric<Double> METRIC_COVERAGE = CoreMetrics.COVERAGE;
    static final Metric<Integer> METRIC_SMELLS = CoreMetrics.CODE_SMELLS;

    ByteArrayOutputStream outputStream; // Use ByteArrayOutputStream to capture output

    @BeforeEach
    void setUp() {
        // --- Instantiate Service ---
        service = new PrometheusWebService(configuration);

        // --- Clear Prometheus Registry ---
        // Crucial for test isolation as it uses the default static registry
        CollectorRegistry.defaultRegistry.clear();

        // --- Mock WebService Definition Chain ---
        when(context.createController("api/prometheus")).thenReturn(controller);
        when(controller.createAction("metrics")).thenReturn(action);
        // Capture the handler when set
        when(action.setHandler(handlerCaptor.capture())).thenReturn(action);

        // --- Mock Response Stream ---
        outputStream = new ByteArrayOutputStream(); // Capture output here
        when(response.stream()).thenReturn(response);
        when(response.setMediaType(TextFormat.CONTENT_TYPE_004)).thenReturn(response);
        when(response.setStatus(200)).thenReturn(response);
        when(response.output()).thenReturn(outputStream); // Return our capture stream

        // --- Mock WsClient Factory Chain (Static Mocking) ---
        // Start static mocking for WsClientFactories
        mockedStaticWsClientFactories = Mockito.mockStatic(WsClientFactories.class);
        mockedStaticWsClientFactories.when(WsClientFactories::getLocal).thenReturn(wsClientFactory);
        when(request.localConnector()).thenReturn(localConnector);
        when(wsClientFactory.newClient(localConnector)).thenReturn(wsClient);

        // --- Mock WsClient Services ---
        when(wsClient.components()).thenReturn(wsComponents);
        when(wsClient.measures()).thenReturn(wsMeasures);

        // --- Mock TextFormat Static Write ---
        // Mock the static write004 method to avoid actual writing complexities
        // and allow verification.
        mockedStaticTextFormat = Mockito.mockStatic(TextFormat.class);
        // Use doNothing().when() for void static methods
        mockedStaticTextFormat.when(() -> TextFormat.write004(any(OutputStreamWriter.class), any()))
                .thenAnswer(invocation -> {
                    // Optional: We could simulate writing to our ByteArrayOutputStream
                    // if needed, but verifying registry state is often enough.
                    // For now, just do nothing to allow verification of the call.
                    return null; // Required for thenAnswer on void methods
                });
    }

    @AfterEach
    void tearDown() {
        // --- Close Static Mocks ---
        // Essential to release static mocks after each test
        mockedStaticWsClientFactories.close();
        mockedStaticTextFormat.close();
    }

    @Test
    void define_shouldRegisterControllerAndAction() {
        // --- Act ---
        service.define(context);

        // --- Assert ---
        // Verify controller/action setup
        verify(context).createController("api/prometheus");
        verify(controller).setDescription("Prometheus Exporter");
        verify(controller).createAction("metrics");
        verify(action).setHandler(any(Handler.class)); // Check handler was set
        verify(controller).done();

        // Verify configuration was read initially (for updateEnabledMetrics/Gauges)
        // Check at least one known metric config key was accessed
        verify(configuration, atLeastOnce()).getBoolean(eq(PrometheusWebService.CONFIG_PREFIX + METRIC_BUGS.getKey()));
    }

    @Test
    void handle_whenNoMetricsEnabled_shouldReturnEmptyMetrics() throws Exception {
        // --- Arrange ---
        // Mock configuration: all metrics disabled
        PrometheusWebService.SUPPORTED_METRICS.forEach(metric ->
                when(configuration.getBoolean(PrometheusWebService.CONFIG_PREFIX + metric.getKey()))
                        .thenReturn(Optional.of(false))
        );

        // Call define to setup mocks and capture handler
        service.define(context);
        Handler handler = handlerCaptor.getValue();
        assertNotNull(handler, "Handler should have been captured");

        // --- Act ---
        handler.handle(request, response);

        // --- Assert ---
        // Verify response setup
        verify(response).stream();
        verify(response).setMediaType(TextFormat.CONTENT_TYPE_004);
        verify(response).setStatus(200);
        verify(response).output();

        // Verify NO interaction with WsClient for fetching data
        verifyNoInteractions(wsClientFactory); // Should not create client
        verifyNoInteractions(wsComponents);
        verifyNoInteractions(wsMeasures);

        // Verify configuration was checked again inside handler
        verify(configuration, times(2 * PrometheusWebService.SUPPORTED_METRICS.size()))
                .getBoolean(stringCaptor.capture());
        assertTrue(stringCaptor.getAllValues().contains(PrometheusWebService.CONFIG_PREFIX + METRIC_BUGS.getKey()));

        // Verify registry is empty (no gauges registered)
        assertEquals(0, CollectorRegistry.defaultRegistry.metricFamilySamples().asIterator().size(),
                "Prometheus registry should be empty when no metrics are enabled");

        // Verify TextFormat.write004 was called (even with empty registry)
        mockedStaticTextFormat.verify(() -> TextFormat.write004(any(OutputStreamWriter.class), any()));
    }

    @Test
    void handle_whenMetricsEnabled_shouldFetchAndExportMetrics() throws Exception {
        // --- Arrange ---
        // Mock configuration: enable BUGS and COVERAGE
        when(configuration.getBoolean(PrometheusWebService.CONFIG_PREFIX + METRIC_BUGS.getKey())).thenReturn(Optional.of(true));
        when(configuration.getBoolean(PrometheusWebService.CONFIG_PREFIX + METRIC_COVERAGE.getKey())).thenReturn(Optional.of(true));
        // Disable others (explicitly or rely on default Optional.empty() -> false)
        PrometheusWebService.SUPPORTED_METRICS.stream()
                .filter(m -> !m.getKey().equals(METRIC_BUGS.getKey()) && !m.getKey().equals(METRIC_COVERAGE.getKey()))
                .forEach(metric ->
                        when(configuration.getBoolean(PrometheusWebService.CONFIG_PREFIX + metric.getKey()))
                                .thenReturn(Optional.of(false)) // Or .thenReturn(Optional.empty())
                );

        // Mock project search result
        Components.Component project1 = Components.Component.newBuilder().setKey("proj-1").setName("Project One").build();
        Components.Component project2 = Components.Component.newBuilder().setKey("proj-2").setName("Project Two").build();
        List<Components.Component> projects = Arrays.asList(project1, project2);
        Components.SearchWsResponse searchResponse = Components.SearchWsResponse.newBuilder().addAllComponents(projects).build();
        // Use argThat for complex object matching if default equals is not reliable
        when(wsComponents.search(searchRequestMatcher(Qualifiers.PROJECT, "500"))).thenReturn(searchResponse);

        // Mock measures fetch for project1
        Measures.Measure measureBugs1 = Measures.Measure.newBuilder().setMetric(METRIC_BUGS.getKey()).setValue("15").build();
        Measures.Measure measureCov1 = Measures.Measure.newBuilder().setMetric(METRIC_COVERAGE.getKey()).setValue("75.5").build();
        Measures.Component project1Measures = Measures.Component.newBuilder()
                .setKey(project1.getKey()).setName(project1.getName())
                .addAllMeasures(Arrays.asList(measureBugs1, measureCov1)).build();
        Measures.ComponentWsResponse response1 = Measures.ComponentWsResponse.newBuilder().setComponent(project1Measures).build();
        List<String> expectedMetricKeys = Arrays.asList(METRIC_BUGS.getKey(), METRIC_COVERAGE.getKey());
        when(wsMeasures.component(componentRequestMatcher(project1.getKey(), expectedMetricKeys))).thenReturn(response1);

        // Mock measures fetch for project2
        Measures.Measure measureBugs2 = Measures.Measure.newBuilder().setMetric(METRIC_BUGS.getKey()).setValue("8").build();
        // Simulate project 2 not having a coverage measure returned
        Measures.Component project2Measures = Measures.Component.newBuilder()
                .setKey(project2.getKey()).setName(project2.getName())
                .addMeasures(measureBugs2).build();
        Measures.ComponentWsResponse response2 = Measures.ComponentWsResponse.newBuilder().setComponent(project2Measures).build();
        when(wsMeasures.component(componentRequestMatcher(project2.getKey(), expectedMetricKeys))).thenReturn(response2);

        // Call define to setup mocks and capture handler
        service.define(context);
        Handler handler = handlerCaptor.getValue();
        assertNotNull(handler, "Handler should have been captured");

        // --- Act ---
        handler.handle(request, response);

        // --- Assert ---
        // Verify WsClient interactions
        verify(wsClientFactory).newClient(localConnector);
        verify(wsComponents).search(searchRequestMatcher(Qualifiers.PROJECT, "500"));
        verify(wsMeasures).component(componentRequestMatcher(project1.getKey(), expectedMetricKeys));
        verify(wsMeasures).component(componentRequestMatcher(project2.getKey(), expectedMetricKeys));

        // Verify response setup
        verify(response).stream();
        verify(response).setMediaType(TextFormat.CONTENT_TYPE_004);
        verify(response).setStatus(200);
        verify(response).output();

        // Verify Gauges were registered and updated in the registry
        assertEquals(2, CollectorRegistry.defaultRegistry.metricFamilySamples().asIterator().size(),
                "Should have 2 metric families registered (bugs, coverage)");

        // Check BUGS gauge values
        assertEquals(15.0, getGaugeValue("sonarqube_" + METRIC_BUGS.getKey(), project1.getKey(), project1.getName()), 0.001);
        assertEquals(8.0, getGaugeValue("sonarqube_" + METRIC_BUGS.getKey(), project2.getKey(), project2.getName()), 0.001);

        // Check COVERAGE gauge values
        assertEquals(75.5, getGaugeValue("sonarqube_" + METRIC_COVERAGE.getKey(), project1.getKey(), project1.getName()), 0.001);
        // Check that coverage for project 2 (which wasn't returned) is absent or 0 (default Gauge behavior might be 0)
        // getSampleValue returns null if labels don't exist, which is safer to check
        assertNull(CollectorRegistry.defaultRegistry.getSampleValue(
                        "sonarqube_" + METRIC_COVERAGE.getKey(),
                        new String[]{"key", "name"},
                        new String[]{project2.getKey(), project2.getName()}),
                "Coverage for project 2 should not be set");


        // Verify TextFormat.write004 was called
        mockedStaticTextFormat.verify(() -> TextFormat.write004(any(OutputStreamWriter.class), any()));
    }

    @Test
    void handle_whenMeasureValueIsNotNumeric_shouldSkipThatMeasure() throws Exception {
        // --- Arrange ---
        // Enable BUGS metric only
        when(configuration.getBoolean(PrometheusWebService.CONFIG_PREFIX + METRIC_BUGS.getKey())).thenReturn(Optional.of(true));
        PrometheusWebService.SUPPORTED_METRICS.stream()
                .filter(m -> !m.getKey().equals(METRIC_BUGS.getKey()))
                .forEach(metric -> when(configuration.getBoolean(PrometheusWebService.CONFIG_PREFIX + metric.getKey()))
                        .thenReturn(Optional.of(false)));

        // Mock project search
        Components.Component project1 = Components.Component.newBuilder().setKey("proj-invalid").setName("Invalid Value Project").build();
        Components.SearchWsResponse searchResponse = Components.SearchWsResponse.newBuilder().addComponents(project1).build();
        when(wsComponents.search(searchRequestMatcher(Qualifiers.PROJECT, "500"))).thenReturn(searchResponse);

        // Mock measures fetch: return BUGS with a non-numeric value
        Measures.Measure measureInvalidBugs = Measures.Measure.newBuilder().setMetric(METRIC_BUGS.getKey()).setValue("not-a-number").build();
        Measures.Component projectMeasures = Measures.Component.newBuilder()
                .setKey(project1.getKey()).setName(project1.getName())
                .addMeasures(measureInvalidBugs).build();
        Measures.ComponentWsResponse measuresResponse = Measures.ComponentWsResponse.newBuilder().setComponent(projectMeasures).build();
        List<String> expectedMetricKeys = Collections.singletonList(METRIC_BUGS.getKey());
        when(wsMeasures.component(componentRequestMatcher(project1.getKey(), expectedMetricKeys))).thenReturn(measuresResponse);

        // Define and get handler
        service.define(context);
        Handler handler = handlerCaptor.getValue();

        // --- Act ---
        // Execute the handler, expecting it to log an error but not throw
        assertDoesNotThrow(() -> handler.handle(request, response));

        // --- Assert ---
        // Verify interactions happened
        verify(wsMeasures).component(componentRequestMatcher(project1.getKey(), expectedMetricKeys));

        // Verify the gauge for BUGS was registered BUT the specific label set was NOT added
        assertEquals(1, CollectorRegistry.defaultRegistry.metricFamilySamples().asIterator().size(), "Bugs metric family should be registered");
        assertNull(CollectorRegistry.defaultRegistry.getSampleValue(
                        "sonarqube_" + METRIC_BUGS.getKey(),
                        new String[]{"key", "name"},
                        new String[]{project1.getKey(), project1.getName()}),
                "Gauge value should not be set for non-numeric input");

        // Verify response was still sent successfully
        verify(response).setStatus(200);
        verify(response).output();
        mockedStaticTextFormat.verify(() -> TextFormat.write004(any(OutputStreamWriter.class), any()));
    }

    // --- Helper Methods ---

    /**
     * Helper to get a specific gauge value from the default registry.
     * Asserts that the value exists before returning.
     */
    private double getGaugeValue(String name, String labelKey, String labelName) {
        Double value = CollectorRegistry.defaultRegistry.getSampleValue(
                name,
                new String[]{"key", "name"},
                new String[]{labelKey, labelName}
        );
        assertNotNull(value, "Gauge value for " + name + " with labels [" + labelKey + ", " + labelName + "] should not be null");
        return value;
    }

    /**
     * Creates an ArgumentMatcher for SearchRequest.
     */
    private SearchRequest searchRequestMatcher(String qualifier, String ps) {
        return argThat(req -> req != null &&
                req.getQualifiersList().equals(Collections.singletonList(qualifier)) &&
                req.getPs().equals(ps)
        );
    }

    /**
     * Creates an ArgumentMatcher for ComponentRequest.
     * Uses Sets for metricKeys to ignore order.
     */
    private ComponentRequest componentRequestMatcher(String componentKey, List<String> metricKeys) {
        Set<String> expectedKeys = new HashSet<>(metricKeys);
        return argThat(req -> req != null &&
                req.getComponent().equals(componentKey) &&
                new HashSet<>(req.getMetricKeysList()).equals(expectedKeys)
        );
    }
}
