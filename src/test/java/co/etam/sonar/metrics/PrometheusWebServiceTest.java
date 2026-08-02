package co.etam.sonar.metrics;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Gauge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.sonar.api.config.Configuration;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.Metric;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.RequestHandler;
import org.sonar.api.server.ws.Response;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PrometheusWebServiceTest {

    @Mock
    Configuration configuration;

    AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        CollectorRegistry.defaultRegistry.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        CollectorRegistry.defaultRegistry.clear();
        mocks.close();
    }

    @Test
    void enabledMetric_registersGaugeWithSeverityLabel_andValueIsExported() throws Exception {
        when(configuration.getBoolean(PrometheusWebService.CONFIG_PREFIX + CoreMetrics.BUGS.getKey()))
                .thenReturn(Optional.of(true));

        PrometheusWebService service = new PrometheusWebService(configuration);

        callPrivate(service, "updateEnabledMetrics");
        callPrivate(service, "updateEnabledGauges");

        Field gaugesField = PrometheusWebService.class.getDeclaredField("gauges");
        gaugesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Gauge> gauges = (Map<String, Gauge>) gaugesField.get(service);

        assertNotNull(gauges, "gauges map should be present");
        assertTrue(gauges.containsKey(CoreMetrics.BUGS.getKey()), "BUGS gauge should be registered");

        Gauge bugsGauge = gauges.get(CoreMetrics.BUGS.getKey());
        assertNotNull(bugsGauge);

        String projectKey = "proj-1";
        String projectName = "Project One";
        String severity = "ALL";
        String branch = "main";
        bugsGauge.labels(projectKey, projectName, severity, branch).set(13.0);

        Double sample = CollectorRegistry.defaultRegistry.getSampleValue(
                "sonarqube_" + CoreMetrics.BUGS.getKey(),
                new String[]{"key", "name", "severity", "branch"},
                new String[]{projectKey, projectName, severity, branch}
        );

        assertNotNull(sample, "Exported sample should exist");
        assertEquals(13.0, sample, 0.0001);
    }

    @Test
    void determineSeverityFromMetricKey_detectsCommonSeverities() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);

        Method m = PrometheusWebService.class.getDeclaredMethod("determineSeverityFromMetricKey", String.class);
        m.setAccessible(true);

        assertEquals("BLOCKER", m.invoke(service, "blocker_violations"));
        assertEquals("CRITICAL", m.invoke(service, "critical_vulnerabilities"));
        assertEquals("MAJOR", m.invoke(service, "major_issues"));
        assertEquals("MINOR", m.invoke(service, "some_minor_metric"));
        assertEquals("INFO", m.invoke(service, "info_metric"));
        assertEquals("ALL", m.invoke(service, "vulnerabilities"));
        assertEquals("ALL", m.invoke(service, (String) null));
    }

    @Test
    void parseSeverityFilter_parsesCommaSeparatedAndUpperCases() throws Exception {
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
    void matchesSeverityFilter_filtersCorrectly() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);

        Method m = PrometheusWebService.class.getDeclaredMethod("matchesSeverityFilter", String.class, Set.class);
        m.setAccessible(true);

        Set<String> filter = Set.of("BLOCKER", "CRITICAL");
        assertTrue((Boolean) m.invoke(service, "BLOCKER", filter));
        assertTrue((Boolean) m.invoke(service, "CRITICAL", filter));
        assertFalse((Boolean) m.invoke(service, "MAJOR", filter));

        Set<String> emptyFilter = Set.of();
        assertTrue((Boolean) m.invoke(service, "MAJOR", emptyFilter));

        Set<String> wildcardFilter = Set.of("*");
        assertTrue((Boolean) m.invoke(service, "MAJOR", wildcardFilter));
    }

    @Test
    void parseDoubleOrDefault_returnsParsedDouble() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Method m = PrometheusWebService.class.getDeclaredMethod("parseDoubleOrDefault", String.class, double.class);
        m.setAccessible(true);
        
        assertEquals(42.5, m.invoke(service, "42.5", 0.0));
        assertEquals(0.0, m.invoke(service, "invalid", 0.0));
        assertEquals(0.0, m.invoke(service, null, 0.0));
    }

    @Test
    void mapAlertStatusToDouble_returnsCorrectValues() throws Exception {
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
    void sanitizeMetricName_handlesEdgeCases() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Method m = PrometheusWebService.class.getDeclaredMethod("sanitizeMetricName", String.class);
        m.setAccessible(true);

        assertEquals("sonarqube_unknown", m.invoke(service, (String) null));
        assertEquals("sonarqube_unknown", m.invoke(service, "   "));
        assertEquals("sonarqube_ncloc", m.invoke(service, "ncloc"));
        assertEquals("sonarqube_metric_key", m.invoke(service, "metric-key"));
        assertEquals("sonarqube_already_sanitized", m.invoke(service, "sonarqube_already_sanitized"));
    }

    @Test
    void getSafeHelp_handlesNullAndEmptyDescriptions() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Method m = PrometheusWebService.class.getDeclaredMethod("getSafeHelp", Metric.class);
        m.setAccessible(true);

        assertEquals("SonarQube Metric", m.invoke(service, (Metric<?>) null));

        Metric<?> metricWithDesc = new Metric.Builder("key1", "Name 1", Metric.ValueType.INT)
                .setDescription("Description 1").create();
        assertEquals("Description 1", m.invoke(service, metricWithDesc));

        Metric<?> metricWithNameOnly = new Metric.Builder("key2", "Name 2", Metric.ValueType.INT)
                .setDescription("").create();
        assertEquals("Name 2", m.invoke(service, metricWithNameOnly));
    }

    @Test
    void updateEnabledMetrics_respectsDisabledConfiguration() throws Exception {
        when(configuration.getBoolean(PrometheusWebService.CONFIG_PREFIX + CoreMetrics.NCLOC.getKey()))
                .thenReturn(Optional.of(false));

        PrometheusWebService service = new PrometheusWebService(configuration);
        callPrivate(service, "updateEnabledMetrics");

        Field enabledMetricsField = PrometheusWebService.class.getDeclaredField("enabledMetrics");
        enabledMetricsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<Metric<?>> enabledMetrics = (Set<Metric<?>>) enabledMetricsField.get(service);

        assertFalse(enabledMetrics.contains(CoreMetrics.NCLOC));
    }

    @Test
    void define_setsUpControllerAndAction_andExecutesHandler() throws Exception {
        org.sonar.api.server.ws.WebService.Context context = mock(org.sonar.api.server.ws.WebService.Context.class);
        org.sonar.api.server.ws.WebService.NewController controller = mock(org.sonar.api.server.ws.WebService.NewController.class);
        org.sonar.api.server.ws.WebService.NewAction action = mock(org.sonar.api.server.ws.WebService.NewAction.class);
        org.sonar.api.server.ws.WebService.NewParam param = mock(org.sonar.api.server.ws.WebService.NewParam.class);

        when(context.createController(anyString())).thenReturn(controller);
        when(controller.createAction(anyString())).thenReturn(action);
        when(action.createParam(anyString())).thenReturn(param);
        when(param.setDescription(anyString())).thenReturn(param);
        when(param.setRequired(anyBoolean())).thenReturn(param);

        PrometheusWebService service = spy(new PrometheusWebService(configuration));
        service.define(context);

        ArgumentCaptor<RequestHandler> handlerCaptor = ArgumentCaptor.forClass(RequestHandler.class);
        verify(action).setHandler(handlerCaptor.capture());
        RequestHandler handler = handlerCaptor.getValue();
        assertNotNull(handler);

        Request request = mock(Request.class);
        Response response = mock(Response.class);
        Response.Stream stream = mock(Response.Stream.class);
        
        when(response.stream()).thenReturn(stream);
        when(stream.setMediaType(anyString())).thenReturn(stream);
        when(stream.setStatus(anyInt())).thenReturn(stream);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when(stream.output()).thenReturn(baos);
        
        when(request.param("severity")).thenReturn("ALL");

        when(configuration.getBoolean(PrometheusWebService.CONFIG_PREFIX + CoreMetrics.BUGS.getKey())).thenReturn(Optional.of(true));

        org.sonarqube.ws.client.WsClient wsClient = mock(org.sonarqube.ws.client.WsClient.class);
        doReturn(wsClient).when(service).createWsClient(any());
        
        org.sonarqube.ws.client.components.ComponentsService compService = mock(org.sonarqube.ws.client.components.ComponentsService.class);
        when(wsClient.components()).thenReturn(compService);
        
        org.sonarqube.ws.Components.Component project = org.sonarqube.ws.Components.Component.newBuilder().setKey("proj1").setName("Project 1").build();
        org.sonarqube.ws.Components.SearchWsResponse searchResponse = org.sonarqube.ws.Components.SearchWsResponse.newBuilder().addComponents(project).build();
        when(compService.search(any())).thenReturn(searchResponse);

        org.sonarqube.ws.client.projectbranches.ProjectBranchesService pbService = mock(org.sonarqube.ws.client.projectbranches.ProjectBranchesService.class);
        when(wsClient.projectBranches()).thenReturn(pbService);
        org.sonarqube.ws.ProjectBranches.Branch branch = org.sonarqube.ws.ProjectBranches.Branch.newBuilder().setName("main").build();
        org.sonarqube.ws.ProjectBranches.ListWsResponse pbResponse = org.sonarqube.ws.ProjectBranches.ListWsResponse.newBuilder().addBranches(branch).build();
        when(pbService.list(any())).thenReturn(pbResponse);

        org.sonarqube.ws.client.measures.MeasuresService measuresService = mock(org.sonarqube.ws.client.measures.MeasuresService.class);
        when(wsClient.measures()).thenReturn(measuresService);
        org.sonarqube.ws.Measures.Measure measure1 = org.sonarqube.ws.Measures.Measure.newBuilder().setMetric("bugs").setValue("5").build();
        org.sonarqube.ws.Measures.Measure measure2 = org.sonarqube.ws.Measures.Measure.newBuilder().setMetric(CoreMetrics.ALERT_STATUS.key()).setValue("OK").build();
        org.sonarqube.ws.Measures.Measure measure3 = org.sonarqube.ws.Measures.Measure.newBuilder().setMetric("unknown_metric_key").setValue("42.5").build();
        org.sonarqube.ws.Measures.ComponentWsResponse measuresResponse = org.sonarqube.ws.Measures.ComponentWsResponse.newBuilder()
                .setComponent(org.sonarqube.ws.Measures.Component.newBuilder()
                        .addMeasures(measure1)
                        .addMeasures(measure2)
                        .addMeasures(measure3)
                        .build())
                .build();
        when(measuresService.component(any())).thenReturn(measuresResponse);
        
        handler.handle(request, response);
        
        assertTrue(baos.toString().contains("sonarqube_exporter_up 1.0"));
    }

    @Test
    void define_whenWsClientFails_logsWarningAndReturnsExporterUpStatus() throws Exception {
        org.sonar.api.server.ws.WebService.Context context = mock(org.sonar.api.server.ws.WebService.Context.class);
        org.sonar.api.server.ws.WebService.NewController controller = mock(org.sonar.api.server.ws.WebService.NewController.class);
        org.sonar.api.server.ws.WebService.NewAction action = mock(org.sonar.api.server.ws.WebService.NewAction.class);
        org.sonar.api.server.ws.WebService.NewParam param = mock(org.sonar.api.server.ws.WebService.NewParam.class);

        when(context.createController(anyString())).thenReturn(controller);
        when(controller.createAction(anyString())).thenReturn(action);
        when(action.createParam(anyString())).thenReturn(param);
        when(param.setDescription(anyString())).thenReturn(param);
        when(param.setRequired(anyBoolean())).thenReturn(param);

        PrometheusWebService service = spy(new PrometheusWebService(configuration));
        service.define(context);

        ArgumentCaptor<RequestHandler> handlerCaptor = ArgumentCaptor.forClass(RequestHandler.class);
        verify(action).setHandler(handlerCaptor.capture());
        RequestHandler handler = handlerCaptor.getValue();

        Request request = mock(Request.class);
        Response response = mock(Response.class);
        Response.Stream stream = mock(Response.Stream.class);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        when(response.stream()).thenReturn(stream);
        when(stream.setMediaType(anyString())).thenReturn(stream);
        when(stream.setStatus(anyInt())).thenReturn(stream);
        when(stream.output()).thenReturn(baos);

        when(request.param("severity")).thenReturn(null);
        when(configuration.get(PrometheusWebService.CONFIG_PREFIX + "severity")).thenReturn(Optional.of("MAJOR,CRITICAL"));

        doThrow(new RuntimeException("Simulated WsClient error")).when(service).createWsClient(any());

        handler.handle(request, response);

        assertTrue(baos.toString().contains("sonarqube_exporter_up 1.0"));
    }

    @Test
    void define_whenExceptionInResponseStream_writesFallbackRegistryWithExporterUpZero() throws Exception {
        org.sonar.api.server.ws.WebService.Context context = mock(org.sonar.api.server.ws.WebService.Context.class);
        org.sonar.api.server.ws.WebService.NewController controller = mock(org.sonar.api.server.ws.WebService.NewController.class);
        org.sonar.api.server.ws.WebService.NewAction action = mock(org.sonar.api.server.ws.WebService.NewAction.class);
        org.sonar.api.server.ws.WebService.NewParam param = mock(org.sonar.api.server.ws.WebService.NewParam.class);

        when(context.createController(anyString())).thenReturn(controller);
        when(controller.createAction(anyString())).thenReturn(action);
        when(action.createParam(anyString())).thenReturn(param);
        when(param.setDescription(anyString())).thenReturn(param);
        when(param.setRequired(anyBoolean())).thenReturn(param);

        PrometheusWebService service = new PrometheusWebService(configuration);
        service.define(context);

        ArgumentCaptor<RequestHandler> handlerCaptor = ArgumentCaptor.forClass(RequestHandler.class);
        verify(action).setHandler(handlerCaptor.capture());
        RequestHandler handler = handlerCaptor.getValue();

        Request request = mock(Request.class);
        Response response = mock(Response.class);
        Response.Stream stream = mock(Response.Stream.class);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when(response.stream()).thenReturn(stream);
        when(stream.setMediaType(anyString())).thenReturn(stream);
        when(stream.setStatus(anyInt())).thenReturn(stream);
        
        when(stream.output()).thenThrow(new RuntimeException("Primary stream failure")).thenReturn(baos);

        handler.handle(request, response);

        assertTrue(baos.toString().contains("sonarqube_exporter_up 0.0"));
    }

    @Test
    void define_whenFallbackStreamAlsoThrowsException_swallowsException() throws Exception {
        org.sonar.api.server.ws.WebService.Context context = mock(org.sonar.api.server.ws.WebService.Context.class);
        org.sonar.api.server.ws.WebService.NewController controller = mock(org.sonar.api.server.ws.WebService.NewController.class);
        org.sonar.api.server.ws.WebService.NewAction action = mock(org.sonar.api.server.ws.WebService.NewAction.class);
        org.sonar.api.server.ws.WebService.NewParam param = mock(org.sonar.api.server.ws.WebService.NewParam.class);

        when(context.createController(anyString())).thenReturn(controller);
        when(controller.createAction(anyString())).thenReturn(action);
        when(action.createParam(anyString())).thenReturn(param);
        when(param.setDescription(anyString())).thenReturn(param);
        when(param.setRequired(anyBoolean())).thenReturn(param);

        PrometheusWebService service = new PrometheusWebService(configuration);
        service.define(context);

        ArgumentCaptor<RequestHandler> handlerCaptor = ArgumentCaptor.forClass(RequestHandler.class);
        verify(action).setHandler(handlerCaptor.capture());
        RequestHandler handler = handlerCaptor.getValue();

        Request request = mock(Request.class);
        Response response = mock(Response.class);

        when(response.stream()).thenThrow(new RuntimeException("Total failure"));

        assertDoesNotThrow(() -> handler.handle(request, response));
    }

    @Test
    void getBranches_whenListThrowsException_returnsDefaultMainList() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Method getBranchesMethod = PrometheusWebService.class.getDeclaredMethod("getBranches", org.sonarqube.ws.client.WsClient.class, String.class);
        getBranchesMethod.setAccessible(true);

        org.sonarqube.ws.client.WsClient wsClient = mock(org.sonarqube.ws.client.WsClient.class);
        when(wsClient.projectBranches()).thenThrow(new RuntimeException("Branches error"));

        @SuppressWarnings("unchecked")
        java.util.List<String> branches = (java.util.List<String>) getBranchesMethod.invoke(service, wsClient, "proj-key");

        assertEquals(1, branches.size());
        assertEquals("main", branches.get(0));
    }

    private void callPrivate(Object target, String methodName) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method m = target.getClass().getDeclaredMethod(methodName);
        m.setAccessible(true);
        m.invoke(target);
    }
}
