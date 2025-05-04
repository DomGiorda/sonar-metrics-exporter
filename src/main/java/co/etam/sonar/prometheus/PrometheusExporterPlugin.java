package co.etam.sonar.prometheus;

import org.sonar.api.Plugin;
import org.sonar.api.PropertyType;
import org.sonar.api.config.PropertyDefinition;

import java.util.List;
import java.util.stream.Collectors;

public class PrometheusExporterPlugin implements Plugin {

    @Override
    public void define(Context context) {

        List<PropertyDefinition> properties = PrometheusWebService.SUPPORTED_METRICS.stream()
            .map(metric -> PropertyDefinition.builder(PrometheusWebService.CONFIG_PREFIX + metric.getKey())
                .name(String.format("Export \"%s\" as Prometheus metric.", metric.getName()))
                .description(metric.getDescription())
                .type(PropertyType.BOOLEAN)
                .defaultValue(Boolean.TRUE.toString())
                .build())
            .collect(Collectors.toList());

        context.addExtensions(properties);
        context.addExtension(PrometheusWebService.class);
    }
}
