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
import org.sonar.api.server.ws.RequestHandler;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.server.ws.LocalConnector; // Import the correct LocalConnector
import org.sonarqube.ws.Components;
import org.sonarqube.ws.Measures;
import org.sonarqube.ws.client.WsConnector; // Use WsConnector interface
import org.sonarqube.ws.client.WsClient;
import org.sonarqube.ws.client.WsClientFactories;
import org.sonarqube.ws.client.components.ComponentsService; // Use service interface
import org.sonarqube.ws.client.components.SearchRequest;
import org.sonarqube.ws.client.measures.ComponentRequest;
import org.sonarqube.ws.client.measures.MeasuresService; // Use service interface


import java.io.ByteArrayInputStream; // Needed for potential input stream mocking if required later
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
    @Mock Response.Stream responseStream; // Mock the Stream object separately
    @Mock WsClient wsClient;
    @Mock WsConnector wsConnector;
    @Mock ComponentsService componentsService; // Mock for wsClient.components()
    @Mock MeasuresService measuresService; // Mock for wsClient.measures()
    @Mock LocalConnector mockLocalConnector; // Mock the correct LocalConnector type returned by request.localConnector()

    // --- Argument Captors ---
    @Captor ArgumentCaptor<RequestHandler> handlerCaptor; // Capture RequestHandler
    @Captor ArgumentCaptor<String> stringCaptor;
    @Captor ArgumentCaptor<List<String>> listStringCaptor;

    // --- Test Subject ---
    PrometheusWebService service;

    // --- Static Mocks ---
    // Needs mockito-inline dependency
    MockedStatic<WsClientFactories> mockedStaticWsClientFactories;
    WsClientFactories wsClientFactory;
    MockedStatic<TextFormat> mockedStaticTextFormat;

    // --- Test Data ---
    static final Metric<Integer> METRIC_BUGS = CoreMetrics.BUGS;
    static final Metric<Integer> METRIC_VULN = CoreMetrics.VULNERABILITIES;
    static final Metric<Double> METRIC_COVERAGE = CoreMetrics.COVERAGE;
    static final Metric<Metric.Level> METRIC_ALERT_STATUS = CoreMetrics.ALERT_STATUS; // Adjust type based on compiler error
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
        when(response.stream()).thenReturn(responseStream); // stream() returns the mock Stream
        when(responseStream.setMediaType(TextFormat.CONTENT_TYPE_004)).thenReturn(responseStream); // Methods called on Stream
        when(responseStream.setStatus(200)).thenReturn(responseStream); // Methods called on Stream
        when(responseStream.output()).thenReturn(outputStream); // output() called on Stream

        // --- Mock WsClient Factory Chain (Static Mocking) ---
        // Start static mocking for WsClientFactories
        mockedStaticWsClientFactories = Mockito.mockStatic(WsClientFactories.class);
        wsClientFactory = Mockito.mock(WsClientFactories.class);
        mockedStaticWsClientFactories.when(WsClientFactories::getLocal).thenReturn(wsClientFactory);
        when(wsClientFactory.newClient(any(WsConnector.class))).thenReturn(wsClient);

        // --- Mock WsClient Service Interfaces ---
        when(wsClient.components()).thenReturn(componentsService);
        when(wsClient.measures()).thenReturn(measuresService);

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
        verify(action).setHandler(any(RequestHandler.class)); // Check RequestHandler was set
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
        RequestHandler handler = handlerCaptor.getValue();
        assertNotNull(handler, "Handler should have been captured");

        // --- Act ---
        handler.handle(request, response);

        // --- Assert ---
        // Verify response stream setup
        verify(response).stream();
        verify(responseStream).setMediaType(TextFormat.CONTENT_TYPE_004);
        verify(responseStream).setStatus(200);
        verify(responseStream).output();

        // Verify NO interaction with WsClient for fetching data
        verifyNoInteractions(wsClientFactory); // Should not create client if no metrics enabled
        verifyNoInteractions(componentsService); // Use correct mock name
        verifyNoInteractions(measuresService); // Use correct mock name

        // Verify configuration was checked again inside handler
        verify(configuration, times(2 * PrometheusWebService.SUPPORTED_METRICS.size()))
                .getBoolean(stringCaptor.capture());
        assertTrue(stringCaptor.getAllValues().contains(PrometheusWebService.CONFIG_PREFIX + METRIC_BUGS.getKey()));

        // Verify registry is empty (no gauges registered)
        assertEquals(0, Collections.list(CollectorRegistry.defaultRegistry.metricFamilySamples()).size(), // Convert Enumeration to List
                "Prometheus registry should be empty when no metrics are enabled");

        // Verify TextFormat.write004 was called (even with empty registry)
        mockedStaticTextFormat.verify(() -> TextFormat.write004(any(OutputStreamWriter.class), any()));
    }

    @Test
    void handle_whenMetricsEnabled_shouldFetchAndExportNumericAndAlertStatusMetrics() throws Exception {
        // --- Arrange ---
        // Mock configuration: enable BUGS, COVERAGE, and ALERT_STATUS
        when(configuration.getBoolean(PrometheusWebService.CONFIG_PREFIX + METRIC_BUGS.getKey())).thenReturn(Optional.of(true));
        when(configuration.getBoolean(PrometheusWebService.CONFIG_PREFIX + METRIC_COVERAGE.getKey())).thenReturn(Optional.of(true));
        when(configuration.getBoolean(PrometheusWebService.CONFIG_PREFIX + METRIC_ALERT_STATUS.getKey())).thenReturn(Optional.of(true));
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
        when(componentsService.search(searchRequestMatcher(Qualifiers.PROJECT, "500"))).thenReturn(searchResponse);

        // Mock measures fetch for project1
        Measures.Measure measureBugs1 = Measures.Measure.newBuilder().setMetric(METRIC_BUGS.getKey()).setValue("15").build();
        Measures.Measure measureCov1 = Measures.Measure.newBuilder().setMetric(METRIC_COVERAGE.getKey()).setValue("75.5").build();
        Measures.Measure measureAlert1 = Measures.Measure.newBuilder().setMetric(METRIC_ALERT_STATUS.getKey()).setValue("OK").build();
        Measures.Component project1Measures = Measures.Component.newBuilder()
                .setKey(project1.getKey()).setName(project1.getName())
                .addAllMeasures(Arrays.asList(measureBugs1, measureCov1, measureAlert1)).build();
        Measures.ComponentWsResponse response1 = Measures.ComponentWsResponse.newBuilder().setComponent(project1Measures).build();
        List<String> expectedMetricKeys = Arrays.asList(METRIC_BUGS.getKey(), METRIC_COVERAGE.getKey(), METRIC_ALERT_STATUS.getKey());
        when(measuresService.component(componentRequestMatcher(project1.getKey(), expectedMetricKeys))).thenReturn(response1);

        // Mock measures fetch for project2
        Measures.Measure measureBugs2 = Measures.Measure.newBuilder().setMetric(METRIC_BUGS.getKey()).setValue("8").build();
        Measures.Measure measureAlert2 = Measures.Measure.newBuilder().setMetric(METRIC_ALERT_STATUS.getKey()).setValue("ERROR").build();
        // Simulate project 2 not having a coverage measure returned
        Measures.Component project2Measures = Measures.Component.newBuilder()
                .setKey(project2.getKey()).setName(project2.getName())
                .addAllMeasures(Arrays.asList(measureBugs2, measureAlert2)).build();
        Measures.ComponentWsResponse response2 = Measures.ComponentWsResponse.newBuilder().setComponent(project2Measures).build();
        when(measuresService.component(componentRequestMatcher(project2.getKey(), expectedMetricKeys))).thenReturn(response2);

        // Call define to setup mocks and capture handler
        service.define(context);
        RequestHandler handler = handlerCaptor.getValue();
        assertNotNull(handler, "Handler should have been captured");

        // --- Act ---
        handler.handle(request, response);

        // --- Assert ---
        // Verify WsClient interactions (using the service interface mocks) - Corrected verification
        verify(wsClientFactory).newClient(any(LocalConnector.class)); // Verify with correct mock
        verify(componentsService).search(searchRequestMatcher(Qualifiers.PROJECT, "500"));
        verify(measuresService).component(componentRequestMatcher(project1.getKey(), expectedMetricKeys));
        verify(measuresService).component(componentRequestMatcher(project2.getKey(), expectedMetricKeys));

        // Verify response setup
        verify(response).stream();
        verify(responseStream).setMediaType(TextFormat.CONTENT_TYPE_004);
        verify(responseStream).setStatus(200);
        verify(responseStream).output();

        // Verify Gauges were registered and updated in the registry
        assertEquals(3, Collections.list(CollectorRegistry.defaultRegistry.metricFamilySamples()).size(), // Convert Enumeration to List, expecting 3 (bugs, coverage, alert_status)
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

        // Check ALERT_STATUS gauge values (mapped)
        assertEquals(1.0, getGaugeValue("sonarqube_" + METRIC_ALERT_STATUS.getKey(), project1.getKey(), project1.getName()), 0.001, "Alert Status OK should be 1.0");
        assertEquals(3.0, getGaugeValue("sonarqube_" + METRIC_ALERT_STATUS.getKey(), project2.getKey(), project2.getName()), 0.001, "Alert Status ERROR should be 3.0");


        // Verify TextFormat.write004 was called
        mockedStaticTextFormat.verify(() -> TextFormat.write004(any(OutputStreamWriter.class), any()));
    }

    @Test
    void handle_whenMeasureValueIsNotNumeric_shouldSetDefaultValue() throws Exception {
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
        when(componentsService.search(searchRequestMatcher(Qualifiers.PROJECT, "500"))).thenReturn(searchResponse);

        // Mock measures fetch: return BUGS with a non-numeric value
        Measures.Measure measureInvalidBugs = Measures.Measure.newBuilder().setMetric(METRIC_BUGS.getKey()).setValue("not-a-number").build();
        Measures.Component projectMeasures = Measures.Component.newBuilder()
                .setKey(project1.getKey()).setName(project1.getName())
                .addMeasures(measureInvalidBugs).build();
        Measures.ComponentWsResponse measuresResponse = Measures.ComponentWsResponse.newBuilder().setComponent(projectMeasures).build();
        List<String> expectedMetricKeys = Collections.singletonList(METRIC_BUGS.getKey());
        when(measuresService.component(componentRequestMatcher(project1.getKey(), expectedMetricKeys))).thenReturn(measuresResponse);

        // Define and get handler
        service.define(context);
        RequestHandler handler = handlerCaptor.getValue();

        // --- Act ---
        // Execute the handler, expecting it to log an error but not throw
        assertDoesNotThrow(() -> handler.handle(request, response));

        // --- Assert ---
        // Verify interactions happened (using service interface mock)
        verify(measuresService).component(componentRequestMatcher(project1.getKey(), expectedMetricKeys));

        // Verify the gauge for BUGS was registered
        assertEquals(1, Collections.list(CollectorRegistry.defaultRegistry.metricFamilySamples()).size(), "Bugs metric family should be registered"); // Convert Enumeration to List
        // Verify the gauge value was set to the default (0.0) due to parsing failure
        assertEquals(0.0, getGaugeValue(
                        "sonarqube_" + METRIC_BUGS.getKey(),
                        project1.getKey(),
                        project1.getName()),
                0.001, "Gauge value should be set to default (0.0) for non-numeric input");

        // Verify response was still sent successfully
        verify(responseStream).setStatus(200); // Verify on responseStream
        verify(responseStream).output(); // Verify on responseStream
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
        // Use accessors from the actual SearchRequest object if available and needed for complex matching
        // For now, assume direct field access or simple getters are sufficient for the matcher's lambda
        return argThat(req -> req != null &&
                req.getQualifiers().equals(Collections.singletonList(qualifier)) && // Assuming getQualifiers() exists
                req.getPs().equals(ps)
        );
    }

    /**
     * Creates an ArgumentMatcher for ComponentRequest.
     * Uses Sets for metricKeys to ignore order.
     */
    private ComponentRequest componentRequestMatcher(String componentKey, List<String> metricKeys) {
        Set<String> expectedKeys = new HashSet<>(metricKeys);
        // Use accessors from the actual ComponentRequest object if available and needed for complex matching
        // For now, assume direct field access or simple getters are sufficient for the matcher's lambda
        return argThat(req -> req != null &&
                req.getComponent().equals(componentKey) &&
                new HashSet<>(req.getMetricKeys()).equals(expectedKeys) // Assuming getMetricKeys() exists
        );
    }
}
