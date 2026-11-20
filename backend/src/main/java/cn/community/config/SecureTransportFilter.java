package cn.community.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** HTTPS模式拒绝明文请求，不信任客户端自行提供的X-Forwarded-Proto。 */
public class SecureTransportFilter extends OncePerRequestFilter {
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!request.isSecure()) {
      response.setStatus(426);
      response.setContentType("application/json;charset=UTF-8");
      response.getWriter().write("{\"code\":426,\"message\":\"请使用HTTPS访问系统\"}");
      return;
    }
    chain.doFilter(request, response);
  }
}
