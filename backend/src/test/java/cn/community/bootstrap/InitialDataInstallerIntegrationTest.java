package cn.community.bootstrap;

import static org.assertj.core.api.Assertions.*;

import cn.community.common.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@EnabledIfEnvironmentVariable(named = "RUN_INITIAL_DATA_TESTS", matches = "true")
class InitialDataInstallerIntegrationTest {
  @Test
  void importIsCompleteRollsBackOnFailureAndCanReplaceItself() throws Exception {
    var source =
        new DriverManagerDataSource(
            System.getenv("DB_URL"),
            System.getenv("DB_USERNAME"),
            System.getenv().getOrDefault("DB_PASSWORD", ""));
    var db = new Db(new NamedParameterJdbcTemplate(source));
    assertThat(db.one("SELECT DATABASE() AS d", Map.of()).get("d").toString()).endsWith("_test");
    Path project = Path.of(System.getenv("IMPORT_PROJECT_ROOT")),
        storage = Path.of(System.getenv("APP_STORAGE"));
    var installer = new InitialDataInstaller(project);
    installer.validatePackage();
    installer.validateSchema();
    long originalElders = db.count("SELECT COUNT(*) FROM elder_profile", Map.of());
    installer.upgradePrivateSchema();
    installer.upgradePrivateSchema();
    assertThat(db.count("SELECT COUNT(*) FROM elder_profile", Map.of())).isEqualTo(originalElders);
    installer.install();
    assertThat(db.count("SELECT COUNT(*) FROM elder_profile", Map.of())).isEqualTo(20);
    assertThat(
            db.count(
                "SELECT COUNT(*) FROM elder_profile WHERE personal_info_cipher IS NOT NULL AND"
                    + " name_hash IS NOT NULL AND phone_hash IS NOT NULL",
                Map.of()))
        .isEqualTo(20);
    assertThat(db.count("SELECT COUNT(*) FROM notice", Map.of())).isEqualTo(26);
    assertThat(db.count("SELECT COUNT(*) FROM notice_recipient", Map.of())).isEqualTo(52);
    assertThat(
            db.count(
                "SELECT COUNT(*) FROM notice n WHERE NOT EXISTS (SELECT 1 FROM sys_user_role ur"
                    + " JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=n.created_by AND"
                    + " r.code='GRID_WORKER')",
                Map.of()))
        .isZero();
    assertThat(
            db.count(
                "SELECT COUNT(*) FROM notice_recipient r JOIN notice n ON n.id=r.notice_id WHERE"
                    + " n.created_by<>r.owner_at_issue OR n.community_id<>r.community_id",
                Map.of()))
        .isZero();
    assertThat(db.count("SELECT COUNT(*) FROM followup_record", Map.of())).isEqualTo(4);
    assertThat(
            db.count(
                "SELECT COUNT(*) FROM ai_document WHERE status='READY' AND is_demo=0", Map.of()))
        .isEqualTo(32);
    var encoder = new BCryptPasswordEncoder(12);
    for (var user : db.list("SELECT password_hash FROM sys_user", Map.of()))
      assertThat(encoder.matches("123456", user.get("password_hash").toString())).isTrue();
    long oldVersion = db.count("SELECT MAX(version) FROM sys_user", Map.of());
    Files.writeString(storage.resolve("rollback-marker.txt"), "previous storage");
    db.update("UPDATE sys_user SET display_name='回滚验证专用' WHERE username='grid5'", Map.of());
    db.update(
        "ALTER TABLE sys_user ADD CONSTRAINT initial_data_rollback_guard CHECK(username<>'grid5' OR"
            + " display_name<>'孙悦')",
        Map.of());
    try {
      assertThatThrownBy(() -> new InitialDataInstaller(project).install())
          .isInstanceOf(RuntimeException.class);
      assertThat(db.count("SELECT COUNT(*) FROM elder_profile", Map.of())).isEqualTo(20);
      assertThat(db.count("SELECT MAX(version) FROM sys_user", Map.of())).isEqualTo(oldVersion);
      assertThat(db.one("SELECT display_name FROM sys_user WHERE username='grid5'", Map.of()))
          .containsEntry("display_name", "回滚验证专用");
      assertThat(Files.readString(storage.resolve("rollback-marker.txt")))
          .isEqualTo("previous storage");
    } finally {
      db.update("ALTER TABLE sys_user DROP CHECK initial_data_rollback_guard", Map.of());
    }
    new InitialDataInstaller(project).install();
    assertThat(Files.exists(storage.resolve("rollback-marker.txt"))).isFalse();
    assertThat(db.count("SELECT MAX(version) FROM sys_user", Map.of())).isGreaterThan(oldVersion);
    assertThat(db.count("SELECT COUNT(*) FROM elder_profile", Map.of())).isEqualTo(20);
    assertThat(db.count("SELECT COUNT(*) FROM ai_document", Map.of())).isEqualTo(32);
    assertThat(db.count("SELECT COUNT(*) FROM sys_user WHERE username LIKE '%demo%'", Map.of()))
        .isZero();
    assertThat(
            db.count(
                "SELECT COUNT(*) FROM elder_profile e LEFT JOIN user_community u ON"
                    + " e.owner_user_id=u.user_id AND e.community_id=u.community_id WHERE u.user_id"
                    + " IS NULL",
                Map.of()))
        .isZero();
  }
}
