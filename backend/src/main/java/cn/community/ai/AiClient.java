package cn.community.ai;

import cn.community.common.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

/** 两种协议的适配器。禁用重定向，避免Authorization被转发；不向用户或日志透传上游响应。 */
@Component
public class AiClient {
  private final Json json;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(15))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public AiClient(Json json) {
    this.json = json;
  }

  public String chat(AiModels.Provider p, String system, String user, boolean structured) {
    boolean anthropic = p.format().equals("ANTHROPIC");
    var body = Db.p("model", p.model(), "max_tokens", 4096, "stream", false, "temperature", 0.1);
    if (anthropic) {
      body.put("system", system);
      body.put("messages", List.of(Map.of("role", "user", "content", user)));
    } else {
      body.put(
          "messages",
          List.of(
              Map.of("role", "system", "content", system),
              Map.of("role", "user", "content", user)));
      if (structured) body.put("response_format", Map.of("type", "json_object"));
    }
    // DeepSeek默认思考输出可占用结构化输出预算；官方模型明确关闭。
    if (URI.create(p.baseUrl()).getHost().equals("api.deepseek.com"))
      body.put("thinking", Map.of("type", "disabled"));
    var r = post(p, anthropic ? "/messages" : "/chat/completions", body);
    try {
      String result;
      if (anthropic) {
        if ("max_tokens".equals(r.get("stop_reason"))) throw new Problem(502, "模型输出被截断，请减少文档长度后重试");
        result =
            ((List<?>) r.get("content"))
                .stream()
                    .map(json::map)
                    .filter(v -> "text".equals(v.get("type")))
                    .map(v -> v.get("text").toString())
                    .reduce("", String::concat);
      } else {
        var choice = json.map(((List<?>) r.get("choices")).getFirst());
        if ("length".equals(choice.get("finish_reason")))
          throw new Problem(502, "模型输出被截断，请减少文档长度后重试");
        Object value = json.map(choice.get("message")).get("content");
        result = value == null ? "" : value.toString();
      }
      if (result.isBlank()) throw new Problem(502, "模型返回空内容，请重试或检查模型配置");
      return result;
    } catch (Problem e) {
      throw e;
    } catch (Exception e) {
      throw new Problem(502, "模型响应格式不兼容，请检查接口格式和模型名");
    }
  }

  public Map<String, Object> structured(AiModels.Provider p, String system, String user) {
    String r = chat(p, system, user, true).trim();
    if (r.startsWith("```"))
      r = r.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
    try {
      return json.map(r);
    } catch (Exception e) {
      throw new Problem(502, "模型未返回有效JSON，请重试或更换配置");
    }
  }

  public float[] embed(AiModels.Provider p, String text) {
    var r =
        post(p, "/embeddings", Db.p("model", p.model(), "input", text, "encoding_format", "float"));
    try {
      var values = (List<?>) json.map(((List<?>) r.get("data")).getFirst()).get("embedding");
      Problem.require(!values.isEmpty() && values.size() <= 1024, "向量维度须为1至1024，请使用对应的Embedding模型");
      float[] vector = new float[values.size()];
      double norm = 0;
      for (int i = 0; i < vector.length; i++) {
        vector[i] = ((Number) values.get(i)).floatValue();
        if (!Float.isFinite(vector[i])) throw new IllegalArgumentException();
        norm += vector[i] * vector[i];
      }
      if (norm == 0) throw new IllegalArgumentException();
      for (int i = 0; i < vector.length; i++) vector[i] /= (float) Math.sqrt(norm);
      return vector;
    } catch (Problem e) {
      throw e;
    } catch (Exception e) {
      throw new Problem(502, "Embedding响应格式不兼容或包含无效向量");
    }
  }

  private Map<String, Object> post(AiModels.Provider p, String suffix, Object body) {
    AiModels.validateUrl(p.baseUrl());
    String base = p.baseUrl().replaceAll("/+$", "");
    String endpoint = base.endsWith(suffix) ? base : base + suffix;
    var builder =
        HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json");
    if (p.format().equals("ANTHROPIC"))
      builder.header("x-api-key", p.key()).header("anthropic-version", "2023-06-01");
    else builder.header("Authorization", "Bearer " + p.key());
    try {
      var r =
          client.send(
              builder.POST(HttpRequest.BodyPublishers.ofString(json.write(body))).build(),
              HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() < 200 || r.statusCode() >= 300)
        throw new Problem(
            502,
            switch (r.statusCode()) {
              case 401, 403 -> "模型鉴权失败，请检查密钥与访问权限";
              case 402 -> "模型账户余额不足";
              case 404 -> "模型接口不存在，请检查API地址、接口格式和模型名";
              case 429 -> "模型服务限流，请稍后重试";
              default -> "模型服务请求失败（HTTP " + r.statusCode() + "），请检查配置";
            });
      if (r.body().length() > 2_000_000) throw new Problem(502, "模型响应过大");
      return json.map(r.body());
    } catch (Problem e) {
      throw e;
    } catch (HttpTimeoutException e) {
      throw new Problem(504, "模型请求超时，请稍后重试");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Problem(503, "模型请求已中断");
    } catch (Exception e) {
      throw new Problem(502, "无法连接模型服务或响应无效，请检查API地址和网络");
    }
  }
}
