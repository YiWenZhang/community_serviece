package cn.community.identity;

import cn.community.common.*;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PasswordService {
  private final Db db;
  private final PasswordEncoder encoder;
  private final Audit audit;
  private final TransactionTemplate tx;

  public PasswordService(Db db, PasswordEncoder encoder, Audit audit, TransactionTemplate tx) {
    this.db = db;
    this.encoder = encoder;
    this.audit = audit;
    this.tx = tx;
  }

  public Object status(Actor a) {
    var row = db.one("SELECT password_hash FROM sys_user WHERE id=:id", Db.p("id", a.id()));
    String hash = row.get("password_hash").toString();
    return Db.p(
        "defaultPassword",
        hash.startsWith("$2") && encoder.matches(Passwords.INITIAL, hash),
        "minLength",
        6,
        "maxUtf8Bytes",
        72);
  }

  public void change(Actor a, String current, String next, String confirm) {
    Passwords.validate(next);
    Problem.require(next.equals(confirm), "两次输入的新密码不一致");
    Problem.require(!next.equals(current), "新密码不能与当前密码相同");
    String outcome =
        tx.execute(
            s -> {
              var row =
                  db.one(
                      "SELECT password_hash,CASE WHEN locked_until>UTC_TIMESTAMP(3) THEN 1 ELSE 0"
                          + " END AS password_locked FROM sys_user WHERE id=:id AND status='ACTIVE'"
                          + " FOR UPDATE",
                      Db.p("id", a.id()));
              if (((Number) row.get("password_locked")).intValue() == 1) return "LOCKED";
              String hash = row.get("password_hash").toString();
              boolean matches = false;
              try {
                matches = hash.startsWith("$2") && encoder.matches(current, hash);
              } catch (IllegalArgumentException ignored) {
              }
              if (!matches) {
                db.update(
                    "UPDATE sys_user SET"
                        + " failed_login_count=failed_login_count+1,locked_until=IF(failed_login_count>=5,UTC_TIMESTAMP(3)+INTERVAL"
                        + " 15 MINUTE,locked_until) WHERE id=:id",
                    Db.p("id", a.id()));
                return "WRONG";
              }
              db.update(
                  "UPDATE sys_user SET"
                      + " password_hash=:hash,failed_login_count=0,locked_until=NULL,version=version+1,updated_by=:id"
                      + " WHERE id=:id",
                  Db.p("hash", encoder.encode(next), "id", a.id()));
              audit.log(
                  a,
                  "PASSWORD_CHANGED",
                  "USER",
                  a.id(),
                  Map.of("strength", Passwords.strength(next)));
              return "OK";
            });
    if ("LOCKED".equals(outcome)) throw new Problem(429, "密码验证暂时锁定，请15分钟后重试或联系管理员重置");
    if (!"OK".equals(outcome)) throw new Problem(422, "当前密码不正确");
  }
}
