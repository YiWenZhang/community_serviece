package cn.community.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-200)
public class TraceFilter extends OncePerRequestFilter {
  private final cn.community.common.Audit audit;

  public TraceFilter(cn.community.common.Audit audit) {
    this.audit = audit;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String trace = UUID.randomUUID().toString();
    MDC.put("traceId", trace);
    res.setHeader("X-Trace-Id", trace);
    res.setHeader("Cache-Control", "no-store");
    try {
      chain.doFilter(req, res);
    } finally {
      if (res.getStatus() == 401 || res.getStatus() == 403) {
        try {
          audit.system("HTTP_ACCESS_REJECTED", "DENIED");
        } catch (RuntimeException ignored) {
          org.slf4j.LoggerFactory.getLogger(TraceFilter.class)
              .warn("security audit unavailable trace={}", trace);
        }
      }
      MDC.remove("traceId");
    }
  }
}
