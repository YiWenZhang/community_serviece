package cn.community.config;

import cn.community.common.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 只激活原SQL中的禁用管理员一次，绝不重置已有活跃管理员密码。 */
@Component
public class Bootstrap implements ApplicationRunner {
  private final Db db;
  private final PasswordEncoder encoder;
  private final String password;

  public Bootstrap(
      Db db, PasswordEncoder encoder, @Value("${app.bootstrap-password}") String password) {
    this.db = db;
    this.encoder = encoder;
    this.password = password;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (password.isBlank()) return;
    Problem.require(password.length() >= 12 && password.length() <= 72, "初始化管理员密码需12至72个字符");
    db.update(
        "UPDATE sys_user SET password_hash=:hash,status='ACTIVE',version=version+1 WHERE"
            + " username='admin_demo' AND status='DISABLED' AND"
            + " password_hash='!DISABLED_DEMO_ACCOUNT_NO_LOGIN'",
        Db.p("hash", encoder.encode(password)));
  }
}
