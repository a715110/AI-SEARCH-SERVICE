package com.dodaso.ecosystem.ai.config;

import com.dodaso.ecosystem.baseline.common.security.AccessTokenFilter;
import com.dodaso.ecosystem.baseline.common.security.SsoJwtDecoders;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

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
 * <p>The caller's SSO access token (X-Access-Token, forwarded by ecws-service and mcp-service) is
 * validated by {@link AccessTokenFilter}; the chat and generate endpoints then act for the
 * token's login. Rollout mode while {@code dodaso.authz.token.required} is false: a call without
 * a token is still served and logged; an invalid token is rejected either way.
 */
@Configuration
public class SecurityConfig {

  @Value("${dodaso.instance.auth-issuer-uri}")
  private String authIssuerUri;

  @Value("${dodaso.authz.token.required:false}")
  private boolean tokenRequired;

  /** ai-search endpoints have no common prefix, so every path but /actuator is checked. */
  @Value("${dodaso.authz.token.path-prefix:/}")
  private String tokenPathPrefix;

  /** Endpoints other services call with no user; none today. */
  @Value("${dodaso.authz.token.service-paths:}")
  private List<String> tokenServicePaths;

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .addFilterBefore(
            new AccessTokenFilter(SsoJwtDecoders.forIssuer(authIssuerUri), tokenRequired, tokenPathPrefix,
                tokenServicePaths),
            AuthorizationFilter.class)
        // Stateless REST API: no browser session, no form posts -> CSRF does not apply.
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
    return http.build();
  }
}
