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

        // Act: set a value for a project with severity "ALL"
        String projectKey = "proj-1";
        String projectName = "Project One";
        String severity = "ALL"; // totals use ALL by our implementation
        bugsGauge.labels(projectKey, projectName, severity).set(13.0);

        // Assert: sample exported with severity label
        Double sample = CollectorRegistry.defaultRegistry.getSampleValue(
                "sonarqube_" + CoreMetrics.BUGS.getKey(),
                new String[]{"key", "name", "severity"},
                new String[]{projectKey, projectName, severity}
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

    // Helper to call private no-arg methods
    private void callPrivate(Object target, String methodName) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method m = target.getClass().getDeclaredMethod(methodName);
        m.setAccessible(true);
        m.invoke(target);
    }
}
