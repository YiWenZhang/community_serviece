package cn.community.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;

@Configuration
public class SecurityConfig {
  @Bean
  org.springframework.security.core.userdetails.UserDetailsService noDefaultUser() {
    return username -> {
      throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
          "使用数据库JSON登录");
    };
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  HttpSessionSecurityContextRepository contextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  CsrfTokenRepository csrfRepository() {
    return CookieCsrfTokenRepository.withHttpOnlyFalse();
  }

  @Bean
  SecurityFilterChain filterChain(
      HttpSecurity http,
      HttpSessionSecurityContextRepository repo,
      CsrfTokenRepository csrf,
      @Value("${app.public-docs}") boolean docs)
      throws Exception {
    http.csrf(
            c ->
                c.csrfTokenRepository(csrf)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .securityContext(c -> c.securityContextRepository(repo))
        .authorizeHttpRequests(
            a -> {
              a.requestMatchers(
                      "/api/v1/auth/csrf", "/api/v1/auth/login", "/actuator/health", "/error")
                  .permitAll();
              if (docs)
                a.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                    .permitAll();
              a.anyRequest().authenticated();
            })
        .formLogin(c -> c.disable())
        .httpBasic(c -> c.disable())
        .logout(c -> c.disable())
        .exceptionHandling(
            c ->
                c.authenticationEntryPoint(
                        (req, res, e) -> {
                          res.setStatus(401);
                          res.setContentType("application/json;charset=UTF-8");
                          res.getWriter().write("{\"code\":401,\"message\":\"请先登录\"}");
                        })
                    .accessDeniedHandler(
                        (req, res, e) -> {
                          res.setStatus(403);
                          res.setContentType("application/json;charset=UTF-8");
                          res.getWriter().write("{\"code\":403,\"message\":\"权限不足或CSRF令牌无效\"}");
                        }))
        .headers(
            c ->
                c.contentSecurityPolicy(
                    p ->
                        p.policyDirectives(
                            "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src"
                                + " 'self'; img-src 'self' data:; object-src 'none';"
                                + " frame-ancestors 'none'")));
    return http.build();
  }
}
