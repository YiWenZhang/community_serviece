package cn.community.ai;

import cn.community.common.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

@Component
@Order(-100)
public class AiBootstrap implements ApplicationRunner {
  private final DataSource source;
  private final Db db;
  private final Crypto crypto;
  private final VectorStore vectors;
  private final boolean migrate;
  private final String initialKey;

  public AiBootstrap(
      DataSource source,
      Db db,
      Crypto crypto,
      VectorStore vectors,
      @Value("${app.ai.migrate:true}") boolean migrate,
      @Value("${app.ai.initial-key:}") String initialKey) {
    this.source = source;
    this.db = db;
    this.crypto = crypto;
    this.vectors = vectors;
    this.migrate = migrate;
    this.initialKey = initialKey;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    if (migrate)
      new ResourceDatabasePopulator(new ClassPathResource("ai-schema.sql")).execute(source);
    // 环境密钥仅用于首次创建，绝不覆盖管理员已经保存的配置。
    if (!initialKey.isBlank()) {
      for (var a :
          db.list(
              "SELECT u.id,u.community_id FROM sys_user u WHERE u.username='admin_demo'",
              Map.of())) {
        if (db.count(
                "SELECT COUNT(*) FROM ai_provider WHERE community_id=:community AND kind='CHAT'",
                Db.p("community", a.get("community_id")))
            == 0)
          db.insert(
              "ai_provider",
              Db.p(
                  "community_id",
                  a.get("community_id"),
                  "name",
                  "DeepSeek Flash",
                  "kind",
                  "CHAT",
                  "api_format",
                  "OPENAI",
                  "base_url",
                  "https://api.deepseek.com",
                  "model",
                  "deepseek-flash",
                  "api_key_cipher",
                  crypto.encrypt(initialKey),
                  "active",
                  true,
                  "created_by",
                  a.get("id")));
      }
    }
    vectors.rebuild(
        db.list(
            "SELECT c.* FROM ai_chunk c JOIN ai_document d ON d.id=c.document_id WHERE d.status IN"
                + " ('READY','DISABLED')",
            Map.of()));
  }
}
