package co.etam.sonar.prometheus;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Gauge;
import io.prometheus.client.exporter.common.TextFormat;
import org.sonar.api.config.Configuration;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.Metric;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.server.ws.WebService;
import org.sonarqube.ws.Components;
import org.sonarqube.ws.Measures;
import org.sonarqube.ws.Measures.Measure;
import org.sonarqube.ws.client.WsClient;
import org.sonarqube.ws.client.WsClientFactories;
import org.sonarqube.ws.client.components.SearchRequest;
import org.sonarqube.ws.client.measures.ComponentRequest;
import org.sonarqube.ws.client.measures.MeasuresService;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.nonNull;

public class PrometheusWebService implements WebService {

    static final Set<Metric<?>> SUPPORTED_METRICS = new HashSet<>();
    static final String CONFIG_PREFIX = "prometheus.export.";
    private static final String METRIC_PREFIX = "sonarqube_";

    private final Configuration configuration;
    private final Map<String, Gauge> gauges = new HashMap<>();
    private final Set<Metric<?>> enabledMetrics = new HashSet<>();

    static {

        SUPPORTED_METRICS.add(CoreMetrics.BUGS);
        SUPPORTED_METRICS.add(CoreMetrics.VULNERABILITIES);
        SUPPORTED_METRICS.add(CoreMetrics.CODE_SMELLS);
        SUPPORTED_METRICS.add(CoreMetrics.COVERAGE);
        SUPPORTED_METRICS.add(CoreMetrics.TECHNICAL_DEBT);
        SUPPORTED_METRICS.add(CoreMetrics.COMPLEXITY);
        SUPPORTED_METRICS.add(CoreMetrics.LINES_TO_COVER);
        SUPPORTED_METRICS.add(CoreMetrics.VIOLATIONS);
        SUPPORTED_METRICS.add(CoreMetrics.ALERT_STATUS);
        SUPPORTED_METRICS.add(CoreMetrics.SECURITY_HOTSPOTS);
        SUPPORTED_METRICS.add(CoreMetrics.DUPLICATED_LINES);
        SUPPORTED_METRICS.add(CoreMetrics.NCLOC);
        SUPPORTED_METRICS.add(CoreMetrics.LINES);
        
    }

    public PrometheusWebService(Configuration configuration) {

        this.configuration = configuration;
    }

    @Override
    public void define(Context context) {

        updateEnabledMetrics();
        updateEnabledGauges();

        NewController controller = context.createController("api/prometheus");
        controller.setDescription("Prometheus Exporter");

        controller.createAction("metrics")
            .setHandler((request, response) -> {

                updateEnabledMetrics();
                updateEnabledGauges();

                if (!this.enabledMetrics.isEmpty()) {

                    WsClient wsClient = WsClientFactories.getLocal().newClient(request.localConnector());

                    List<Components.Component> projects = getProjects(wsClient);
                    projects.forEach(project -> {

                        Measures.ComponentWsResponse wsResponse = getMeasures(wsClient, project);

                        wsResponse.getComponent().getMeasuresList().forEach(measure -> {

                            String metricKey = measure.getMetric();
                            String valueStr = measure.getValue();
                            double valueDouble;

                            if (CoreMetrics.ALERT_STATUS.key().equals(metricKey)) {
                                // Map Quality Gate status string to numeric value
                                valueDouble = mapAlertStatusToDouble(valueStr);
                            } else {
                                // Attempt to parse other metrics as Double
                                valueDouble = parseDoubleOrDefault(valueStr, 0.0); // Use 0.0 as default if parsing fails
                            }

                            // Determine severity label from the metric key (e.g. "blocker_violations").
                            String severity = determineSeverityFromMetricKey(metricKey);

                            if (this.gauges.containsKey(metricKey)) {
                                // Pre-registered gauge (from enabledMetrics)
                                Gauge gauge = this.gauges.get(metricKey);
                                gauge.labels(project.getKey(), project.getName(), severity).set(valueDouble);
                            } else {
                                // Dynamically register a gauge for unexpected/severity-specific metric keys
                                Gauge dynamicGauge = Gauge.build()
                                        .name(METRIC_PREFIX + metricKey)
                                        .help("Metric exported from Sonar: " + metricKey)
                                        .labelNames("key", "name", "severity")
                                        .register();

                                this.gauges.put(metricKey, dynamicGauge);
                                dynamicGauge.labels(project.getKey(), project.getName(), severity).set(valueDouble);
                            }
                        });
                    });
                }

                OutputStream output = response.stream()
                    .setMediaType(TextFormat.CONTENT_TYPE_004)
                    .setStatus(200)
                    .output();

                try (OutputStreamWriter writer = new OutputStreamWriter(output)) {

                    TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples());
                }

            });

        controller.done();
    }

    private void updateEnabledMetrics() {

        Map<Boolean, List<Metric<?>>> byEnabledState = SUPPORTED_METRICS.stream()
            .collect(Collectors.groupingBy(metric -> this.configuration.getBoolean(CONFIG_PREFIX + metric.getKey()).orElse(false)));

        this.enabledMetrics.clear();

        if (nonNull(byEnabledState.get(true))) {
            this.enabledMetrics.addAll(byEnabledState.get(true));
        }
    }

    private void updateEnabledGauges() {

        CollectorRegistry.defaultRegistry.clear();

        // Clear the local map so we re-register gauges on each configuration refresh
        this.gauges.clear();

        // Register gauges for explicitly enabled metrics. Add a "severity" label so
        // Grafana can filter/group by severity (BLOCKER, CRITICAL, etc.). If Sonar
        // returns additional severity-specific metric keys we will dynamically
        // register gauges for them when handling measures.
        this.enabledMetrics.forEach(metric -> gauges.put(metric.getKey(), Gauge.build()
            .name(METRIC_PREFIX + metric.getKey())
            .help(metric.getDescription())
            .labelNames("key", "name", "severity")
            .register()));
    }

    private Measures.ComponentWsResponse getMeasures(WsClient wsClient, Components.Component project) {

        List<String> metricKeys = this.enabledMetrics.stream()
            .map(Metric::getKey)
            .collect(Collectors.toList());

        return wsClient.measures().component(new ComponentRequest()
            .setComponent(project.getKey())
            .setMetricKeys(metricKeys));
    }

    private List<Components.Component> getProjects(WsClient wsClient) {

        return wsClient.components().search(new SearchRequest()
            .setQualifiers(Collections.singletonList(Qualifiers.PROJECT))
            .setPs("500"))
            .getComponentsList();
    }


    private double mapAlertStatusToDouble(String status) {
        if (status == null) {
            return 0.0;
        }
        switch (status.toUpperCase()) {
            case "OK": return 1.0;
            case "WARN": return 2.0;
            case "ERROR": return 3.0;
            default: return 0.0; // Unknown or unexpected status
        }
    }

    /**
     * Safely parses a String to a Double, returning a default value if parsing fails.
     */
    private double parseDoubleOrDefault(String value, double defaultValue) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException | NullPointerException e) {
            // Log potentially? For now, return default.
            return defaultValue;
        }
    }

    /**
     * Try to infer a severity label from the metric key. Common Sonar metric keys
     * include severity text like "blocker", "critical", "major", "minor",
     * or "info" in their name (for example "blocker_violations"). Return a
     * canonical uppercase severity name or "ALL" when none can be detected.
     */
    private String determineSeverityFromMetricKey(String metricKey) {
        if (metricKey == null) {
            return "ALL";
        }
        String lower = metricKey.toLowerCase(Locale.ROOT);
        if (lower.contains("blocker")) return "BLOCKER";
        if (lower.contains("critical")) return "CRITICAL";
        if (lower.contains("major")) return "MAJOR";
        if (lower.contains("minor")) return "MINOR";
        if (lower.contains("info")) return "INFO";
        return "ALL";
    }
}