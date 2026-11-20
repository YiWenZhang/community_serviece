package cn.community.identity;

import cn.community.common.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "01 登录与会话")
public class AuthController {
  public record Login(
      @NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 128) String password) {}

  private final Db db;
  private final PasswordEncoder encoder;
  private final Auth auth;
  private final Audit audit;
  private final HttpSessionSecurityContextRepository repo;
  private final TransactionTemplate tx;
  private final org.springframework.security.web.csrf.CsrfTokenRepository csrf;
  private final PasswordService passwords;

  public AuthController(
      Db db,
      PasswordEncoder encoder,
      Auth auth,
      Audit audit,
      HttpSessionSecurityContextRepository repo,
      TransactionTemplate tx,
      org.springframework.security.web.csrf.CsrfTokenRepository csrf,
      PasswordService passwords) {
    this.db = db;
    this.encoder = encoder;
    this.auth = auth;
    this.audit = audit;
    this.repo = repo;
    this.tx = tx;
    this.csrf = csrf;
    this.passwords = passwords;
  }

  @GetMapping("/csrf")
  @Operation(summary = "获取CSRF令牌 登录前和登录后分别获取")
  @io.swagger.v3.oas.annotations.security.SecurityRequirements
  public Object csrf(@io.swagger.v3.oas.annotations.Parameter(hidden = true) CsrfToken token) {
    return Api.ok(Db.p("headerName", token.getHeaderName(), "token", token.getToken()));
  }

  @PostMapping("/login")
  @Operation(summary = "JSON登录，Cookie会话与CSRF防护")
  @io.swagger.v3.oas.annotations.security.SecurityRequirements
  public Object login(
      @Valid @RequestBody Login body, HttpServletRequest request, HttpServletResponse response) {
    var user =
        tx.execute(
            s -> {
              var found =
                  db.optional(
                      "SELECT *,CASE WHEN locked_until>UTC_TIMESTAMP(3) THEN 1 ELSE 0 END AS"
                          + " password_locked FROM sys_user WHERE username=:u FOR UPDATE",
                      Db.p("u", body.username()));
              if (found.isEmpty()) {
                audit.system("LOGIN_FAILED", "FAILED");
                return null;
              }
              var u = found.get();
              boolean locked = ((Number) u.get("password_locked")).intValue() == 1;
              if (locked) {
                audit.system("LOGIN_FAILED", "FAILED");
                return null;
              }
              String hash = u.get("password_hash").toString();
              boolean valid =
                  !locked
                      && "ACTIVE".equals(u.get("status"))
                      && hash.startsWith("$2")
                      && encoder.matches(body.password(), hash);
              if (!valid) {
                db.update(
                    "UPDATE sys_user SET failed_login_count=failed_login_count+1,"
                        + " locked_until=IF(failed_login_count>=5,UTC_TIMESTAMP(3)+INTERVAL 15"
                        + " MINUTE,locked_until) WHERE id=:id",
                    Db.p("id", u.get("id")));
                audit.system("LOGIN_FAILED", "FAILED");
                return null;
              }
              db.update(
                  "UPDATE sys_user SET"
                      + " failed_login_count=0,locked_until=NULL,last_login_at=UTC_TIMESTAMP(3)"
                      + " WHERE id=:id",
                  Db.p("id", u.get("id")));
              return u;
            });
    if (user == null) throw new Problem(401, "账号密码无效、账号停用或暂时锁定");
    if (request.getSession(false) != null) request.changeSessionId();
    var session = request.getSession(true);
    session.setAttribute("userVersion", user.get("version"));
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            user.get("id").toString(), null, List.of(new SimpleGrantedAuthority("AUTHENTICATED"))));
    SecurityContextHolder.setContext(context);
    repo.saveContext(context, request, response);
    csrf.saveToken(null, request, response);
    var actor = auth.current();
    audit.log(actor, "LOGIN", "USER", actor.id(), Map.of());
    return me();
  }

  @GetMapping("/me")
  @Operation(summary = "当前用户、角色、权限与社区")
  public Object me() {
    var a = auth.current();
    return Api.ok(
        Db.p(
            "id",
            a.id(),
            "username",
            a.username(),
            "communityId",
            a.communityId(),
            "communities",
            auth.communities(a),
            "roles",
            a.roles(),
            "permissions",
            a.permissions().stream().filter(a::has).toList()));
  }

  @PostMapping("/logout")
  @Operation(summary = "注销并销毁会话")
  public Object logout(HttpServletRequest req, HttpServletResponse response) {
    var a = auth.current();
    audit.log(a, "LOGOUT", "USER", a.id(), Map.of());
    if (req.getSession(false) != null) req.getSession(false).invalidate();
    csrf.saveToken(null, req, response);
    SecurityContextHolder.clearContext();
    return Api.ok(Map.of("loggedOut", true));
  }

  public record PasswordChange(
      @NotBlank @Size(max = 128) String currentPassword,
      @NotBlank @Size(min = 6, max = 72) String newPassword,
      @NotBlank @Size(max = 72) String confirmPassword) {}

  @GetMapping("/password-status")
  public Object passwordStatus() {
    return Api.ok(passwords.status(auth.current()));
  }

  @PostMapping("/password")
  @Operation(summary = "本人修改密码，核验当前密码；成功后所有旧会话失效")
  public Object changePassword(
      @Valid @RequestBody PasswordChange b, HttpServletRequest req, HttpServletResponse res) {
    passwords.change(auth.current(), b.currentPassword(), b.newPassword(), b.confirmPassword());
    if (req.getSession(false) != null) req.getSession(false).invalidate();
    csrf.saveToken(null, req, res);
    SecurityContextHolder.clearContext();
    return Api.ok(Map.of("changed", true, "requireLogin", true));
  }
}
