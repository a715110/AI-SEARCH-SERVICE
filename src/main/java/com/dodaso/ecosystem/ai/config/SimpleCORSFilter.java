package com.dodaso.ecosystem.ai.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SimpleCORSFilter implements Filter {

  @Override
  public void init(FilterConfig arg0) throws ServletException {
  }
  // @functionality Sending headers before calling controller

  @Override
  public void doFilter(ServletRequest req, ServletResponse resp,
      FilterChain chain) throws IOException, ServletException {

    HttpServletResponse response = (HttpServletResponse) resp;
    response.setHeader("Access-Control-Allow-Origin", "*");
    response.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS, DELETE, PUT");
    response.setHeader("Access-Control-Max-Age", "3600");
    response.setHeader("Access-Control-Allow-Headers",
        "origin, content-type, accept, Authorization");
    response.setHeader("Access-Control-Expose-Headers",
        "Accept-Ranges, Content-Encoding, Content-Length, Content-Range, Authorization, Content-Disposition");
    response.setHeader("Content-Type", "application/json");
    response.setHeader("Access-Control-Allow-Credentials", "true");
    response.setHeader("X-Frame-Options", "SAMEORIGIN");
    chain.doFilter(req, resp);
  }

  @Override
  public void destroy() {
  }
}
