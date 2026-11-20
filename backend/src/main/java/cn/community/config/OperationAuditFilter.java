package cn.community.config;

import cn.community.common.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 独立于业务事务记录访问结果，不记录请求体、密码、密钥或完整查询字符串。 */
public class OperationAuditFilter extends OncePerRequestFilter {
  private final Audit audit;

  public OperationAuditFilter(Audit audit) {
    this.audit = audit;
  }

  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Long user = null;
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null)
      try {
        user = Long.parseLong(authentication.getName());
      } catch (NumberFormatException ignored) {
      }
    boolean failed = false;
    try {
      chain.doFilter(request, response);
    } catch (ServletException | IOException | RuntimeException e) {
      failed = true;
      throw e;
    } finally {
      if (request.getRequestURI().startsWith("/api/v1/")
          && !request.getRequestURI().equals("/api/v1/auth/csrf")) {
        Object read = request.getAttribute("auditActorId");
        if (read instanceof Number n) user = n.longValue();
        if (user != null)
          try {
            audit.request(
                user,
                request.getAttribute("auditCommunityId"),
                request.getMethod(),
                request.getRequestURI(),
                failed && response.getStatus() < 400 ? 500 : response.getStatus());
          } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                .error("operation audit persistence failed trace={}", org.slf4j.MDC.get("traceId"));
          }
      }
    }
  }
}
