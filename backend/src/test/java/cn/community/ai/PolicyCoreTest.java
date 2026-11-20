package cn.community.ai;

import static org.assertj.core.api.Assertions.*;

import cn.community.common.*;
import cn.community.identity.Actor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class PolicyCoreTest {
  PolicyParser parser = new PolicyParser();
  Json json = new Json(new ObjectMapper());
  @TempDir Path temp;

  @Test
  void splitsPreserveExactOffsetsAndOverlap() {
    String text = "# 规范\n\n第一条 助餐材料\n" + "助餐申请应填写需求表，授权需要核验。\n".repeat(180);
    var parts = parser.split(text);
    assertThat(parts.size()).isGreaterThan(2);
    for (var p : parts) assertThat(text.substring(p.start(), p.end())).isEqualTo(p.content());
    assertThat(parts.get(1).start()).isLessThan(parts.getFirst().end());
    assertThat(parts.getFirst().section()).contains("第一条");
  }

  @Test
  void docxTablesAndParagraphsAreExtracted() throws Exception {
    try (var doc = new XWPFDocument();
        var out = new ByteArrayOutputStream()) {
      doc.createParagraph().createRun().setText("第一条 社区助餐服务办理规范，申请人应当填写需求申请表。");
      doc.createTable(1, 1).getRow(0).getCell(0).setText("委托办理需要核验授权说明。");
      doc.write(out);
      assertThat(parser.extract("test.docx", out.toByteArray())).contains("需求申请表", "授权说明");
    }
  }

  @Test
  void pdfPageBoundariesArePreserved() throws Exception {
    try (var doc = new PDDocument();
        var out = new ByteArrayOutputStream()) {
      for (int i = 0; i < 2; i++) {
        var page = new PDPage();
        doc.addPage(page);
        try (var stream = new PDPageContentStream(doc, page)) {
          stream.beginText();
          stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
          stream.newLineAtOffset(30, 700);
          stream.showText(
              "Community care policy original page " + (i + 1) + ". Requirements and process.");
          stream.endText();
        }
      }
      doc.save(out);
      String text = parser.extract("test.pdf", out.toByteArray());
      assertThat(text).contains("page 1", "page 2", "\f");
      assertThat(parser.split(text).getLast().page()).isEqualTo(2);
    }
  }

  @Test
  void rejectsInvalidAndScannedDocuments() {
    assertThatThrownBy(() -> parser.extract("a.exe", new byte[40])).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> parser.extract("a.txt", new byte[] {(byte) 0xff, (byte) 0xfe}))
        .isInstanceOf(Problem.class);
    assertThatThrownBy(() -> parser.extract("a.pdf", "not pdf".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(Problem.class);
    assertThatThrownBy(() -> parser.extract("a.txt", "短文".getBytes(StandardCharsets.UTF_8)))
        .hasMessageContaining("有效正文");
  }

  @Test
  void localVectorsAreNormalizedAndPreferRelatedText() {
    float[] a = Embeddings.local("社区助餐申请需要哪些材料"),
        b = Embeddings.local("助餐申请材料包括养老服务需求表"),
        c = Embeddings.local("Java虚拟机垃圾收集器");
    assertThat(dot(a, a)).isCloseTo(1, within(0.0001));
    assertThat(dot(a, b)).isGreaterThan(dot(a, c));
  }

  @Test
  void vectorIndexPersistsAndIsolatesCommunitiesAndSpaces() throws Exception {
    try (var store = new StoreClose(temp.toString(), json)) {
      store.v.replace(
          10,
          List.of(
              chunk(1, 10, 1, "助餐申请材料填写需求表", "local-zh-hash-v1"),
              chunk(2, 10, 2, "助餐申请材料隐私信息", "local-zh-hash-v1")));
      assertThat(
              store.v.search(
                  1, "助餐材料", Embeddings.local("助餐材料"), "local-zh-hash-v1", List.of(10L), 4))
          .containsExactly(1L);
      assertThat(
              store.v.search(1, "助餐材料", Embeddings.local("助餐材料"), "other-space", List.of(10L), 4))
          .isEmpty();
      assertThat(
              store.v.search(
                  1, "助餐材料", Embeddings.local("助餐材料"), "local-zh-hash-v1", List.of(11L), 4))
          .isEmpty();
    }
    try (var store = new StoreClose(temp.toString(), json)) {
      assertThat(
              store.v.search(
                  1, "助餐材料", Embeddings.local("助餐材料"), "local-zh-hash-v1", List.of(10L), 4))
          .containsExactly(1L);
      store.v.delete(10);
      assertThat(
              store.v.search(
                  1, "助餐材料", Embeddings.local("助餐材料"), "local-zh-hash-v1", List.of(10L), 4))
          .isEmpty();
    }
  }

  @Test
  void modelConfigRequiresHighestRoleAndPermission() {
    assertThatThrownBy(
            () ->
                AiModels.requireAdmin(
                    new Actor(
                        1,
                        1,
                        "grid",
                        Set.of("GRID_WORKER"),
                        Set.of("ai:model:manage", "system:config:manage"))))
        .isInstanceOf(Problem.class);
    assertThatThrownBy(
            () ->
                AiModels.requireAdmin(
                    new Actor(1, 1, "admin", Set.of("ADMIN"), Set.of("ai:model:manage"))))
        .isInstanceOf(Problem.class);
    assertThatCode(
            () ->
                AiModels.requireAdmin(
                    new Actor(
                        1,
                        1,
                        "admin",
                        Set.of("ADMIN"),
                        Set.of("ai:model:manage", "system:config:manage"))))
        .doesNotThrowAnyException();
  }

  @Test
  void validatesEndpointsAndRedactsProviderToString() {
    assertThatCode(() -> AiModels.validateUrl("https://api.deepseek.com"))
        .doesNotThrowAnyException();
    assertThatCode(() -> AiModels.validateUrl("http://127.0.0.1:1234/v1"))
        .doesNotThrowAnyException();
    for (String u :
        List.of(
            "file:///etc/passwd",
            "http://remote.example/v1",
            "https://user:password@example.com",
            "https://example.com?key=secret"))
      assertThatThrownBy(() -> AiModels.validateUrl(u)).isInstanceOf(Problem.class);
    assertThat(
            new AiModels.Provider(1, "OPENAI", "https://example.com", "model", "secret-key", 0)
                .toString())
        .doesNotContain("secret-key");
  }

  Map<String, Object> chunk(long id, long doc, long community, String text, String space) {
    return Db.p(
        "id",
        id,
        "document_id",
        doc,
        "community_id",
        community,
        "content",
        text,
        "summary",
        text,
        "keywords",
        "申请 材料",
        "embedding_json",
        json.write(Embeddings.local(text)),
        "embedding_space",
        space);
  }

  double dot(float[] a, float[] b) {
    double sum = 0;
    for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
    return sum;
  }

  record StoreClose(VectorStore v) implements AutoCloseable {
    StoreClose(String root, Json json) throws Exception {
      this(new VectorStore(root, json));
    }

    public void close() throws Exception {
      v.close();
    }
  }
}
