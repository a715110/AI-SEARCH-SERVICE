package com.dodaso.ecosystem.ai.config;

import com.dodaso.ecosystem.baseline.common.config.DodasoInstanceProperties;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * CORS and response headers for browser callers.
 *
 * <p><b>STEP1:</b> {@code Access-Control-Allow-Origin} was {@code *} together with
 * {@code Access-Control-Allow-Credentials: true}. Browsers reject that combination for
 * credentialed requests, and {@code *} admits any site. The allowed origins are now the
 * origins of this instance's UIs, derived from {@link DodasoInstanceProperties}
 * (ECWS and ELCM base URLs, same rule as the authorization server's {@code originOf()}).
 * The request's {@code Origin} is echoed only when it is on that list; {@code Vary: Origin}
 * keeps caches from mixing responses. Requests without an {@code Origin} header
 * (service-to-service calls) are not affected.
 *
 * <p>All other headers are unchanged.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SimpleCORSFilter implements Filter {

  private final Set<String> allowedOrigins;

  public SimpleCORSFilter(DodasoInstanceProperties instance) {
    Set<String> origins = new LinkedHashSet<>();
    origins.add(originOf(instance.ecwsBaseUrl()));
    origins.add(originOf(instance.elcmBaseUrl()));
    this.allowedOrigins = Set.copyOf(origins);
    log.info("CORS allowed origins: {}", origins);
  }

  @Override
  public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
      throws IOException, ServletException {

    HttpServletRequest request = (HttpServletRequest) req;
    HttpServletResponse response = (HttpServletResponse) resp;

    String origin = request.getHeader("Origin");
    if (origin != null && allowedOrigins.contains(origin)) {
      response.setHeader("Access-Control-Allow-Origin", origin);
      response.setHeader("Access-Control-Allow-Credentials", "true");
    }
    response.addHeader("Vary", "Origin");
    response.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS, DELETE, PUT");
    response.setHeader("Access-Control-Max-Age", "3600");
    response.setHeader("Access-Control-Allow-Headers",
        "origin, content-type, accept, Authorization");
    response.setHeader("Access-Control-Expose-Headers",
        "Accept-Ranges, Content-Encoding, Content-Length, Content-Range, Authorization, Content-Disposition");
    response.setHeader("Content-Type", "application/json");
    response.setHeader("X-Frame-Options", "SAMEORIGIN");
    chain.doFilter(req, resp);
  }

  /** "https://acme.dodaso.app/ecws" -> "https://acme.dodaso.app"; keeps a non-default port. */
  static String originOf(String baseUrl) {
    URI uri = URI.create(baseUrl);
    boolean defaultPort = uri.getPort() == -1
        || ("https".equals(uri.getScheme()) && uri.getPort() == 443)
        || ("http".equals(uri.getScheme()) && uri.getPort() == 80);
    return uri.getScheme() + "://" + uri.getHost() + (defaultPort ? "" : ":" + uri.getPort());
  }
}
