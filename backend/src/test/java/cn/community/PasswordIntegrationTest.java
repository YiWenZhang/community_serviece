package cn.community;

import static org.assertj.core.api.Assertions.*;

import cn.community.common.*;
import cn.community.identity.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
class PasswordIntegrationTest {
  @Autowired Db db;
  @Autowired Auth auth;
  @Autowired PasswordService passwords;
  @Autowired PasswordEncoder encoder;
  Actor actor;

  @BeforeEach
  void prepare() {
    assertThat(db.one("SELECT DATABASE() AS db", Map.of()).get("db").toString())
        .matches(".*(_test|_regression)$");
    db.update(
        "UPDATE sys_user SET"
            + " status='ACTIVE',password_hash=:hash,failed_login_count=0,locked_until=NULL WHERE"
            + " id=3",
        Db.p("hash", encoder.encode("123456")));
    actor = auth.worker(3);
  }

  @Test
  void statisticianCanChangeOwnPasswordWithSixCharactersAndRevokesSessionVersion() {
    int version =
        ((Number) db.one("SELECT version FROM sys_user WHERE id=3", Map.of()).get("version"))
            .intValue();
    passwords.change(actor, "123456", "654321", "654321");
    var row = db.one("SELECT password_hash,version FROM sys_user WHERE id=3", Map.of());
    assertThat(encoder.matches("654321", row.get("password_hash").toString())).isTrue();
    assertThat(row.get("password_hash").toString()).doesNotContain("654321");
    assertThat(((Number) row.get("version")).intValue()).isEqualTo(version + 1);
    assertThat(((Map<?, ?>) passwords.status(actor)).get("defaultPassword")).isEqualTo(false);
    assertThat(
            db.list(
                    "SELECT change_summary_json FROM audit_log WHERE action='PASSWORD_CHANGED' AND"
                        + " actor_user_id=3",
                    Map.of())
                .toString())
        .doesNotContain("654321", "123456");
  }

  @Test
  void wrongCurrentAndConfirmationDoNotChangeHashAndFailuresLock() {
    assertThatThrownBy(() -> passwords.change(actor, "123456", "abcdef", "different"))
        .isInstanceOf(Problem.class);
    for (int i = 0; i < 5; i++)
      assertThatThrownBy(() -> passwords.change(actor, "badpass", "abcdef", "abcdef"))
          .isInstanceOfSatisfying(Problem.class, e -> assertThat(e.status).isEqualTo(422));
    assertThatThrownBy(() -> passwords.change(actor, "123456", "abcdef", "abcdef"))
        .isInstanceOfSatisfying(Problem.class, e -> assertThat(e.status).isEqualTo(429));
    assertThat(
            encoder.matches(
                "123456",
                db.one("SELECT password_hash FROM sys_user WHERE id=3", Map.of())
                    .get("password_hash")
                    .toString()))
        .isTrue();
  }
}
