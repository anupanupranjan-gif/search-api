package com.search.api.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class MetricsConfig {

    private final Timer searchLatencyTimer;

    public MetricsConfig(MeterRegistry registry) {
        this.searchLatencyTimer = Timer.builder("search.latency")
                .description("Search endpoint latency")
                .tag("endpoint", "search")
                .publishPercentiles(0.5, 0.90, 0.95, 0.99)
                .publishPercentileHistogram(true)
                .serviceLevelObjectives(
                        Duration.ofMillis(50),
                        Duration.ofMillis(100),
                        Duration.ofMillis(200),
                        Duration.ofMillis(300),
                        Duration.ofMillis(500),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2)
                )
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(10))
                .register(registry);
    }

    public Timer getSearchLatencyTimer() {
        return searchLatencyTimer;
    }
}
