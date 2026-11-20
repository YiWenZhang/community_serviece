package cn.community.ai;

import static org.assertj.core.api.Assertions.*;

import cn.community.common.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;

class AiClientTest {
  HttpServer server;
  Json json = new Json(new ObjectMapper());
  AiClient client = new AiClient(json);
  String response;
  int status = 200;
  Map<String, Object> request;
  String path, key;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        x -> {
          path = x.getRequestURI().getPath();
          key = x.getRequestHeaders().getFirst("Authorization");
          if (key == null) key = x.getRequestHeaders().getFirst("x-api-key");
          request = json.map(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body = response.getBytes(StandardCharsets.UTF_8);
          x.sendResponseHeaders(status, body.length);
          x.getResponseBody().write(body);
          x.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  AiModels.Provider provider(String format, String suffix) {
    return new AiModels.Provider(
        1,
        format,
        "http://127.0.0.1:" + server.getAddress().getPort() + suffix,
        "custom-model",
        "private-key",
        0);
  }

  @Test
  void openAiJsonProtocolAndFullEndpoint() {
    response =
        "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}]}";
    assertThat(client.structured(provider("OPENAI", "/v1/chat/completions"), "json", "test"))
        .containsEntry("ok", true);
    assertThat(path).isEqualTo("/v1/chat/completions");
    assertThat(key).isEqualTo("Bearer private-key");
    assertThat(request).containsEntry("model", "custom-model").containsKey("response_format");
  }

  @Test
  void anthropicMessagesProtocol() {
    response =
        "{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\"}";
    assertThat(client.chat(provider("ANTHROPIC", "/anthropic"), "system", "question", true))
        .isEqualTo("hello");
    assertThat(path).isEqualTo("/anthropic/messages");
    assertThat(key).isEqualTo("private-key");
    assertThat(request).containsEntry("system", "system").doesNotContainKey("response_format");
  }

  @Test
  void errorsNeverExposeUpstreamSecrets() {
    status = 401;
    response = "private-key upstream detail";
    assertThatThrownBy(() -> client.chat(provider("OPENAI", ""), "a", "b", false))
        .isInstanceOf(Problem.class)
        .hasMessageContaining("鉴权")
        .hasMessageNotContaining("private-key");
  }

  @Test
  void rejectsEmptyOrTruncatedOutput() {
    response = "{\"choices\":[{\"message\":{\"content\":\"   \"},\"finish_reason\":\"stop\"}]}";
    assertThatThrownBy(() -> client.chat(provider("OPENAI", ""), "a", "b", false))
        .hasMessageContaining("空内容");
    response =
        "{\"choices\":[{\"message\":{\"content\":\"partial\"},\"finish_reason\":\"length\"}]}";
    assertThatThrownBy(() -> client.chat(provider("OPENAI", ""), "a", "b", false))
        .hasMessageContaining("截断");
  }

  @Test
  void embeddingsAreValidatedAndNormalized() {
    response = "{\"data\":[{\"embedding\":[3,4,0]}]}";
    assertThat(client.embed(provider("OPENAI", "/v1"), "text")).containsExactly(0.6f, 0.8f, 0f);
    assertThat(path).isEqualTo("/v1/embeddings");
    response = "{\"data\":[{\"embedding\":[0,0]}]}";
    assertThatThrownBy(() -> client.embed(provider("OPENAI", "/v1"), "text"))
        .isInstanceOf(Problem.class);
  }
}
