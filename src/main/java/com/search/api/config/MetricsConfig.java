package com.search.api.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class MetricsConfig {

    @Bean
    public SmartInitializingSingleton metricsHistogramConfigurer(MeterRegistry registry) {
        return () -> {
            MeterFilter histogramFilter = new MeterFilter() {
                @Override
                public DistributionStatisticConfig configure(
                        io.micrometer.core.instrument.Meter.Id id,
                        DistributionStatisticConfig config) {
                    if (id.getName().startsWith("http.server.requests")) {
                        return DistributionStatisticConfig.builder()
                                .percentilesHistogram(true)
                                .percentiles(0.5, 0.90, 0.95, 0.99)
                                .serviceLevelObjectives(
                                        Duration.ofMillis(50).toNanos(),
                                        Duration.ofMillis(100).toNanos(),
                                        Duration.ofMillis(200).toNanos(),
                                        Duration.ofMillis(300).toNanos(),
                                        Duration.ofMillis(500).toNanos(),
                                        Duration.ofSeconds(1).toNanos(),
                                        Duration.ofSeconds(2).toNanos()
                                )
                                .minimumExpectedValue(Duration.ofMillis(1).toNanos())
                                .maximumExpectedValue(Duration.ofSeconds(10).toNanos())
                                .build()
                                .merge(config);
                    }
                    return config;
                }
            };

            registry.config().meterFilter(histogramFilter);

            // Remove existing http.server.requests meters so they get recreated with histogram
            registry.getMeters().stream()
                    .filter(m -> m.getId().getName().startsWith("http.server.requests"))
                    .forEach(registry::remove);
        };
    }
}
