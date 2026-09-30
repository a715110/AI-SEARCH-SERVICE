package com.dodaso.ecosystem.ai.service;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Reachability check behind {@code OllamaHealthIndicator} (/actuator/health).
 *
 * <p><b>STEP1:</b>
 * <ul>
 *   <li>Address from {@code spring.ai.ollama.base-url}; was hard-coded
 *       {@code http://localhost:11434/api/tags}, which is never the Ollama host in a
 *       customer instance, so health would always have reported DOWN there.</li>
 *   <li>Short timeouts. The previous RestTemplate had none: an unresponsive Ollama made
 *       /actuator/health hang, and Eureka's health check and container probes with it.</li>
 * </ul>
 */
@Service
@Slf4j
public class OllamaHealthCheckService {

    /** Ollama endpoint that lists installed models; cheap and needs no model loaded. */
    private static final String TAGS_PATH = "/api/tags";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private final RestTemplate restTemplate;
    private final String tagsUrl;

    public OllamaHealthCheckService(RestTemplateBuilder builder,
                                    @Value("${spring.ai.ollama.base-url}") String ollamaBaseUrl) {
        this.restTemplate = builder
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(READ_TIMEOUT)
                .build();
        this.tagsUrl = OllamaService.stripTrailingSlash(ollamaBaseUrl) + TAGS_PATH;
    }

    public boolean isOllamaAlive() {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(tagsUrl, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("Ollama health check failed ({}): {}", tagsUrl, e.getMessage());
            return false;
        }
    }
}
