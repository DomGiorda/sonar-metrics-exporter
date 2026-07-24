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

import org.sonarqube.ws.client.projectbranches.ListRequest;
import org.sonarqube.ws.ProjectBranches;

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

        // Test metrics
        SUPPORTED_METRICS.add(CoreMetrics.TEST_SUCCESS_DENSITY);
        SUPPORTED_METRICS.add(CoreMetrics.TESTS);
        SUPPORTED_METRICS.add(CoreMetrics.TEST_FAILURES);
        SUPPORTED_METRICS.add(CoreMetrics.TEST_ERRORS);
        SUPPORTED_METRICS.add(CoreMetrics.SKIPPED_TESTS);

        // Branch/PR metrics (New Code Period)
        SUPPORTED_METRICS.add(CoreMetrics.NEW_BUGS);
        SUPPORTED_METRICS.add(CoreMetrics.NEW_VULNERABILITIES);
        SUPPORTED_METRICS.add(CoreMetrics.NEW_CODE_SMELLS);
        SUPPORTED_METRICS.add(CoreMetrics.NEW_COVERAGE);
        SUPPORTED_METRICS.add(CoreMetrics.NEW_DUPLICATED_LINES_DENSITY);

        // Language breakdown
        SUPPORTED_METRICS.add(CoreMetrics.NCLOC_LANGUAGE_DISTRIBUTION);

        // Maintainability ratios
        SUPPORTED_METRICS.add(CoreMetrics.SQALE_DEBT_RATIO);
        SUPPORTED_METRICS.add(CoreMetrics.SQALE_RATING);
        SUPPORTED_METRICS.add(CoreMetrics.RELIABILITY_RATING);
        SUPPORTED_METRICS.add(CoreMetrics.SECURITY_RATING);

        // Security hotspots breakdown
        SUPPORTED_METRICS.add(CoreMetrics.SECURITY_HOTSPOTS_REVIEWED);
        
        // Custom/newer metrics that might not be in CoreMetrics constant for this API version
        SUPPORTED_METRICS.add(new Metric.Builder("security_hotspots_reviewed_status", "Security Hotspots Reviewed Status", Metric.ValueType.FLOAT).setDescription("Security Hotspots Reviewed Status").create());
        SUPPORTED_METRICS.add(new Metric.Builder("to_review_status", "To Review Status", Metric.ValueType.FLOAT).setDescription("To Review Status").create());
        
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

        NewAction action = controller.createAction("metrics");
        action.setDescription("Exports SonarQube metrics in Prometheus format");
        action.createParam("severity")
            .setDescription("Comma-separated list of severities to filter metrics by (e.g. BLOCKER, CRITICAL, MAJOR, MINOR, INFO, ALL). Defaults to all if omitted or empty.")
            .setRequired(false);

        action.setHandler((request, response) -> {

                updateEnabledMetrics();
                updateEnabledGauges();

                String severityParam = request.param("severity");
                if (severityParam == null || severityParam.trim().isEmpty()) {
                    severityParam = this.configuration.get(CONFIG_PREFIX + "severity").orElse(null);
                }
                Set<String> allowedSeverities = parseSeverityFilter(severityParam);

                if (!this.enabledMetrics.isEmpty()) {
                    WsClient wsClient = createWsClient(request);
                    processAllProjects(wsClient, allowedSeverities);
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

    WsClient createWsClient(org.sonar.api.server.ws.Request request) {
        return WsClientFactories.getLocal().newClient(request.localConnector());
    }

    private void processAllProjects(WsClient wsClient, Set<String> allowedSeverities) {
        List<Components.Component> projects = getProjects(wsClient);
        projects.forEach(project -> processProjectBranches(wsClient, project, allowedSeverities));
    }

    private void processProjectBranches(WsClient wsClient, Components.Component project, Set<String> allowedSeverities) {
        List<String> branches = getBranches(wsClient, project.getKey());
        branches.forEach(branch -> processMeasuresForBranch(wsClient, project, branch, allowedSeverities));
    }

    private void processMeasuresForBranch(WsClient wsClient, Components.Component project, String branch, Set<String> allowedSeverities) {
        Measures.ComponentWsResponse wsResponse = getMeasures(wsClient, project, branch);
        wsResponse.getComponent().getMeasuresList().forEach(measure -> 
            processSingleMeasure(project, branch, allowedSeverities, measure)
        );
    }

    private void processSingleMeasure(Components.Component project, String branch, Set<String> allowedSeverities, Measure measure) {
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

        if (!matchesSeverityFilter(severity, allowedSeverities)) {
            return;
        }

        if (this.gauges.containsKey(metricKey)) {
            // Pre-registered gauge (from enabledMetrics)
            Gauge gauge = this.gauges.get(metricKey);
            gauge.labels(project.getKey(), project.getName(), severity, branch).set(valueDouble);
        } else {
            // Dynamically register a gauge for unexpected/severity-specific metric keys
            Gauge dynamicGauge = Gauge.build()
                    .name(METRIC_PREFIX + metricKey)
                    .help("Metric exported from Sonar: " + metricKey)
                    .labelNames("key", "name", "severity", "branch")
                    .register();

            this.gauges.put(metricKey, dynamicGauge);
            dynamicGauge.labels(project.getKey(), project.getName(), severity, branch).set(valueDouble);
        }
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
            .labelNames("key", "name", "severity", "branch")
            .register()));
    }

    private List<String> getBranches(WsClient wsClient, String projectKey) {
        try {
            ProjectBranches.ListWsResponse response = wsClient.projectBranches().list(new ListRequest().setProject(projectKey));
            List<String> branches = response.getBranchesList().stream()
                    .map(ProjectBranches.Branch::getName)
                    .collect(Collectors.toList());
            return branches.isEmpty() ? Collections.singletonList("main") : branches;
        } catch (Exception e) {
            // Fallback for Community Edition or older SonarQube versions without branch support
            return Collections.singletonList("main");
        }
    }

    private Measures.ComponentWsResponse getMeasures(WsClient wsClient, Components.Component project, String branch) {

        List<String> metricKeys = this.enabledMetrics.stream()
            .map(Metric::getKey)
            .collect(Collectors.toList());

        ComponentRequest request = new ComponentRequest()
            .setComponent(project.getKey())
            .setMetricKeys(metricKeys);
        
        if (branch != null && !branch.isEmpty() && !branch.equals("main")) {
            request.setBranch(branch);
        }

        return wsClient.measures().component(request);
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

    private Set<String> parseSeverityFilter(String severityParam) {
        if (severityParam == null || severityParam.trim().isEmpty()) {
            return Collections.emptySet();
        }
        return Arrays.stream(severityParam.split(","))
                .map(String::trim)
                .map(s -> s.toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private boolean matchesSeverityFilter(String severity, Set<String> allowedSeverities) {
        if (allowedSeverities.isEmpty() || allowedSeverities.contains("ALL_SEVERITIES") || allowedSeverities.contains("*")) {
            return true;
        }
        return allowedSeverities.contains(severity);
    }
}