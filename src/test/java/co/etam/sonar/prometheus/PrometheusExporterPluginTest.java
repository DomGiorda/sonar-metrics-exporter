package co.etam.sonar.prometheus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.sonar.api.Plugin;
import org.sonar.api.PropertyType;
import org.sonar.api.config.PropertyDefinition;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrometheusExporterPluginTest {

    @Mock
    Plugin.Context context;

    AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @Test
    void define_registersPropertiesAndWebService() {
        PrometheusExporterPlugin plugin = new PrometheusExporterPlugin();

        plugin.define(context);

        // Verify the plugin attempted to register property definitions and the web service class
        verify(context).addExtensions(anyList());
        verify(context).addExtension(PrometheusWebService.class);
    }

    @Test
    void define_registersExpectedPropertyKeys() {
        PrometheusExporterPlugin plugin = new PrometheusExporterPlugin();

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);

        plugin.define(context);

        verify(context).addExtensions(captor.capture());

        List<PropertyDefinition> props = (List<PropertyDefinition>) captor.getValue();

        assertEquals(PrometheusWebService.SUPPORTED_METRICS.size(), props.size(), "Should register one property per supported metric");

        Set<String> actualKeys = props.stream()
                .map(PropertyDefinition::key)
                .collect(Collectors.toSet());

        Set<String> expectedKeys = PrometheusWebService.SUPPORTED_METRICS.stream()
                .map(m -> PrometheusWebService.CONFIG_PREFIX + m.getKey())
                .collect(Collectors.toSet());

        assertEquals(expectedKeys, actualKeys, "Registered property keys should match supported metric keys with the configured prefix");
    }
}
