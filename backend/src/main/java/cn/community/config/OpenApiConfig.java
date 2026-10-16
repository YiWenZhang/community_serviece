package cn.community.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfig {
  @Bean
  OpenAPI communityApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("社区老年服务业务微服务接口")
                .version("1.0.0")
                .description(
                    "不含AI。先GET"
                        + " /api/v1/auth/csrf，再使用X-XSRF-TOKEN及Cookie登录。所有写请求要求CSRF，触达、跟进及异步作业要求Idempotency-Key。BIGINT及统计数量响应为字符串。"))
        .components(
            new Components()
                .addSecuritySchemes(
                    "session",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name("JSESSIONID")))
        .addSecurityItem(new SecurityRequirement().addList("session"));
  }
}
