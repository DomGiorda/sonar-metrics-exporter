package co.etam.sonar.prometheus;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Gauge;
import io.prometheus.client.exporter.common.TextFormat;
import org.sonar.api.config.Configuration;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.Metric;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonarqube.ws.Components;
import org.sonarqube.ws.Measures;
import org.sonarqube.ws.Measures.Measure;
import org.sonarqube.ws.client.WsClient;
import org.sonarqube.ws.client.WsClientFactories;
import org.sonarqube.ws.client.components.SearchRequest;
import org.sonarqube.ws.client.measures.ComponentRequest;
import org.sonarqube.ws.client.projectbranches.ListRequest;
import org.sonarqube.ws.ProjectBranches;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.nonNull;

public class PrometheusWebService implements WebService {

    private static final Logger LOG = Loggers.get(PrometheusWebService.class);

    static final Set<Metric<?>> SUPPORTED_METRICS = new HashSet<>();
    static final String CONFIG_PREFIX = "prometheus.export.";
    private static final String METRIC_PREFIX = "sonarqube_";
    private static final String PARAM_SEVERITY = "severity";

    private final Configuration configuration;
    private final Map<String, Gauge> gauges = new HashMap<>();
    private final Set<Metric<?>> enabledMetrics = new HashSet<>();

    static {
        // Size & Code
        SUPPORTED_METRICS.add(CoreMetrics.NCLOC);
        SUPPORTED_METRICS.add(CoreMetrics.LINES);
        SUPPORTED_METRICS.add(CoreMetrics.LINES_TO_COVER);
        SUPPORTED_METRICS.add(CoreMetrics.COMMENT_LINES);
        SUPPORTED_METRICS.add(CoreMetrics.COMMENT_LINES_DENSITY);

        // Issues & Violations
        SUPPORTED_METRICS.add(CoreMetrics.BUGS);
        SUPPORTED_METRICS.add(CoreMetrics.VULNERABILITIES);
        SUPPORTED_METRICS.add(CoreMetrics.CODE_SMELLS);
        SUPPORTED_METRICS.add(CoreMetrics.VIOLATIONS);
        SUPPORTED_METRICS.add(CoreMetrics.SECURITY_HOTSPOTS);

        // Technical Debt & Maintainability
        SUPPORTED_METRICS.add(CoreMetrics.TECHNICAL_DEBT); // Key is sqale_index
        SUPPORTED_METRICS.add(CoreMetrics.SQALE_DEBT_RATIO);
        SUPPORTED_METRICS.add(CoreMetrics.SQALE_RATING);
        SUPPORTED_METRICS.add(CoreMetrics.RELIABILITY_RATING);
        SUPPORTED_METRICS.add(CoreMetrics.SECURITY_RATING);

        // Ratings & Remediation Effort
        SUPPORTED_METRICS.add(CoreMetrics.RELIABILITY_REMEDIATION_EFFORT);
        SUPPORTED_METRICS.add(CoreMetrics.SECURITY_REMEDIATION_EFFORT);
        SUPPORTED_METRICS.add(CoreMetrics.SECURITY_HOTSPOTS_REVIEWED);

        // Complexity
        SUPPORTED_METRICS.add(CoreMetrics.COMPLEXITY);
        SUPPORTED_METRICS.add(CoreMetrics.COGNITIVE_COMPLEXITY);

        // Duplications
        SUPPORTED_METRICS.add(CoreMetrics.DUPLICATED_LINES);
        SUPPORTED_METRICS.add(CoreMetrics.DUPLICATED_LINES_DENSITY);
        SUPPORTED_METRICS.add(CoreMetrics.DUPLICATED_BLOCKS);
        SUPPORTED_METRICS.add(CoreMetrics.DUPLICATED_FILES);

        // Coverage & Tests
        SUPPORTED_METRICS.add(CoreMetrics.COVERAGE);
        SUPPORTED_METRICS.add(CoreMetrics.TESTS);
        SUPPORTED_METRICS.add(CoreMetrics.TEST_FAILURES);
        SUPPORTED_METRICS.add(CoreMetrics.TEST_ERRORS);
        SUPPORTED_METRICS.add(CoreMetrics.SKIPPED_TESTS);
        SUPPORTED_METRICS.add(CoreMetrics.TEST_SUCCESS_DENSITY);

        // Quality Gate
        SUPPORTED_METRICS.add(CoreMetrics.ALERT_STATUS);

        // Branch / New Code Period Metrics
        SUPPORTED_METRICS.add(CoreMetrics.NEW_BUGS);
        SUPPORTED_METRICS.add(CoreMetrics.NEW_VULNERABILITIES);
        SUPPORTED_METRICS.add(CoreMetrics.NEW_CODE_SMELLS);
        SUPPORTED_METRICS.add(CoreMetrics.NEW_COVERAGE);
        SUPPORTED_METRICS.add(CoreMetrics.NEW_DUPLICATED_LINES_DENSITY);

        // Language distribution
        SUPPORTED_METRICS.add(CoreMetrics.NCLOC_LANGUAGE_DISTRIBUTION);

        // Additional / custom metric keys present in SonarQube API
        SUPPORTED_METRICS.add(new Metric.Builder("security_review_rating", "Security Review Rating", Metric.ValueType.RATING).setDescription("Security Review Rating").create());
        SUPPORTED_METRICS.add(new Metric.Builder("security_hotspots_reviewed_status", "Security Hotspots Reviewed Status", Metric.ValueType.INT).setDescription("Security Hotspots Reviewed Status").create());
        SUPPORTED_METRICS.add(new Metric.Builder("security_hotspots_to_review_status", "Security Hotspots To Review Status", Metric.ValueType.INT).setDescription("Security Hotspots To Review Status").create());
        SUPPORTED_METRICS.add(new Metric.Builder("open_issues", "Open Issues", Metric.ValueType.INT).setDescription("Open Issues").create());
        SUPPORTED_METRICS.add(new Metric.Builder("confirmed_issues", "Confirmed Issues", Metric.ValueType.INT).setDescription("Confirmed Issues").create());
        SUPPORTED_METRICS.add(new Metric.Builder("false_positive_issues", "False Positive Issues", Metric.ValueType.INT).setDescription("False Positive Issues").create());
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
        action.createParam(PARAM_SEVERITY)
            .setDescription("Comma-separated list of severities to filter metrics by (e.g. BLOCKER, CRITICAL, MAJOR, MINOR, INFO, ALL). Defaults to all if omitted or empty.")
            .setRequired(false);

        action.setHandler((request, response) -> {
            try {
                updateEnabledMetrics();
                
                CollectorRegistry registry = new CollectorRegistry();
                
                // Exporter health status metric
                Gauge.build()
                    .name(METRIC_PREFIX + "exporter_up")
                    .help("1 if SonarQube Prometheus exporter endpoint is responding")
                    .register(registry)
                    .set(1.0);

                Map<String, Gauge> requestGauges = registerGaugesForRegistry(registry);

                String severityParam = request.param(PARAM_SEVERITY);
                if (severityParam == null || severityParam.trim().isEmpty()) {
                    severityParam = this.configuration.get(CONFIG_PREFIX + PARAM_SEVERITY).orElse(null);
                }
                Set<String> allowedSeverities = parseSeverityFilter(severityParam);

                if (!this.enabledMetrics.isEmpty()) {
                    try {
                        WsClient wsClient = createWsClient(request);
                        processAllProjects(wsClient, allowedSeverities, registry, requestGauges);
                    } catch (Exception e) {
                        LOG.warn("Failed to create WsClient or process projects: {}", e.getMessage());
                    }
                }

                OutputStream output = response.stream()
                    .setMediaType(TextFormat.CONTENT_TYPE_004)
                    .setStatus(200)
                    .output();

                try (OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                    TextFormat.write004(writer, registry.metricFamilySamples());
                }
            } catch (Exception e) {
                LOG.error("Error generating Prometheus metrics: {}", e.getMessage(), e);
                try {
                    OutputStream output = response.stream()
                        .setMediaType(TextFormat.CONTENT_TYPE_004)
                        .setStatus(200)
                        .output();
                    try (OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                        CollectorRegistry fallbackRegistry = new CollectorRegistry();
                        Gauge.build()
                            .name(METRIC_PREFIX + "exporter_up")
                            .help("1 if SonarQube Prometheus exporter endpoint is responding")
                            .register(fallbackRegistry)
                            .set(0.0);
                        TextFormat.write004(writer, fallbackRegistry.metricFamilySamples());
                    }
                } catch (Exception ignored) {
                }
            }
        });

        controller.done();
    }

    WsClient createWsClient(org.sonar.api.server.ws.Request request) {
        return WsClientFactories.getLocal().newClient(request.localConnector());
    }

    private Map<String, Gauge> registerGaugesForRegistry(CollectorRegistry registry) {
        Map<String, Gauge> gaugesMap = new HashMap<>();
        this.enabledMetrics.forEach(metric -> {
            String key = metric.getKey();
            String name = sanitizeMetricName(key);
            String help = getSafeHelp(metric);
            try {
                Gauge gauge = Gauge.build()
                    .name(name)
                    .help(help)
                    .labelNames("key", "name", PARAM_SEVERITY, "branch")
                    .register(registry);
                gaugesMap.put(key, gauge);
            } catch (Exception ignored) {
            }
        });
        return gaugesMap;
    }

    private void processAllProjects(WsClient wsClient, Set<String> allowedSeverities, CollectorRegistry registry, Map<String, Gauge> requestGauges) {
        try {
            List<Components.Component> projects = getProjects(wsClient);
            if (projects != null) {
                projects.forEach(project -> processProjectBranches(wsClient, project, allowedSeverities, registry, requestGauges));
            }
        } catch (Exception e) {
            LOG.warn("Failed to fetch projects list: {}", e.getMessage());
        }
    }

    private void processProjectBranches(WsClient wsClient, Components.Component project, Set<String> allowedSeverities, CollectorRegistry registry, Map<String, Gauge> requestGauges) {
        if (project == null || project.getKey() == null) {
            return;
        }
        try {
            List<String> branches = getBranches(wsClient, project.getKey());
            if (branches != null) {
                branches.forEach(branch -> processMeasuresForBranch(wsClient, project, branch, allowedSeverities, registry, requestGauges));
            }
        } catch (Exception e) {
            LOG.warn("Failed to fetch branches for project {}: {}", project.getKey(), e.getMessage());
        }
    }

    private void processMeasuresForBranch(WsClient wsClient, Components.Component project, String branch, Set<String> allowedSeverities, CollectorRegistry registry, Map<String, Gauge> requestGauges) {
        try {
            Measures.ComponentWsResponse wsResponse = getMeasures(wsClient, project, branch);
            if (wsResponse != null && wsResponse.hasComponent() && wsResponse.getComponent().getMeasuresList() != null) {
                wsResponse.getComponent().getMeasuresList().forEach(measure ->
                    processSingleMeasure(project, branch, allowedSeverities, measure, registry, requestGauges)
                );
            }
        } catch (Exception e) {
            LOG.warn("Failed to fetch measures for project {} branch {}: {}", project.getKey(), branch, e.getMessage());
        }
    }

    private void processSingleMeasure(Components.Component project, String branch, Set<String> allowedSeverities, Measure measure, CollectorRegistry registry, Map<String, Gauge> requestGauges) {
        if (measure == null || measure.getMetric() == null) {
            return;
        }
        String metricKey = measure.getMetric();
        String valueStr = measure.getValue();
        double valueDouble;

        if (CoreMetrics.ALERT_STATUS.key().equals(metricKey)) {
            valueDouble = mapAlertStatusToDouble(valueStr);
        } else {
            valueDouble = parseDoubleOrDefault(valueStr, 0.0);
        }

        String severity = determineSeverityFromMetricKey(metricKey);

        if (!matchesSeverityFilter(severity, allowedSeverities)) {
            return;
        }

        Gauge gauge = requestGauges.get(metricKey);
        if (gauge == null) {
            String sanitizedName = sanitizeMetricName(metricKey);
            try {
                gauge = Gauge.build()
                        .name(sanitizedName)
                        .help("Metric exported from Sonar: " + metricKey)
                        .labelNames("key", "name", PARAM_SEVERITY, "branch")
                        .register(registry);
                requestGauges.put(metricKey, gauge);
            } catch (Exception ignored) {
            }
        }

        if (gauge != null) {
            try {
                gauge.labels(
                    project.getKey() != null ? project.getKey() : "",
                    project.getName() != null ? project.getName() : "",
                    severity,
                    branch != null ? branch : "main"
                ).set(valueDouble);
            } catch (Exception ignored) {
            }
        }
    }

    private void updateEnabledMetrics() {
        Map<Boolean, List<Metric<?>>> byEnabledState = SUPPORTED_METRICS.stream()
            .collect(Collectors.groupingBy(metric -> this.configuration.getBoolean(CONFIG_PREFIX + metric.getKey()).orElse(true)));

        this.enabledMetrics.clear();

        if (nonNull(byEnabledState.get(true))) {
            this.enabledMetrics.addAll(byEnabledState.get(true));
        }
    }

    private void updateEnabledGauges() {
        CollectorRegistry.defaultRegistry.clear();
        this.gauges.clear();
        this.enabledMetrics.forEach(metric -> {
            String key = metric.getKey();
            String name = sanitizeMetricName(key);
            String help = getSafeHelp(metric);
            try {
                Gauge gauge = Gauge.build()
                    .name(name)
                    .help(help)
                    .labelNames("key", "name", PARAM_SEVERITY, "branch")
                    .register();
                this.gauges.put(key, gauge);
            } catch (Exception ignored) {
            }
        });
    }

    private String sanitizeMetricName(String key) {
        if (key == null || key.trim().isEmpty()) {
            return METRIC_PREFIX + "unknown";
        }
        String sanitized = key.replaceAll("[^a-zA-Z0-9_:]", "_");
        if (!sanitized.startsWith(METRIC_PREFIX)) {
            sanitized = METRIC_PREFIX + sanitized;
        }
        return sanitized;
    }

    private String getSafeHelp(Metric<?> metric) {
        if (metric != null && metric.getDescription() != null && !metric.getDescription().trim().isEmpty()) {
            return metric.getDescription();
        }
        if (metric != null && metric.getName() != null && !metric.getName().trim().isEmpty()) {
            return metric.getName();
        }
        return metric != null && metric.getKey() != null ? metric.getKey() : "SonarQube Metric";
    }

    private List<String> getBranches(WsClient wsClient, String projectKey) {
        try {
            ProjectBranches.ListWsResponse response = wsClient.projectBranches().list(new ListRequest().setProject(projectKey));
            List<String> branches = response.getBranchesList().stream()
                    .map(ProjectBranches.Branch::getName)
                    .collect(Collectors.toList());
            return branches.isEmpty() ? Collections.singletonList("main") : branches;
        } catch (Exception e) {
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
            default: return 0.0;
        }
    }

    private double parseDoubleOrDefault(String value, double defaultValue) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException | NullPointerException e) {
            return defaultValue;
        }
    }

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