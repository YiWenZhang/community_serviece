package cn.community.config;

import cn.community.common.*;
import cn.community.identity.Passwords;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 初始化原SQL中尚未设置密码的示例账号；只启用管理员，不覆盖已有密码。 */
@Component
public class Bootstrap implements ApplicationRunner {
  private final Db db;
  private final PasswordEncoder encoder;

  public Bootstrap(Db db, PasswordEncoder encoder) {
    this.db = db;
    this.encoder = encoder;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    db.update(
        "UPDATE sys_user SET password_hash=:hash,status='ACTIVE',version=version+1 WHERE"
            + " username='admin_demo' AND status='DISABLED' AND"
            + " password_hash='!DISABLED_DEMO_ACCOUNT_NO_LOGIN'",
        Db.p("hash", encoder.encode(Passwords.INITIAL)));
    db.update(
        "UPDATE sys_user SET password_hash=:hash,version=version+1 WHERE username IN"
            + " ('grid_demo','stats_demo') AND password_hash='!DISABLED_DEMO_ACCOUNT_NO_LOGIN'",
        Db.p("hash", encoder.encode(Passwords.INITIAL)));
  }
}
