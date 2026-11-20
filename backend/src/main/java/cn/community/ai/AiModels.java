package cn.community.ai;

import cn.community.common.*;
import cn.community.identity.Actor;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiModels {
  public record Draft(
      @NotBlank @Size(max = 80) String name,
      @NotBlank @Pattern(regexp = "CHAT|EMBEDDING") String kind,
      @NotBlank @Pattern(regexp = "OPENAI|ANTHROPIC") String apiFormat,
      @NotBlank @Size(max = 500) String baseUrl,
      @NotBlank @Size(max = 160) String model,
      @Size(max = 512) String apiKey,
      @Min(0) int version) {}

  public record Provider(
      long id, String format, String baseUrl, String model, String key, int version) {
    // 防止record自动生成的toString泄露密钥。
    @Override
    public String toString() {
      return "Provider[id=" + id + ", model=" + model + "]";
    }
  }

  private final Db db;
  private final Crypto crypto;
  private final Audit audit;

  public AiModels(Db db, Crypto crypto, Audit audit) {
    this.db = db;
    this.crypto = crypto;
    this.audit = audit;
  }

  public static void requireAdmin(Actor a) {
    a.require("ai:model:manage");
    if (!a.roles().contains("ADMIN") || !a.has("system:config:manage"))
      throw new Problem(403, "仅最高权限管理员可维护模型配置");
  }

  public Object list(Actor a) {
    requireAdmin(a);
    return db.list(
        "SELECT id,name,kind,api_format,base_url,model,active,version,(api_key_cipher IS NOT NULL)"
            + " AS has_key,updated_at FROM ai_provider WHERE community_id=:community ORDER BY"
            + " kind,id",
        a.scope());
  }

  @Transactional
  public Object save(Actor a, Long id, Draft b) {
    requireAdmin(a);
    validateUrl(b.baseUrl());
    Problem.require(
        !b.kind().equals("EMBEDDING") || b.apiFormat().equals("OPENAI"),
        "向量模型使用OpenAI Embeddings兼容格式");
    var f =
        Db.p(
            "community_id",
            a.communityId(),
            "name",
            b.name().trim(),
            "kind",
            b.kind(),
            "api_format",
            b.apiFormat(),
            "base_url",
            b.baseUrl().replaceAll("/+$", ""),
            "model",
            b.model().trim());
    if (id == null) {
      Problem.require(b.apiKey() != null && !b.apiKey().isBlank(), "请填写模型密钥");
      f.put("api_key_cipher", crypto.encrypt(b.apiKey().trim()));
      f.put("created_by", a.id());
      id = db.insert("ai_provider", f);
    } else {
      var old = owned(a, id);
      Problem.require(old.get("kind").equals(b.kind()), "模型用途创建后不可修改");
      Problem.require(
          !Boolean.TRUE.equals(old.get("active")) && !"1".equals(old.get("active").toString()),
          "请先切换到其他配置或停用当前配置，再编辑");
      f.put("id", id);
      f.put("v", b.version());
      if (b.apiKey() != null && !b.apiKey().isBlank())
        f.put("api_key_cipher", crypto.encrypt(b.apiKey().trim()));
      Problem.changed(
          db.update(
              "UPDATE ai_provider SET"
                  + " name=:name,api_format=:api_format,base_url=:base_url,model=:model,version=version+1"
                  + (f.containsKey("api_key_cipher") ? ",api_key_cipher=:api_key_cipher" : "")
                  + " WHERE id=:id AND community_id=:community_id AND version=:v",
              f));
    }
    audit.log(a, "AI_PROVIDER_SAVED", "AI_PROVIDER", id, Map.of("kind", b.kind()));
    return Map.of("id", id);
  }

  public Map<String, Object> owned(Actor a, long id) {
    return db.one(
        "SELECT * FROM ai_provider WHERE id=:id AND community_id=:community",
        Db.p("id", id, "community", a.communityId()));
  }

  @Transactional
  public Object activate(Actor a, long id, boolean active) {
    requireAdmin(a);
    // 社区行锁确保同一用途只启用一个配置。
    db.one("SELECT id FROM community WHERE id=:id FOR UPDATE", Db.p("id", a.communityId()));
    var row = owned(a, id);
    if (active)
      db.update(
          "UPDATE ai_provider SET active=0,version=version+1 WHERE community_id=:community AND"
              + " kind=:kind AND active=1",
          Db.p("community", a.communityId(), "kind", row.get("kind")));
    db.update(
        "UPDATE ai_provider SET active=:active,version=version+1 WHERE id=:id",
        Db.p("id", id, "active", active));
    audit.log(a, "AI_PROVIDER_SWITCHED", "AI_PROVIDER", id, Map.of("active", active));
    return Map.of("id", id, "active", active);
  }

  @Transactional
  public void delete(Actor a, long id) {
    requireAdmin(a);
    var r = owned(a, id);
    Problem.require(!truth(r.get("active")), "请先停用配置");
    db.update("DELETE FROM ai_provider WHERE id=:id", Db.p("id", id));
    audit.log(a, "AI_PROVIDER_DELETED", "AI_PROVIDER", id, Map.of());
  }

  public Provider active(long community, String kind) {
    return db.optional(
            "SELECT * FROM ai_provider WHERE community_id=:community AND kind=:kind AND active=1",
            Db.p("community", community, "kind", kind))
        .map(this::provider)
        .orElse(null);
  }

  public Provider chat(long community) {
    var p = active(community, "CHAT");
    if (p == null) throw new Problem(422, "请先由最高管理员配置并启用一个对话模型");
    return p;
  }

  public Provider provider(Map<String, Object> r) {
    return new Provider(
        Db.id(r, "id"),
        r.get("api_format").toString(),
        r.get("base_url").toString(),
        r.get("model").toString(),
        crypto.decrypt((byte[]) r.get("api_key_cipher")),
        ((Number) r.get("version")).intValue());
  }

  public static boolean truth(Object o) {
    return Boolean.TRUE.equals(o) || "1".equals(String.valueOf(o));
  }

  public static void validateUrl(String url) {
    try {
      URI u = URI.create(url);
      Problem.require(
          u.getHost() != null
              && u.getRawUserInfo() == null
              && u.getRawQuery() == null
              && u.getRawFragment() == null,
          "API地址不可包含账号、查询参数或片段");
      Problem.require(
          "https".equals(u.getScheme())
              || ("http".equals(u.getScheme())
                  && Set.of("localhost", "127.0.0.1", "[::1]").contains(u.getHost())),
          "API地址须为HTTPS，本地服务可用HTTP");
    } catch (IllegalArgumentException e) {
      throw new Problem(422, "API地址格式不正确");
    }
  }
}
