package com.dodaso.ecosystem.ai.config;

import com.dodaso.ecosystem.ai.service.OllamaHealthCheckService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class OllamaHealthIndicator implements HealthIndicator {

    private final OllamaHealthCheckService healthCheckService;

    public OllamaHealthIndicator(OllamaHealthCheckService healthCheckService) {
        this.healthCheckService = healthCheckService;
    }

    @Override
    public Health health() {
        if (healthCheckService.isOllamaAlive()) {
            return Health.up().withDetail("ollama", "running").build();
        } else {
            return Health.down().withDetail("ollama", "not reachable").build();
        }
    }
}
