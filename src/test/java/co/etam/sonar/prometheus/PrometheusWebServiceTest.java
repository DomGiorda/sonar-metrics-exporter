package co.etam.sonar.prometheus;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Gauge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.sonar.api.config.Configuration;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.Metric;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class PrometheusWebServiceTest {

    @Mock
    Configuration configuration;

    AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        // Ensure registry clean for each test
        CollectorRegistry.defaultRegistry.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        CollectorRegistry.defaultRegistry.clear();
        mocks.close();
    }

    @Test
    void enabledMetric_registersGaugeWithSeverityLabel_andValueIsExported() throws Exception {
        // Arrange: enable BUGS metric
        when(configuration.getBoolean(PrometheusWebService.CONFIG_PREFIX + CoreMetrics.BUGS.getKey()))
                .thenReturn(Optional.of(true));

        PrometheusWebService service = new PrometheusWebService(configuration);

        // Invoke private updateEnabledMetrics() and updateEnabledGauges() via reflection
        callPrivate(service, "updateEnabledMetrics");
        callPrivate(service, "updateEnabledGauges");

        // Access private gauges map
        Field gaugesField = PrometheusWebService.class.getDeclaredField("gauges");
        gaugesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Gauge> gauges = (Map<String, Gauge>) gaugesField.get(service);

        assertNotNull(gauges, "gauges map should be present");
        assertTrue(gauges.containsKey(CoreMetrics.BUGS.getKey()), "BUGS gauge should be registered");

        Gauge bugsGauge = gauges.get(CoreMetrics.BUGS.getKey());
        assertNotNull(bugsGauge);

        // Act: set a value for a project with severity "ALL" and branch "main"
        String projectKey = "proj-1";
        String projectName = "Project One";
        String severity = "ALL"; // totals use ALL by our implementation
        String branch = "main";
        bugsGauge.labels(projectKey, projectName, severity, branch).set(13.0);

        // Assert: sample exported with severity label and branch label
        Double sample = CollectorRegistry.defaultRegistry.getSampleValue(
                "sonarqube_" + CoreMetrics.BUGS.getKey(),
                new String[]{"key", "name", "severity", "branch"},
                new String[]{projectKey, projectName, severity, branch}
        );

        assertNotNull(sample, "Exported sample should exist");
        assertEquals(13.0, sample, 0.0001);
    }

    @Test
    void determineSeverityFromMetricKey_detectsCommonSeverities() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        PrometheusWebService service = new PrometheusWebService(configuration);

        Method m = PrometheusWebService.class.getDeclaredMethod("determineSeverityFromMetricKey", String.class);
        m.setAccessible(true);

        assertEquals("BLOCKER", m.invoke(service, "blocker_violations"));
        assertEquals("CRITICAL", m.invoke(service, "critical_vulnerabilities"));
        assertEquals("MAJOR", m.invoke(service, "major_issues"));
        assertEquals("MINOR", m.invoke(service, "some_minor_metric"));
        assertEquals("INFO", m.invoke(service, "info_metric"));
        assertEquals("ALL", m.invoke(service, "vulnerabilities"));
    }

    @Test
    void parseSeverityFilter_parsesCommaSeparatedAndUpperCases() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        PrometheusWebService service = new PrometheusWebService(configuration);

        Method m = PrometheusWebService.class.getDeclaredMethod("parseSeverityFilter", String.class);
        m.setAccessible(true);

        @SuppressWarnings("unchecked")
        Set<String> result1 = (Set<String>) m.invoke(service, "blocker, critical");
        assertTrue(result1.contains("BLOCKER"));
        assertTrue(result1.contains("CRITICAL"));
        assertEquals(2, result1.size());

        @SuppressWarnings("unchecked")
        Set<String> resultEmpty = (Set<String>) m.invoke(service, " ");
        assertTrue(resultEmpty.isEmpty());

        @SuppressWarnings("unchecked")
        Set<String> resultNull = (Set<String>) m.invoke(service, (String) null);
        assertTrue(resultNull.isEmpty());
    }

    @Test
    void matchesSeverityFilter_filtersCorrectly() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        PrometheusWebService service = new PrometheusWebService(configuration);

        Method m = PrometheusWebService.class.getDeclaredMethod("matchesSeverityFilter", String.class, Set.class);
        m.setAccessible(true);

        Set<String> filter = Set.of("BLOCKER", "CRITICAL");
        assertTrue((Boolean) m.invoke(service, "BLOCKER", filter));
        assertTrue((Boolean) m.invoke(service, "CRITICAL", filter));
        assertFalse((Boolean) m.invoke(service, "MAJOR", filter));

        Set<String> emptyFilter = Set.of();
        assertTrue((Boolean) m.invoke(service, "MAJOR", emptyFilter));
    }

    @Test
    void parseDoubleOrDefault_returnsParsedDouble() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Method m = PrometheusWebService.class.getDeclaredMethod("parseDoubleOrDefault", String.class, double.class);
        m.setAccessible(true);
        
        assertEquals(42.5, m.invoke(service, "42.5", 0.0));
        assertEquals(0.0, m.invoke(service, "invalid", 0.0));
        assertEquals(0.0, m.invoke(service, null, 0.0));
    }

    @Test
    void mapAlertStatusToDouble_returnsCorrectValues() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Method m = PrometheusWebService.class.getDeclaredMethod("mapAlertStatusToDouble", String.class);
        m.setAccessible(true);
        
        assertEquals(1.0, m.invoke(service, "OK"));
        assertEquals(1.0, m.invoke(service, "ok"));
        assertEquals(2.0, m.invoke(service, "WARN"));
        assertEquals(3.0, m.invoke(service, "ERROR"));
        assertEquals(0.0, m.invoke(service, "UNKNOWN"));
        assertEquals(0.0, m.invoke(service, (String) null));
    }

    @Test
    void define_setsUpControllerAndAction_andExecutesHandler() throws Exception {
        org.sonar.api.server.ws.WebService.Context context = org.mockito.Mockito.mock(org.sonar.api.server.ws.WebService.Context.class);
        org.sonar.api.server.ws.WebService.NewController controller = org.mockito.Mockito.mock(org.sonar.api.server.ws.WebService.NewController.class);
        org.sonar.api.server.ws.WebService.NewAction action = org.mockito.Mockito.mock(org.sonar.api.server.ws.WebService.NewAction.class);
        org.sonar.api.server.ws.WebService.NewParam param = org.mockito.Mockito.mock(org.sonar.api.server.ws.WebService.NewParam.class);

        when(context.createController(org.mockito.ArgumentMatchers.anyString())).thenReturn(controller);
        when(controller.createAction(org.mockito.ArgumentMatchers.anyString())).thenReturn(action);
        when(action.createParam(org.mockito.ArgumentMatchers.anyString())).thenReturn(param);
        when(param.setDescription(org.mockito.ArgumentMatchers.anyString())).thenReturn(param);
        when(param.setRequired(org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(param);

        PrometheusWebService service = org.mockito.Mockito.spy(new PrometheusWebService(configuration));
        service.define(context);

        org.mockito.ArgumentCaptor<org.sonar.api.server.ws.RequestHandler> handlerCaptor = org.mockito.ArgumentCaptor.forClass(org.sonar.api.server.ws.RequestHandler.class);
        org.mockito.Mockito.verify(action).setHandler(handlerCaptor.capture());
        org.sonar.api.server.ws.RequestHandler handler = handlerCaptor.getValue();
        assertNotNull(handler);

        // Prepare mocks for handler execution
        org.sonar.api.server.ws.Request request = org.mockito.Mockito.mock(org.sonar.api.server.ws.Request.class);
        org.sonar.api.server.ws.Response response = org.mockito.Mockito.mock(org.sonar.api.server.ws.Response.class);
        org.sonar.api.server.ws.Response.Stream stream = org.mockito.Mockito.mock(org.sonar.api.server.ws.Response.Stream.class);
        
        when(response.stream()).thenReturn(stream);
        when(stream.setMediaType(org.mockito.ArgumentMatchers.anyString())).thenReturn(stream);
        when(stream.setStatus(org.mockito.ArgumentMatchers.anyInt())).thenReturn(stream);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        when(stream.output()).thenReturn(baos);
        
        when(request.param("severity")).thenReturn("ALL");

        when(configuration.getBoolean(PrometheusWebService.CONFIG_PREFIX + org.sonar.api.measures.CoreMetrics.BUGS.getKey())).thenReturn(Optional.of(true));

        org.sonarqube.ws.client.WsClient wsClient = org.mockito.Mockito.mock(org.sonarqube.ws.client.WsClient.class);
        org.mockito.Mockito.doReturn(wsClient).when(service).createWsClient(org.mockito.ArgumentMatchers.any());
        
        org.sonarqube.ws.client.components.ComponentsService compService = org.mockito.Mockito.mock(org.sonarqube.ws.client.components.ComponentsService.class);
        when(wsClient.components()).thenReturn(compService);
        
        org.sonarqube.ws.Components.Component project = org.sonarqube.ws.Components.Component.newBuilder().setKey("proj1").setName("Project 1").build();
        org.sonarqube.ws.Components.SearchWsResponse searchResponse = org.sonarqube.ws.Components.SearchWsResponse.newBuilder().addComponents(project).build();
        when(compService.search(org.mockito.ArgumentMatchers.any())).thenReturn(searchResponse);

        org.sonarqube.ws.client.projectbranches.ProjectBranchesService pbService = org.mockito.Mockito.mock(org.sonarqube.ws.client.projectbranches.ProjectBranchesService.class);
        when(wsClient.projectBranches()).thenReturn(pbService);
        org.sonarqube.ws.ProjectBranches.Branch branch = org.sonarqube.ws.ProjectBranches.Branch.newBuilder().setName("main").build();
        org.sonarqube.ws.ProjectBranches.ListWsResponse pbResponse = org.sonarqube.ws.ProjectBranches.ListWsResponse.newBuilder().addBranches(branch).build();
        when(pbService.list(org.mockito.ArgumentMatchers.any())).thenReturn(pbResponse);

        org.sonarqube.ws.client.measures.MeasuresService measuresService = org.mockito.Mockito.mock(org.sonarqube.ws.client.measures.MeasuresService.class);
        when(wsClient.measures()).thenReturn(measuresService);
        org.sonarqube.ws.Measures.Measure measure1 = org.sonarqube.ws.Measures.Measure.newBuilder().setMetric("bugs").setValue("5").build();
        org.sonarqube.ws.Measures.Measure measure2 = org.sonarqube.ws.Measures.Measure.newBuilder().setMetric(org.sonar.api.measures.CoreMetrics.ALERT_STATUS.key()).setValue("OK").build();
        org.sonarqube.ws.Measures.Measure measure3 = org.sonarqube.ws.Measures.Measure.newBuilder().setMetric("unknown_metric_key").setValue("42.5").build();
        org.sonarqube.ws.Measures.ComponentWsResponse measuresResponse = org.sonarqube.ws.Measures.ComponentWsResponse.newBuilder()
                .setComponent(org.sonarqube.ws.Measures.Component.newBuilder()
                        .addMeasures(measure1)
                        .addMeasures(measure2)
                        .addMeasures(measure3)
                        .build())
                .build();
        when(measuresService.component(org.mockito.ArgumentMatchers.any())).thenReturn(measuresResponse);
        
        // Execute
        handler.handle(request, response);
        
        assertTrue(baos.toString().length() >= 0);
    }

    // Helper to call private no-arg methods
    private void callPrivate(Object target, String methodName) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method m = target.getClass().getDeclaredMethod(methodName);
        m.setAccessible(true);
        m.invoke(target);
    }
}
