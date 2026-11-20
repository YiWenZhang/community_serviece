package cn.community.ai;

import cn.community.common.*;
import cn.community.identity.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/ai")
@Tag(name = "09 政策知识库与AI问答")
public class AiController {
  private final Auth auth;
  private final AiModels models;
  private final AiClient client;
  private final KnowledgeService knowledge;
  private final RagService rag;

  public AiController(
      Auth auth, AiModels models, AiClient client, KnowledgeService knowledge, RagService rag) {
    this.auth = auth;
    this.models = models;
    this.client = client;
    this.knowledge = knowledge;
    this.rag = rag;
  }

  public record Toggle(boolean active) {}

  public record Change(
      @NotBlank @Pattern(regexp = "retry|enable|disable|delete") String action,
      @Min(0) int version) {}

  public record Question(
      @NotBlank @Size(max = 2000) String question,
      @NotBlank @Pattern(regexp = "[0-9a-fA-F-]{36}") String requestId) {}

  @GetMapping("/providers")
  public Object providers() {
    return Api.ok(models.list(auth.current()));
  }

  @PostMapping("/providers")
  public Object createProvider(@Valid @RequestBody AiModels.Draft b) {
    return Api.ok(models.save(auth.current(), null, b));
  }

  @PutMapping("/providers/{id}")
  public Object saveProvider(@PathVariable long id, @Valid @RequestBody AiModels.Draft b) {
    return Api.ok(models.save(auth.current(), id, b));
  }

  @PostMapping("/providers/{id}/activation")
  public Object activate(@PathVariable long id, @RequestBody Toggle b) {
    return Api.ok(models.activate(auth.current(), id, b.active()));
  }

  @DeleteMapping("/providers/{id}")
  public Object deleteProvider(@PathVariable long id) {
    models.delete(auth.current(), id);
    return Api.ok(Map.of("deleted", true));
  }

  @PostMapping("/providers/{id}/test")
  public Object test(@PathVariable long id) {
    var a = auth.current();
    AiModels.requireAdmin(a);
    var row = models.owned(a, id);
    var p = models.provider(row);
    if ("EMBEDDING".equals(row.get("kind")))
      return Api.ok(Map.of("ok", true, "dimensions", client.embed(p, "社区养老政策").length));
    var result = client.structured(p, "只输出JSON：{\"ok\":true}", "请测试连接，按给定JSON返回。");
    Problem.require(Boolean.TRUE.equals(result.get("ok")), "模型未按要求返回结构化JSON");
    return Api.ok(Map.of("ok", true));
  }

  @GetMapping("/status")
  public Object status() {
    return Api.ok(rag.status(auth.current()));
  }

  @GetMapping("/documents")
  public Object documents(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(defaultValue = "") String q) {
    Problem.require(q.length() <= 200, "搜索内容过长");
    return Api.ok(knowledge.list(auth.current(), page, pageSize, q));
  }

  @GetMapping("/documents/{id}")
  public Object detail(@PathVariable long id) {
    return Api.ok(knowledge.detail(auth.current(), id));
  }

  @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Object upload(
      @RequestPart MultipartFile file,
      @RequestParam String title,
      @RequestParam(defaultValue = "OTHER") String category,
      @RequestParam(defaultValue = "") String issuer,
      @RequestParam(defaultValue = "") String policyNo,
      @RequestParam(required = false) LocalDate effectiveDate,
      @RequestParam(required = false) LocalDate expiryDate,
      @RequestParam(defaultValue = "false") boolean isDemo)
      throws Exception {
    return Api.ok(
        knowledge.upload(
            auth.current(),
            file,
            title,
            category,
            issuer,
            policyNo,
            effectiveDate,
            expiryDate,
            isDemo));
  }

  @PostMapping("/documents/{id}/action")
  public Object action(@PathVariable long id, @Valid @RequestBody Change b) {
    return Api.ok(knowledge.change(auth.current(), id, b.action(), b.version()));
  }

  @GetMapping("/documents/{id}/content")
  public ResponseEntity<byte[]> content(@PathVariable long id) throws Exception {
    var a = auth.current();
    byte[] content = knowledge.original(a, id);
    var doc = knowledge.owned(a, id);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(doc.get("safe_name").toString(), java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(content);
  }

  @GetMapping("/conversations")
  public Object conversations() {
    return Api.ok(rag.conversations(auth.current()));
  }

  @PostMapping("/conversations")
  public Object create() {
    return Api.ok(rag.create(auth.current()));
  }

  @DeleteMapping("/conversations/{id}")
  public Object delete(@PathVariable long id) {
    rag.delete(auth.current(), id);
    return Api.ok(Map.of("deleted", true));
  }

  @GetMapping("/conversations/{id}/messages")
  public Object messages(@PathVariable long id) {
    return Api.ok(rag.messages(auth.current(), id));
  }

  @PostMapping("/conversations/{id}/messages")
  public Object ask(@PathVariable long id, @Valid @RequestBody Question b) {
    return Api.ok(rag.ask(auth.current(), id, b.question().trim(), b.requestId()));
  }
}
