package cn.community.config;

import cn.community.common.Db;
import java.util.Map;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(-90)
public class CommunitySchemaCheck implements ApplicationRunner {
  private final Db db;

  public CommunitySchemaCheck(Db db) {
    this.db = db;
  }

  public void run(ApplicationArguments args) {
    if (db.count(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND"
                    + " table_name='app_schema_version'",
                Map.of())
            == 0
        || db.count("SELECT COUNT(*) FROM app_schema_version WHERE version=2", Map.of()) == 0)
      throw new IllegalStateException(
          "请先备份并导入 database/migrations/V002__multi_community.sql，参阅 多社区权限升级说明.md；不要重新导入初始化数据库");
  }
}
