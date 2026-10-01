package com.dodaso.ecosystem.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Web security for ai-search (internal REST API, called by ecws-service and the UIs).
 *
 * <p><b>STEP1:</b> the same rules now apply in every environment. Before, the filter chain
 * depended on the profile NAME ({@code @Value("${spring.profiles.active}")}):
 * <ul>
 *   <li>{@code local} / {@code test}: everything permitted, CSRF off.</li>
 *   <li>any other profile, including {@code cloud}: no authorization rules (so everything
 *       permitted as well) but CSRF ON. Every endpoint here is a POST, and service callers
 *       send no CSRF token, so every call would have been rejected with 403 in a customer
 *       instance.</li>
 * </ul>
 * The {@code @Value} also had no default, so a start without an explicit profile failed,
 * and a profile list such as {@code cloud,debug} matched neither branch.
 *
 * <p>Effective access is unchanged: all requests are permitted. The service is reachable
 * only inside the instance network (never through the public ingress).
 *
 * <p><b>Open (Step 2):</b> validate the caller's JWT here, with the platform key, once the
 * key question for ai-search is settled (SDS Step 1 services addendum, O4).
 */
@Configuration
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        // Stateless REST API: no browser session, no form posts -> CSRF does not apply.
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
    return http.build();
  }
}
