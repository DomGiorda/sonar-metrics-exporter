package co.etam.sonar.metrics;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Gauge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.sonar.api.config.Configuration;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.Metric;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.RequestHandler;
import org.sonar.api.server.ws.Response;
import org.sonarqube.ws.Components.Component;
import org.sonarqube.ws.Measures.ComponentWsResponse;
import org.sonarqube.ws.Measures.Measure;
import org.sonarqube.ws.ProjectBranches.ListWsResponse;

import org.sonarqube.ws.client.WsClient;
import org.sonarqube.ws.client.components.ComponentsService;
import org.sonarqube.ws.client.measures.ComponentRequest;
import org.sonarqube.ws.client.measures.MeasuresService;
import org.sonarqube.ws.client.projectbranches.ProjectBranchesService;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
    void matchesSeverityFilter_coversAllFilterConditions() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);

        Method m = PrometheusWebService.class.getDeclaredMethod("matchesSeverityFilter", String.class, Set.class);
        m.setAccessible(true);

        assertTrue((Boolean) m.invoke(service, "BLOCKER", Set.of()));
        assertTrue((Boolean) m.invoke(service, "BLOCKER", Set.of("ALL_SEVERITIES")));
        assertTrue((Boolean) m.invoke(service, "BLOCKER", Set.of("*")));
        assertTrue((Boolean) m.invoke(service, "BLOCKER", Set.of("BLOCKER", "CRITICAL")));
        assertFalse((Boolean) m.invoke(service, "MAJOR", Set.of("BLOCKER", "CRITICAL")));
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
    void getSafeHelp_coversAllBranches() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Method m = PrometheusWebService.class.getDeclaredMethod("getSafeHelp", Metric.class);
        m.setAccessible(true);

        Metric<?> m1 = new Metric.Builder("k1", "N1", Metric.ValueType.INT).setDescription("Desc1").create();
        assertEquals("Desc1", m.invoke(service, m1));

        Metric<?> m2 = new Metric.Builder("k2", "N2", Metric.ValueType.INT).setDescription("   ").create();
        assertEquals("N2", m.invoke(service, m2));

        Metric<?> m3 = new Metric.Builder("k3", "N3", Metric.ValueType.INT).setDescription("").create();
        assertEquals("N3", m.invoke(service, m3));

        assertEquals("SonarQube Metric", m.invoke(service, (Metric<?>) null));
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

        WsClient wsClient = mock(WsClient.class);
        doReturn(wsClient).when(service).createWsClient(any());
        
        ComponentsService compService = mock(ComponentsService.class);
        when(wsClient.components()).thenReturn(compService);
        
        Component project = Component.newBuilder().setKey("proj1").setName("Project 1").build();
        org.sonarqube.ws.Components.SearchWsResponse searchResponse = org.sonarqube.ws.Components.SearchWsResponse.newBuilder().addComponents(project).build();
        when(compService.search(any())).thenReturn(searchResponse);

        ProjectBranchesService pbService = mock(ProjectBranchesService.class);
        when(wsClient.projectBranches()).thenReturn(pbService);
        org.sonarqube.ws.ProjectBranches.Branch branch = org.sonarqube.ws.ProjectBranches.Branch.newBuilder().setName("main").build();
        ListWsResponse pbResponse = ListWsResponse.newBuilder().addBranches(branch).build();
        when(pbService.list(any())).thenReturn(pbResponse);

        MeasuresService measuresService = mock(MeasuresService.class);
        when(wsClient.measures()).thenReturn(measuresService);
        Measure measure1 = Measure.newBuilder().setMetric("bugs").setValue("5").build();
        Measure measure2 = Measure.newBuilder().setMetric(CoreMetrics.ALERT_STATUS.key()).setValue("OK").build();
        Measure measure3 = Measure.newBuilder().setMetric("unknown_metric_key").setValue("42.5").build();
        ComponentWsResponse measuresResponse = ComponentWsResponse.newBuilder()
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
    void define_whenSeverityParamIsBlank_usesSeverityFromConfiguration() throws Exception {
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

        when(request.param("severity")).thenReturn("   ");
        when(configuration.get(PrometheusWebService.CONFIG_PREFIX + "severity")).thenReturn(Optional.of("CRITICAL"));

        WsClient wsClient = mock(WsClient.class);
        doReturn(wsClient).when(service).createWsClient(any());

        ComponentsService compService = mock(ComponentsService.class);
        when(wsClient.components()).thenReturn(compService);
        when(compService.search(any())).thenReturn(org.sonarqube.ws.Components.SearchWsResponse.newBuilder().build());

        handler.handle(request, response);

        assertTrue(baos.toString().contains("sonarqube_exporter_up 1.0"));
    }

    @Test
    void define_whenEnabledMetricsIsEmpty_skipsProcessingProjects() throws Exception {
        org.sonar.api.server.ws.WebService.Context context = mock(org.sonar.api.server.ws.WebService.Context.class);
        org.sonar.api.server.ws.WebService.NewController controller = mock(org.sonar.api.server.ws.WebService.NewController.class);
        org.sonar.api.server.ws.WebService.NewAction action = mock(org.sonar.api.server.ws.WebService.NewAction.class);
        org.sonar.api.server.ws.WebService.NewParam param = mock(org.sonar.api.server.ws.WebService.NewParam.class);

        when(context.createController(anyString())).thenReturn(controller);
        when(controller.createAction(anyString())).thenReturn(action);
        when(action.createParam(anyString())).thenReturn(param);
        when(param.setDescription(anyString())).thenReturn(param);
        when(param.setRequired(anyBoolean())).thenReturn(param);

        when(configuration.getBoolean(anyString())).thenReturn(Optional.of(false));

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

        handler.handle(request, response);

        verify(service, never()).createWsClient(any());
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
    void registerGaugesForRegistry_whenRegistrationFails_handlesExceptionGracefully() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);

        Field enabledMetricsField = PrometheusWebService.class.getDeclaredField("enabledMetrics");
        enabledMetricsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<Metric<?>> enabledMetrics = (Set<Metric<?>>) enabledMetricsField.get(service);
        enabledMetrics.add(CoreMetrics.BUGS);

        Method method = PrometheusWebService.class.getDeclaredMethod("registerGaugesForRegistry", CollectorRegistry.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Gauge> gaugesMap = (Map<String, Gauge>) method.invoke(service, (CollectorRegistry) null);

        assertNotNull(gaugesMap);
        assertTrue(gaugesMap.isEmpty());
    }

    @Test
    void processAllProjects_whenSearchReturnsNullComponents_doesNotThrow() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);

        WsClient wsClient = mock(WsClient.class);
        ComponentsService compService = mock(ComponentsService.class);
        when(wsClient.components()).thenReturn(compService);

        Method method = PrometheusWebService.class.getDeclaredMethod("processAllProjects", WsClient.class, Set.class, CollectorRegistry.class, Map.class);
        method.setAccessible(true);

        // Search returns null components list via real builder
        when(compService.search(any())).thenReturn(org.sonarqube.ws.Components.SearchWsResponse.newBuilder().build());
        assertDoesNotThrow(() -> method.invoke(service, wsClient, Set.of("ALL"), new CollectorRegistry(), new HashMap<>()));
    }

    @Test
    void processAllProjects_whenGetProjectsThrowsException_logsWarningAndDoesNotThrow() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);

        WsClient wsClient = mock(WsClient.class);
        when(wsClient.components()).thenThrow(new RuntimeException("Search failed"));

        Method method = PrometheusWebService.class.getDeclaredMethod("processAllProjects", WsClient.class, Set.class, CollectorRegistry.class, Map.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(service, wsClient, Set.of("ALL"), new CollectorRegistry(), new HashMap<>()));
    }

    @Test
    void processProjectBranches_whenProjectIsNull_returnsEarly() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);

        WsClient wsClient = mock(WsClient.class);
        Method method = PrometheusWebService.class.getDeclaredMethod("processProjectBranches", WsClient.class, Component.class, Set.class, CollectorRegistry.class, Map.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(service, wsClient, null, Set.of("ALL"), new CollectorRegistry(), new HashMap<>()));
        verifyNoInteractions(wsClient);
    }

    @Test
    void processProjectBranches_whenProjectKeyIsEmpty_returnsEarly() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);

        WsClient wsClient = mock(WsClient.class);
        Component emptyKeyProject = Component.newBuilder().setKey("").build();

        Method method = PrometheusWebService.class.getDeclaredMethod("processProjectBranches", WsClient.class, Component.class, Set.class, CollectorRegistry.class, Map.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(service, wsClient, emptyKeyProject, Set.of("ALL"), new CollectorRegistry(), new HashMap<>()));
        verifyNoInteractions(wsClient);
    }

    @Test
    void processProjectBranches_whenExceptionInGetBranches_catchesExceptionAndLogsWarning() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);

        WsClient wsClient = mock(WsClient.class);
        ProjectBranchesService pbService = mock(ProjectBranchesService.class);
        when(wsClient.projectBranches()).thenReturn(pbService);
        when(pbService.list(any())).thenThrow(new RuntimeException("Simulated branch fetch error"));

        Component project = Component.newBuilder().setKey("proj1").setName("Project 1").build();

        Method method = PrometheusWebService.class.getDeclaredMethod("processProjectBranches", WsClient.class, Component.class, Set.class, CollectorRegistry.class, Map.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(service, wsClient, project, Set.of("ALL"), new CollectorRegistry(), new HashMap<>()));
    }

    @Test
    void processMeasuresForBranch_coversAllResponseConditions() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);

        WsClient wsClient = mock(WsClient.class);
        MeasuresService measuresService = mock(MeasuresService.class);
        when(wsClient.measures()).thenReturn(measuresService);

        Component project = Component.newBuilder().setKey("proj1").setName("Project 1").build();

        Method method = PrometheusWebService.class.getDeclaredMethod("processMeasuresForBranch", WsClient.class, Component.class, String.class, Set.class, CollectorRegistry.class, Map.class);
        method.setAccessible(true);

        // Condition A: wsResponse is null
        when(measuresService.component(any())).thenReturn(null);
        assertDoesNotThrow(() -> method.invoke(service, wsClient, project, "main", Set.of("ALL"), new CollectorRegistry(), new HashMap<>()));

        // Condition B: wsResponse has no component
        ComponentWsResponse responseNoComp = ComponentWsResponse.newBuilder().build();
        when(measuresService.component(any())).thenReturn(responseNoComp);
        assertDoesNotThrow(() -> method.invoke(service, wsClient, project, "main", Set.of("ALL"), new CollectorRegistry(), new HashMap<>()));

        // Condition C: wsResponse has component with valid measures
        Measure measure = Measure.newBuilder().setMetric("bugs").setValue("2").build();
        ComponentWsResponse responseWithComp = ComponentWsResponse.newBuilder()
                .setComponent(org.sonarqube.ws.Measures.Component.newBuilder().addMeasures(measure).build())
                .build();
        when(measuresService.component(any())).thenReturn(responseWithComp);
        assertDoesNotThrow(() -> method.invoke(service, wsClient, project, "main", Set.of("ALL"), new CollectorRegistry(), new HashMap<>()));
    }

    @Test
    void processMeasuresForBranch_whenGetMeasuresThrowsException_logsWarningAndDoesNotThrow() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);

        WsClient wsClient = mock(WsClient.class);
        MeasuresService measuresService = mock(MeasuresService.class);
        when(wsClient.measures()).thenReturn(measuresService);
        when(measuresService.component(any())).thenThrow(new RuntimeException("Measures service error"));

        Component project = Component.newBuilder().setKey("proj1").setName("Project 1").build();

        Method method = PrometheusWebService.class.getDeclaredMethod("processMeasuresForBranch", WsClient.class, Component.class, String.class, Set.class, CollectorRegistry.class, Map.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(service, wsClient, project, "main", Set.of("ALL"), new CollectorRegistry(), new HashMap<>()));
    }

    @Test
    void processSingleMeasure_whenMeasureIsNull_returnsEarly() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);

        Component project = Component.newBuilder().setKey("proj1").setName("Project 1").build();
        CollectorRegistry registry = new CollectorRegistry();
        Map<String, Gauge> gaugesMap = new HashMap<>();

        Method method = PrometheusWebService.class.getDeclaredMethod("processSingleMeasure", Component.class, String.class, Set.class, Measure.class, CollectorRegistry.class, Map.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(service, project, "main", Set.of("ALL"), null, registry, gaugesMap));
        assertTrue(gaugesMap.isEmpty());
    }

    @Test
    void processSingleMeasure_whenSeverityFilterDoesNotMatch_returnsEarly() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);

        Component project = Component.newBuilder().setKey("proj1").setName("Project 1").build();
        CollectorRegistry registry = new CollectorRegistry();
        Map<String, Gauge> gaugesMap = new HashMap<>();

        Measure minorMeasure = Measure.newBuilder().setMetric("minor_violations").setValue("5").build();

        Method method = PrometheusWebService.class.getDeclaredMethod("processSingleMeasure", Component.class, String.class, Set.class, Measure.class, CollectorRegistry.class, Map.class);
        method.setAccessible(true);

        method.invoke(service, project, "main", Set.of("BLOCKER"), minorMeasure, registry, gaugesMap);

        assertTrue(gaugesMap.isEmpty());
    }

    @Test
    void getOrCreateGauge_whenRegistrationThrowsException_returnsNull() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);

        Method method = PrometheusWebService.class.getDeclaredMethod("getOrCreateGauge", String.class, CollectorRegistry.class, Map.class);
        method.setAccessible(true);

        Gauge gauge = (Gauge) method.invoke(service, "bugs", (CollectorRegistry) null, new HashMap<>());
        assertNull(gauge);
    }

    @Test
    void setGaugeValue_whenGaugeIsNull_doesNothing() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);

        Component project = Component.newBuilder().setKey("proj1").setName("Project 1").build();

        Method method = PrometheusWebService.class.getDeclaredMethod("setGaugeValue", Gauge.class, Component.class, String.class, String.class, double.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(service, null, project, "ALL", "main", 5.0));
    }

    @Test
    void setGaugeValue_whenProjectIsNull_usesFallbackDefaults() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);

        CollectorRegistry registry = new CollectorRegistry();
        Gauge gauge = Gauge.build()
                .name("sonarqube_test_null_proj")
                .help("Test metric")
                .labelNames("key", "name", "severity", "branch")
                .register(registry);

        Method method = PrometheusWebService.class.getDeclaredMethod("setGaugeValue", Gauge.class, Component.class, String.class, String.class, double.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(service, gauge, null, "ALL", "main", 10.0));

        Double sample = registry.getSampleValue(
                "sonarqube_test_null_proj",
                new String[]{"key", "name", "severity", "branch"},
                new String[]{"", "", "ALL", "main"}
        );
        assertNotNull(sample);
        assertEquals(10.0, sample, 0.0001);
    }

    @Test
    void setGaugeValue_whenGaugeThrowsExceptionAndProjectIsNull_logsDebugWithNullProjectKey() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);

        Gauge mockGauge = mock(Gauge.class);
        when(mockGauge.labels(anyString(), anyString(), anyString(), anyString())).thenThrow(new RuntimeException("Labels error"));

        Method method = PrometheusWebService.class.getDeclaredMethod("setGaugeValue", Gauge.class, Component.class, String.class, String.class, double.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(service, mockGauge, null, "ALL", "main", 5.0));
    }

    @Test
    void setGaugeValue_whenGaugeThrowsExceptionAndProjectIsNotNull_logsDebugWithProjectKey() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);

        Gauge mockGauge = mock(Gauge.class);
        when(mockGauge.labels(anyString(), anyString(), anyString(), anyString())).thenThrow(new RuntimeException("Labels error"));

        Component project = Component.newBuilder().setKey("proj1").setName("Project 1").build();

        Method method = PrometheusWebService.class.getDeclaredMethod("setGaugeValue", Gauge.class, Component.class, String.class, String.class, double.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(service, mockGauge, project, "ALL", "main", 5.0));
    }

    @Test
    void updateEnabledMetrics_whenAllMetricsDisabled_clearsEnabledMetrics() throws Exception {
        when(configuration.getBoolean(anyString())).thenReturn(Optional.of(false));

        PrometheusWebService service = new PrometheusWebService(configuration);
        callPrivate(service, "updateEnabledMetrics");

        Field enabledMetricsField = PrometheusWebService.class.getDeclaredField("enabledMetrics");
        enabledMetricsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<Metric<?>> enabledMetrics = (Set<Metric<?>>) enabledMetricsField.get(service);

        assertTrue(enabledMetrics.isEmpty());
    }

    @Test
    void updateEnabledGauges_whenDuplicateMetricNames_catchesExceptionAndLogsDebug() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);

        Field enabledMetricsField = PrometheusWebService.class.getDeclaredField("enabledMetrics");
        enabledMetricsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<Metric<?>> enabledMetrics = (Set<Metric<?>>) enabledMetricsField.get(service);
        enabledMetrics.clear();

        Metric<?> metric1 = new Metric.Builder("dup_metric", "Dup 1", Metric.ValueType.INT).create();
        Metric<?> metric2 = new Metric.Builder("dup.metric", "Dup 2", Metric.ValueType.INT).create();
        enabledMetrics.add(metric1);
        enabledMetrics.add(metric2);

        assertDoesNotThrow(() -> callPrivate(service, "updateEnabledGauges"));
    }

    @Test
    void getBranches_whenListThrowsException_returnsDefaultMainList() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Method getBranchesMethod = PrometheusWebService.class.getDeclaredMethod("getBranches", WsClient.class, String.class);
        getBranchesMethod.setAccessible(true);

        WsClient wsClient = mock(WsClient.class);
        when(wsClient.projectBranches()).thenThrow(new RuntimeException("Branches error"));

        @SuppressWarnings("unchecked")
        List<String> branches = (List<String>) getBranchesMethod.invoke(service, wsClient, "proj-key");

        assertEquals(1, branches.size());
        assertEquals("main", branches.get(0));
    }

    @Test
    void getBranches_whenBranchListIsEmpty_returnsDefaultMainList() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);

        WsClient wsClient = mock(WsClient.class);
        ProjectBranchesService pbService = mock(ProjectBranchesService.class);
        when(wsClient.projectBranches()).thenReturn(pbService);
        ListWsResponse emptyResponse = ListWsResponse.newBuilder().build();
        when(pbService.list(any())).thenReturn(emptyResponse);

        Method getBranchesMethod = PrometheusWebService.class.getDeclaredMethod("getBranches", WsClient.class, String.class);
        getBranchesMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> branches = (List<String>) getBranchesMethod.invoke(service, wsClient, "proj-key");

        assertEquals(1, branches.size());
        assertEquals("main", branches.get(0));
    }

    @Test
    void getMeasures_coversAllBranchConditions() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);

        WsClient wsClient = mock(WsClient.class);
        MeasuresService measuresService = mock(MeasuresService.class);
        when(wsClient.measures()).thenReturn(measuresService);

        Component project = Component.newBuilder().setKey("proj1").setName("Project 1").build();

        Method getMeasuresMethod = PrometheusWebService.class.getDeclaredMethod("getMeasures", WsClient.class, Component.class, String.class);
        getMeasuresMethod.setAccessible(true);

        getMeasuresMethod.invoke(service, wsClient, project, (String) null);
        getMeasuresMethod.invoke(service, wsClient, project, "");
        getMeasuresMethod.invoke(service, wsClient, project, "main");
        getMeasuresMethod.invoke(service, wsClient, project, "feature/my-branch");

        ArgumentCaptor<ComponentRequest> requestCaptor = ArgumentCaptor.forClass(ComponentRequest.class);
        verify(measuresService, times(4)).component(requestCaptor.capture());

        List<ComponentRequest> requests = requestCaptor.getAllValues();
        assertNull(requests.get(0).getBranch());
        assertNull(requests.get(1).getBranch());
        assertNull(requests.get(2).getBranch());
        assertEquals("feature/my-branch", requests.get(3).getBranch());
    }

    @Test
    void parseSeverityFilter_filtersOutEmptyTokens() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);

        Method m = PrometheusWebService.class.getDeclaredMethod("parseSeverityFilter", String.class);
        m.setAccessible(true);

        @SuppressWarnings("unchecked")
        Set<String> result = (Set<String>) m.invoke(service, "BLOCKER,,CRITICAL,  ");
        assertEquals(2, result.size());
        assertTrue(result.contains("BLOCKER"));
        assertTrue(result.contains("CRITICAL"));
    }

    // --- Line 227 & 230: processAllProjects ---

    @Test
    void processAllProjects_whenProjectsIsNull_doesNotProcessProjects() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        WsClient wsClient = mock(WsClient.class);
        ComponentsService componentsService = mock(ComponentsService.class);
        when(wsClient.components()).thenReturn(componentsService);
        when(componentsService.search(any())).thenReturn(null);

        Method method = PrometheusWebService.class.getDeclaredMethod("processAllProjects", WsClient.class, Set.class, CollectorRegistry.class, Map.class);
        method.setAccessible(true);

        Map<String, Gauge> requestGauges = new HashMap<>();
        assertDoesNotThrow(() -> method.invoke(service, wsClient, Set.of("ALL"), CollectorRegistry.defaultRegistry, requestGauges));
        assertTrue(requestGauges.isEmpty());
    }

    @Test
    void processAllProjects_whenGetProjectsThrowsException_catchesException() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        WsClient wsClient = mock(WsClient.class);
        when(wsClient.components()).thenThrow(new RuntimeException("API Connection failed"));

        Method method = PrometheusWebService.class.getDeclaredMethod("processAllProjects", WsClient.class, Set.class, CollectorRegistry.class, Map.class);
        method.setAccessible(true);

        Map<String, Gauge> requestGauges = new HashMap<>();
        assertDoesNotThrow(() -> method.invoke(service, wsClient, Set.of("ALL"), CollectorRegistry.defaultRegistry, requestGauges));
        assertTrue(requestGauges.isEmpty());
    }

    // --- Line 236: processProjectBranches ---

    @Test
    void processProjectBranches_whenProjectIsNull_returnsImmediately() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        WsClient wsClient = mock(WsClient.class);

        Method method = PrometheusWebService.class.getDeclaredMethod("processProjectBranches", WsClient.class, Component.class, Set.class, CollectorRegistry.class, Map.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(service, wsClient, null, Set.of("ALL"), CollectorRegistry.defaultRegistry, new HashMap<>()));
        verifyNoInteractions(wsClient);
    }

    @Test
    void processProjectBranches_whenProjectKeyUnset_returnsImmediately() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        WsClient wsClient = mock(WsClient.class);
        Component projectWithoutKey = Component.newBuilder().build();

        Method method = PrometheusWebService.class.getDeclaredMethod("processProjectBranches", WsClient.class, Component.class, Set.class, CollectorRegistry.class, Map.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(service, wsClient, projectWithoutKey, Set.of("ALL"), CollectorRegistry.defaultRegistry, new HashMap<>()));
        verifyNoInteractions(wsClient);
    }

    @Test
    void processProjectBranches_whenProjectKeyIsEmpty_returnsImmediately() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        WsClient wsClient = mock(WsClient.class);
        Component project = Component.newBuilder().setKey("").build();

        Method method = PrometheusWebService.class.getDeclaredMethod("processProjectBranches", WsClient.class, Component.class, Set.class, CollectorRegistry.class, Map.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(service, wsClient, project, Set.of("ALL"), CollectorRegistry.defaultRegistry, new HashMap<>()));
        verifyNoInteractions(wsClient);
    }

    // --- Line 241, 244, 245: processProjectBranches ---

    @Test
    void processProjectBranches_whenMeasuresServiceReturnsNullResponse_handlesGracefully() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        WsClient wsClient = mock(WsClient.class);
        ProjectBranchesService pbService = mock(ProjectBranchesService.class);
        when(wsClient.projectBranches()).thenReturn(pbService);
        when(pbService.list(any())).thenReturn(ListWsResponse.newBuilder().build());

        MeasuresService measuresService = mock(MeasuresService.class);
        when(wsClient.measures()).thenReturn(measuresService);
        when(measuresService.component(any())).thenReturn(null);

        Component project = Component.newBuilder().setKey("proj-1").build();

        Method method = PrometheusWebService.class.getDeclaredMethod("processProjectBranches", WsClient.class, Component.class, Set.class, CollectorRegistry.class, Map.class);
        method.setAccessible(true);

        Map<String, Gauge> requestGauges = new HashMap<>();
        assertDoesNotThrow(() -> method.invoke(service, wsClient, project, Set.of("ALL"), CollectorRegistry.defaultRegistry, requestGauges));
        assertTrue(requestGauges.isEmpty());
    }

    @Test
    void processProjectBranches_whenExceptionThrownInTry_catchesAndLogsWarning() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        WsClient wsClient = mock(WsClient.class);
        ProjectBranchesService pbService = mock(ProjectBranchesService.class);
        when(wsClient.projectBranches()).thenReturn(pbService);

        MeasuresService measuresService = mock(MeasuresService.class);
        when(wsClient.measures()).thenReturn(measuresService);
        when(measuresService.component(any())).thenThrow(new RuntimeException("Measures fetch error"));

        Component project = Component.newBuilder().setKey("proj-1").build();

        Method method = PrometheusWebService.class.getDeclaredMethod("processProjectBranches", WsClient.class, Component.class, Set.class, CollectorRegistry.class, Map.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(service, wsClient, project, Set.of("ALL"), CollectorRegistry.defaultRegistry, new HashMap<>()));
    }

    // --- Line 252: processMeasuresForBranch ---

    @Test
    void processMeasuresForBranch_whenWsResponseIsNull_doesNothing() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        WsClient wsClient = mock(WsClient.class);
        MeasuresService measuresService = mock(MeasuresService.class);
        when(wsClient.measures()).thenReturn(measuresService);
        when(measuresService.component(any())).thenReturn(null);

        Component project = Component.newBuilder().setKey("proj-1").build();

        Method method = PrometheusWebService.class.getDeclaredMethod("processMeasuresForBranch", WsClient.class, Component.class, String.class, Set.class, CollectorRegistry.class, Map.class);
        method.setAccessible(true);

        Map<String, Gauge> requestGauges = new HashMap<>();
        assertDoesNotThrow(() -> method.invoke(service, wsClient, project, "main", Set.of("ALL"), CollectorRegistry.defaultRegistry, requestGauges));
        assertTrue(requestGauges.isEmpty());
    }

    @Test
    void processMeasuresForBranch_whenWsResponseLacksComponent_doesNothing() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        WsClient wsClient = mock(WsClient.class);
        MeasuresService measuresService = mock(MeasuresService.class);
        when(wsClient.measures()).thenReturn(measuresService);
        when(measuresService.component(any())).thenReturn(ComponentWsResponse.newBuilder().build());

        Component project = Component.newBuilder().setKey("proj-1").build();

        Method method = PrometheusWebService.class.getDeclaredMethod("processMeasuresForBranch", WsClient.class, Component.class, String.class, Set.class, CollectorRegistry.class, Map.class);
        method.setAccessible(true);

        Map<String, Gauge> requestGauges = new HashMap<>();
        assertDoesNotThrow(() -> method.invoke(service, wsClient, project, "main", Set.of("ALL"), CollectorRegistry.defaultRegistry, requestGauges));
        assertTrue(requestGauges.isEmpty());
    }

    @Test
    void processMeasuresForBranch_whenMeasuresListIsEmpty_doesNothing() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        WsClient wsClient = mock(WsClient.class);
        MeasuresService measuresService = mock(MeasuresService.class);
        when(wsClient.measures()).thenReturn(measuresService);

        ComponentWsResponse responseMock = ComponentWsResponse.newBuilder()
                .setComponent(org.sonarqube.ws.Measures.Component.newBuilder().build())
                .build();
        when(measuresService.component(any())).thenReturn(responseMock);

        Component project = Component.newBuilder().setKey("proj-1").build();

        Method method = PrometheusWebService.class.getDeclaredMethod("processMeasuresForBranch", WsClient.class, Component.class, String.class, Set.class, CollectorRegistry.class, Map.class);
        method.setAccessible(true);

        Map<String, Gauge> requestGauges = new HashMap<>();
        assertDoesNotThrow(() -> method.invoke(service, wsClient, project, "main", Set.of("ALL"), CollectorRegistry.defaultRegistry, requestGauges));
        assertTrue(requestGauges.isEmpty());
    }

    // --- Line 263: processSingleMeasure ---

    @Test
    void processSingleMeasure_whenMeasureIsNull_returnsImmediately() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Component project = Component.newBuilder().setKey("proj-1").build();

        Method method = PrometheusWebService.class.getDeclaredMethod("processSingleMeasure", Component.class, String.class, Set.class, Measure.class, CollectorRegistry.class, Map.class);
        method.setAccessible(true);

        Map<String, Gauge> requestGauges = new HashMap<>();
        assertDoesNotThrow(() -> method.invoke(service, project, "main", Set.of("ALL"), null, CollectorRegistry.defaultRegistry, requestGauges));
        assertTrue(requestGauges.isEmpty());
    }

    @Test
    void processSingleMeasure_whenMeasureMetricUnset_returnsImmediately() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Component project = Component.newBuilder().setKey("proj-1").build();
        Measure measureWithUnsetMetric = Measure.newBuilder().build();

        Method method = PrometheusWebService.class.getDeclaredMethod("processSingleMeasure", Component.class, String.class, Set.class, Measure.class, CollectorRegistry.class, Map.class);
        method.setAccessible(true);

        Map<String, Gauge> requestGauges = new HashMap<>();
        assertDoesNotThrow(() -> method.invoke(service, project, "main", Set.of("BLOCKER"), measureWithUnsetMetric, CollectorRegistry.defaultRegistry, requestGauges));
        assertTrue(requestGauges.isEmpty());
    }

    // --- Lines 309, 310: setGaugeValue ---

    @Test
    void setGaugeValue_whenSeverityIsNull_usesDefaultSeverityALL() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Gauge gaugeMock = mock(Gauge.class);
        Gauge.Child childMock = mock(Gauge.Child.class);
        when(gaugeMock.labels(anyString(), anyString(), anyString(), anyString())).thenReturn(childMock);

        Component project = Component.newBuilder().setKey("proj-1").setName("Project 1").build();

        Method method = PrometheusWebService.class.getDeclaredMethod("setGaugeValue", Gauge.class, Component.class, String.class, String.class, double.class);
        method.setAccessible(true);

        method.invoke(service, gaugeMock, project, null, "main", 5.0);
        verify(gaugeMock).labels("proj-1", "Project 1", "ALL", "main");
        verify(childMock).set(5.0);
    }

    @Test
    void setGaugeValue_whenBranchIsNull_usesDefaultBranchMain() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Gauge gaugeMock = mock(Gauge.class);
        Gauge.Child childMock = mock(Gauge.Child.class);
        when(gaugeMock.labels(anyString(), anyString(), anyString(), anyString())).thenReturn(childMock);

        Component project = Component.newBuilder().setKey("proj-1").setName("Project 1").build();

        Method method = PrometheusWebService.class.getDeclaredMethod("setGaugeValue", Gauge.class, Component.class, String.class, String.class, double.class);
        method.setAccessible(true);

        method.invoke(service, gaugeMock, project, "BLOCKER", null, 5.0);
        verify(gaugeMock).labels("proj-1", "Project 1", "BLOCKER", "main");
        verify(childMock).set(5.0);
    }

    @Test
    void setGaugeValue_whenBranchIsEmpty_usesDefaultBranchMain() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Gauge gaugeMock = mock(Gauge.class);
        Gauge.Child childMock = mock(Gauge.Child.class);
        when(gaugeMock.labels(anyString(), anyString(), anyString(), anyString())).thenReturn(childMock);

        Component project = Component.newBuilder().setKey("proj-1").setName("Project 1").build();

        Method method = PrometheusWebService.class.getDeclaredMethod("setGaugeValue", Gauge.class, Component.class, String.class, String.class, double.class);
        method.setAccessible(true);

        method.invoke(service, gaugeMock, project, "BLOCKER", "", 5.0);
        verify(gaugeMock).labels("proj-1", "Project 1", "BLOCKER", "main");
        verify(childMock).set(5.0);
    }

    @Test
    void setGaugeValue_whenBranchIsProvided_usesProvidedBranch() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Gauge gaugeMock = mock(Gauge.class);
        Gauge.Child childMock = mock(Gauge.Child.class);
        when(gaugeMock.labels(anyString(), anyString(), anyString(), anyString())).thenReturn(childMock);

        Component project = Component.newBuilder().setKey("proj-1").setName("Project 1").build();

        Method method = PrometheusWebService.class.getDeclaredMethod("setGaugeValue", Gauge.class, Component.class, String.class, String.class, double.class);
        method.setAccessible(true);

        method.invoke(service, gaugeMock, project, "BLOCKER", "feature/new-ui", 10.0);
        verify(gaugeMock).labels("proj-1", "Project 1", "BLOCKER", "feature/new-ui");
        verify(childMock).set(10.0);
    }

    // --- Line 321: extractProjectKey ---

    @Test
    void extractProjectKey_whenProjectIsNull_returnsEmptyString() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Method method = PrometheusWebService.class.getDeclaredMethod("extractProjectKey", Component.class);
        method.setAccessible(true);

        String key = (String) method.invoke(service, (Object) null);
        assertEquals("", key);
    }

    @Test
    void extractProjectKey_whenProjectKeyUnset_returnsEmptyString() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Component projectUnsetKey = Component.newBuilder().build();

        Method method = PrometheusWebService.class.getDeclaredMethod("extractProjectKey", Component.class);
        method.setAccessible(true);

        String key = (String) method.invoke(service, projectUnsetKey);
        assertEquals("", key);
    }

    @Test
    void extractProjectKey_whenProjectKeyIsPresent_returnsProjectKey() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Component project = Component.newBuilder().setKey("valid-key").build();

        Method method = PrometheusWebService.class.getDeclaredMethod("extractProjectKey", Component.class);
        method.setAccessible(true);

        String key = (String) method.invoke(service, project);
        assertEquals("valid-key", key);
    }

    // --- Line 325: extractProjectName ---

    @Test
    void extractProjectName_whenProjectIsNull_returnsEmptyString() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Method method = PrometheusWebService.class.getDeclaredMethod("extractProjectName", Component.class);
        method.setAccessible(true);

        String name = (String) method.invoke(service, (Object) null);
        assertEquals("", name);
    }

    @Test
    void extractProjectName_whenProjectNameUnset_returnsEmptyString() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Component projectUnsetName = Component.newBuilder().build();

        Method method = PrometheusWebService.class.getDeclaredMethod("extractProjectName", Component.class);
        method.setAccessible(true);

        String name = (String) method.invoke(service, projectUnsetName);
        assertEquals("", name);
    }

    @Test
    void extractProjectName_whenProjectNameIsPresent_returnsProjectName() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Component project = Component.newBuilder().setName("Valid Name").build();

        Method method = PrometheusWebService.class.getDeclaredMethod("extractProjectName", Component.class);
        method.setAccessible(true);

        String name = (String) method.invoke(service, project);
        assertEquals("Valid Name", name);
    }

    // --- Lines 375, 378: getSafeHelp ---

    @Test
    void getSafeHelp_whenDescriptionBlankAndNameValid_returnsName() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Method method = PrometheusWebService.class.getDeclaredMethod("getSafeHelp", Metric.class);
        method.setAccessible(true);

        Metric<?> mockMetric = mock(Metric.class);
        when(mockMetric.getDescription()).thenReturn("   ");
        when(mockMetric.getName()).thenReturn("Metric Name");

        String help = (String) method.invoke(service, mockMetric);
        assertEquals("Metric Name", help);
    }

    @Test
    void getSafeHelp_whenMetricIsNull_returnsDefaultHelp() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Method method = PrometheusWebService.class.getDeclaredMethod("getSafeHelp", Metric.class);
        method.setAccessible(true);

        String help = (String) method.invoke(service, (Object) null);
        assertEquals("SonarQube Metric", help);
    }

    @Test
    void getSafeHelp_whenDescriptionAndNameBlankAndKeyValid_returnsKey() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Method method = PrometheusWebService.class.getDeclaredMethod("getSafeHelp", Metric.class);
        method.setAccessible(true);

        Metric<?> mockMetric = mock(Metric.class);
        when(mockMetric.getDescription()).thenReturn("   ");
        when(mockMetric.getName()).thenReturn("   ");
        when(mockMetric.getKey()).thenReturn("m_key");

        String help = (String) method.invoke(service, mockMetric);
        assertEquals("m_key", help);
    }

    @Test
    void getSafeHelp_whenDescriptionAndNameBlankAndKeyNull_returnsDefaultHelp() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        Method method = PrometheusWebService.class.getDeclaredMethod("getSafeHelp", Metric.class);
        method.setAccessible(true);

        Metric<?> mockMetric = mock(Metric.class);
        when(mockMetric.getDescription()).thenReturn(null);
        when(mockMetric.getName()).thenReturn(null);
        when(mockMetric.getKey()).thenReturn(null);

        String help = (String) method.invoke(service, mockMetric);
        assertEquals("SonarQube Metric", help);
    }

    // --- Lines 384, 385, 389: getBranches ---

    @Test
    void getBranches_whenResponseIsNull_returnsDefaultMainList() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        WsClient wsClient = mock(WsClient.class);
        ProjectBranchesService pbService = mock(ProjectBranchesService.class);
        when(wsClient.projectBranches()).thenReturn(pbService);
        when(pbService.list(any())).thenReturn(null);

        Method method = PrometheusWebService.class.getDeclaredMethod("getBranches", WsClient.class, String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> branches = (List<String>) method.invoke(service, wsClient, "proj-key");
        assertEquals(Collections.singletonList("main"), branches);
    }

    @Test
    void getBranches_whenBranchesListInResponseIsEmpty_returnsDefaultMainList() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        WsClient wsClient = mock(WsClient.class);
        ProjectBranchesService pbService = mock(ProjectBranchesService.class);
        when(wsClient.projectBranches()).thenReturn(pbService);

        ListWsResponse response = ListWsResponse.newBuilder().build();
        when(pbService.list(any())).thenReturn(response);

        Method method = PrometheusWebService.class.getDeclaredMethod("getBranches", WsClient.class, String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> branches = (List<String>) method.invoke(service, wsClient, "proj-key");
        assertEquals(Collections.singletonList("main"), branches);
    }

    @Test
    void getBranches_whenBranchesContainEmptyNames_filtersThemOut() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        WsClient wsClient = mock(WsClient.class);
        ProjectBranchesService pbService = mock(ProjectBranchesService.class);
        when(wsClient.projectBranches()).thenReturn(pbService);

        org.sonarqube.ws.ProjectBranches.Branch branchEmptyName = org.sonarqube.ws.ProjectBranches.Branch.newBuilder().setName("").build();
        org.sonarqube.ws.ProjectBranches.Branch branchValidName = org.sonarqube.ws.ProjectBranches.Branch.newBuilder().setName("release/1.0").build();

        ListWsResponse response = ListWsResponse.newBuilder()
                .addBranches(branchEmptyName)
                .addBranches(branchValidName)
                .build();
        when(pbService.list(any())).thenReturn(response);

        Method method = PrometheusWebService.class.getDeclaredMethod("getBranches", WsClient.class, String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> branches = (List<String>) method.invoke(service, wsClient, "proj-key");
        assertEquals(List.of("release/1.0"), branches);
    }

    @Test
    void getBranches_whenAllBranchesAreFilteredOut_returnsDefaultMainList() throws Exception {
        PrometheusWebService service = new PrometheusWebService(configuration);
        WsClient wsClient = mock(WsClient.class);
        ProjectBranchesService pbService = mock(ProjectBranchesService.class);
        when(wsClient.projectBranches()).thenReturn(pbService);

        org.sonarqube.ws.ProjectBranches.Branch branchEmptyName = org.sonarqube.ws.ProjectBranches.Branch.newBuilder().setName("").build();

        ListWsResponse response = ListWsResponse.newBuilder().addBranches(branchEmptyName).build();
        when(pbService.list(any())).thenReturn(response);

        Method method = PrometheusWebService.class.getDeclaredMethod("getBranches", WsClient.class, String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> branches = (List<String>) method.invoke(service, wsClient, "proj-key");
        assertEquals(Collections.singletonList("main"), branches);
    }

    private void callPrivate(Object target, String methodName) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method m = target.getClass().getDeclaredMethod(methodName);
        m.setAccessible(true);
        m.invoke(target);
    }
}
