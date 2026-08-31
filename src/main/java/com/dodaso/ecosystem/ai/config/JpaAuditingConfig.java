package com.dodaso.ecosystem.ai.config;

import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware", dateTimeProviderRef = "auditDateTimeAware")
public class JpaAuditingConfig {

  @Bean("auditorAware")
  public AuditorAware<String> auditorAware() {
    // AI search service runs as a system process, not a user request
    return () -> Optional.of("ai-search-service");
  }

  @Bean("auditDateTimeAware")
  public DateTimeProvider auditDateTimeAware() {
    return () -> Optional.of(Instant.now());
  }
}
