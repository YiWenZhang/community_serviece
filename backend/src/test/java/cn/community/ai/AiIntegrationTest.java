package cn.community.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.community.common.*;
import cn.community.identity.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
class AiIntegrationTest {
  @Autowired Db db;
  @Autowired Json json;
  @Autowired Auth auth;
  @Autowired AiModels models;
  @Autowired KnowledgeService knowledge;
  @Autowired RagService rag;
  @Autowired VectorStore vectors;
  @MockitoBean AiClient client;
  Actor admin, worker;
  String questionAnswer = "按规范填写需求申请表，并核验授权说明。[1]";

  @BeforeEach
  void setup() throws Exception {
    String name = db.one("SELECT DATABASE() AS db", Map.of()).get("db").toString();
    assertThat(name).matches(".*(_test|_regression)$");
    db.update("UPDATE sys_user SET status='ACTIVE' WHERE id IN (1,2,3)", Map.of());
    admin = auth.worker(1);
    worker = auth.worker(2);
    db.update("UPDATE ai_provider SET active=0", Map.of());
    var result =
        (Map<?, ?>)
            models.save(
                admin,
                null,
                new AiModels.Draft(
                    "test-chat",
                    "CHAT",
                    "OPENAI",
                    "http://127.0.0.1:19090",
                    "test-model",
                    "test-secret",
                    0));
    models.activate(admin, ((Number) result.get("id")).longValue(), true);
    when(client.structured(any(), anyString(), anyString()))
        .thenAnswer(
            inv -> {
              String system = inv.getArgument(1), input = inv.getArgument(2);
              if (system.contains("分析器")) {
                var parts = (List<?>) json.map("{\"parts\":" + input + "}").get("parts");
                return Map.of(
                    "sections",
                    parts.stream()
                        .map(json::map)
                        .map(
                            p ->
                                Db.p(
                                    "id",
                                    p.get("id"),
                                    "summary",
                                    "助餐申请填写需求表并核验授权说明",
                                    "keywords",
                                    List.of("助餐", "申请", "材料", "授权")))
                        .toList());
              }
              return Db.p("answer", questionAnswer, "sourceIds", List.of(1), "insufficient", false);
            });
    vectors.rebuild(List.of());
  }

  long upload(String suffix) {
    try {
      String text =
          "# 助餐申请演示规范 "
              + suffix
              + "\n第一条 申请材料\n助餐申请应当填写需求申请表，委托办理需要核验授权说明。\n第二条 其他规定\n本文件为演示规范，不作为真实政务依据。";
      var file =
          new MockMultipartFile(
              "file", "demo.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8));
      return ((Number)
              ((Map<?, ?>)
                      knowledge.upload(
                          admin, file, "助餐规范", "ELDERCARE", "虚构社区", "demo-001", null, null, true))
                  .get("id"))
          .longValue();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  int version(long id) {
    return ((Number) knowledge.owned(admin, id).get("version")).intValue();
  }

  long conversation() {
    return ((Number) ((Map<?, ?>) rag.create(worker)).get("id")).longValue();
  }

  @Test
  void uploadStructureVectorRagAndExactCitation() {
    long doc = upload("full");
    assertThat(knowledge.processOne()).isTrue();
    assertThat(knowledge.owned(admin, doc)).containsEntry("status", "READY");
    var reply =
        (Map<?, ?>) rag.ask(worker, conversation(), "助餐申请需要哪些材料", UUID.randomUUID().toString());
    assertThat(reply.get("answer").toString()).startsWith("以下依据演示规范").contains("[1]");
    var citation = (Map<?, ?>) ((List<?>) reply.get("citations")).getFirst();
    String content = citation.get("content").toString();
    String original = knowledge.owned(admin, doc).get("extracted_text").toString();
    assertThat(
            original.substring(
                ((Number) citation.get("start_offset")).intValue(),
                ((Number) citation.get("end_offset")).intValue()))
        .isEqualTo(content);
  }

  @Test
  void duplicateUploadRejectedAndRetrySuccessful() {
    long id = upload("duplicate");
    assertThatThrownBy(() -> upload("duplicate")).hasCauseInstanceOf(Problem.class);
    knowledge.processOne();
    knowledge.change(admin, id, "retry", version(id));
    knowledge.processOne();
    assertThat(knowledge.owned(admin, id)).containsEntry("status", "READY");
    assertThat(db.count("SELECT COUNT(*) FROM ai_chunk WHERE document_id=:id", Db.p("id", id)))
        .isEqualTo(1);
  }

  @Test
  void disabledDeletedExpiredAndOtherCommunityCannotBeRetrieved() {
    long id = upload("visibility");
    knowledge.processOne();
    knowledge.change(admin, id, "disable", version(id));
    assertThat(
            ((Map<?, ?>) rag.ask(worker, conversation(), "助餐", UUID.randomUUID().toString()))
                .get("citations"))
        .asList()
        .isEmpty();
    knowledge.change(admin, id, "enable", version(id));
    db.update("UPDATE ai_document SET expiry_date='2020-01-01' WHERE id=:id", Db.p("id", id));
    assertThat(
            ((Map<?, ?>) rag.ask(worker, conversation(), "助餐", UUID.randomUUID().toString()))
                .get("citations"))
        .asList()
        .isEmpty();
    db.update(
        "UPDATE ai_document SET expiry_date=NULL,community_id=999 WHERE id=:id", Db.p("id", id));
    assertThat(
            ((Map<?, ?>) rag.ask(worker, conversation(), "助餐", UUID.randomUUID().toString()))
                .get("citations"))
        .asList()
        .isEmpty();
    db.update("UPDATE ai_document SET community_id=1 WHERE id=:id", Db.p("id", id));
    knowledge.change(admin, id, "delete", version(id));
    assertThat(
            ((Map<?, ?>) rag.ask(worker, conversation(), "助餐", UUID.randomUUID().toString()))
                .get("citations"))
        .asList()
        .isEmpty();
  }

  @Test
  void failureIsVisibleAndCanBeRetried() {
    long id = upload("retry");
    when(client.structured(any(), anyString(), anyString())).thenThrow(new Problem(502, "模型鉴权失败"));
    knowledge.processOne();
    assertThat(knowledge.owned(admin, id))
        .containsEntry("status", "FAILED")
        .containsEntry("error_summary", "模型鉴权失败");
    knowledge.change(admin, id, "retry", version(id));
    assertThat(knowledge.owned(admin, id)).containsEntry("status", "PENDING");
  }

  @Test
  void conversationOwnershipAndIdempotency() {
    long id = upload("chat");
    knowledge.processOne();
    long c = conversation();
    String key = UUID.randomUUID().toString();
    var first = (Map<?, ?>) rag.ask(worker, c, "助餐材料", key);
    var second = (Map<?, ?>) rag.ask(worker, c, "助餐材料", key);
    assertThat(first.get("id").toString()).isEqualTo(second.get("id").toString());
    assertThatThrownBy(() -> rag.ask(worker, c, "其他问题", key)).isInstanceOf(Problem.class);
    var other = new Actor(3, 1, "other", Set.of("GRID_WORKER"), Set.of("ai:chat"));
    assertThatThrownBy(() -> rag.messages(other, c))
        .isInstanceOfSatisfying(Problem.class, e -> assertThat(e.status).isEqualTo(404));
    rag.delete(worker, c);
    assertThatThrownBy(() -> rag.messages(worker, c)).isInstanceOf(Problem.class);
  }

  @Test
  void fabricatedCitationIsBlockedAndConversationUnlocked() {
    upload("citation");
    knowledge.processOne();
    when(client.structured(any(), anyString(), anyString()))
        .thenReturn(Db.p("answer", "编造的政策。[99]", "sourceIds", List.of(99), "insufficient", false));
    long c = conversation();
    assertThatThrownBy(() -> rag.ask(worker, c, "助餐材料", UUID.randomUUID().toString()))
        .isInstanceOfSatisfying(Problem.class, e -> assertThat(e.status).isEqualTo(502));
    assertThat(db.count("SELECT COUNT(*) FROM ai_message WHERE conversation_id=:id", Db.p("id", c)))
        .isZero();
    assertThat(
            db.one("SELECT busy_token FROM ai_conversation WHERE id=:id", Db.p("id", c))
                .get("busy_token"))
        .isNull();
  }

  @Test
  void permissionsAndSecrets() {
    assertThatThrownBy(() -> knowledge.list(worker, 1, 20, "")).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> models.list(worker)).isInstanceOf(Problem.class);
    String response = json.write(models.list(admin));
    assertThat(response).doesNotContain("test-secret", "api_key_cipher").contains("has_key");
    var stat = auth.worker(3);
    assertThatThrownBy(() -> rag.conversations(stat)).isInstanceOf(Problem.class);
  }
}
