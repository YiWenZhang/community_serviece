package cn.community.identity;

import cn.community.common.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "02 用户角色与基础字典")
public class AdminController {
  private final Db db;
  private final Auth auth;
  private final Audit audit;
  private final PasswordEncoder encoder;
  private final Privacy privacy;

  public AdminController(Db db, Auth auth, Audit audit, PasswordEncoder encoder, Privacy privacy) {
    this.db = db;
    this.auth = auth;
    this.audit = audit;
    this.encoder = encoder;
    this.privacy = privacy;
  }

  public record UserCreate(
      @NotBlank @Pattern(regexp = "[a-zA-Z0-9_]{3,64}") String username,
      @NotBlank @Size(min = 12, max = 72) String password,
      @NotBlank @Size(max = 64) String displayName,
      @NotEmpty Set<Long> roleIds) {}

  public record UserChange(
      @Min(0) int version,
      @NotBlank @Size(max = 64) String displayName,
      @NotNull @Pattern(regexp = "ACTIVE|DISABLED") String status,
      @NotEmpty Set<Long> roleIds) {}

  public record Password(
      @Min(0) int version, @NotBlank @Size(min = 12, max = 72) String password) {}

  public record RoleChange(@NotEmpty Set<Long> permissionIds) {}

  @GetMapping("/users")
  @Operation(summary = "本社区工作人员列表，不返回密码散列")
  public Object users(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    var a = auth.require("system:user:manage");
    var result =
        db.page(
            "SELECT id,username,display_name,status,version,created_at FROM sys_user WHERE"
                + " community_id=:community ORDER BY id",
            "SELECT COUNT(*) FROM sys_user WHERE community_id=:community",
            a.scope(),
            page,
            pageSize);
    @SuppressWarnings("unchecked")
    var items = (List<Map<String, Object>>) result.get("items");
    for (var user : items) {
      user.put(
          "roleIds",
          db
              .list(
                  "SELECT role_id FROM sys_user_role WHERE user_id=:id ORDER BY role_id",
                  Db.p("id", user.get("id")))
              .stream()
              .map(r -> r.get("role_id"))
              .toList());
    }
    return Api.ok(result);
  }

  @PostMapping("/users")
  @Transactional
  @Operation(summary = "创建本社区应用用户及角色")
  public Object create(@Valid @RequestBody UserCreate b) {
    var a = auth.require("system:user:manage");
    privacy.check(b.displayName());
    validateRoles(b.roleIds());
    long id =
        db.insert(
            "sys_user",
            Db.p(
                "community_id",
                a.communityId(),
                "username",
                b.username(),
                "password_hash",
                encoder.encode(b.password()),
                "display_name",
                b.displayName(),
                "status",
                "ACTIVE",
                "created_by",
                a.id()));
    roles(id, b.roleIds());
    audit.log(a, "USER_CREATED", "USER", id, Map.of("roles", b.roleIds()));
    return Api.ok(Map.of("id", id));
  }

  @PatchMapping("/users/{id}")
  @Transactional
  @Operation(summary = "变更工作人员状态角色，旧会话失效")
  public Object change(@PathVariable long id, @Valid @RequestBody UserChange b) {
    var a = auth.require("system:user:manage");
    privacy.check(b.displayName());
    validateRoles(b.roleIds());
    db.one(
        "SELECT id FROM sys_user WHERE id=:id AND community_id=:community FOR UPDATE",
        Db.p("id", id, "community", a.communityId()));
    boolean staysAdmin =
        "ACTIVE".equals(b.status())
            && db.count(
                    "SELECT COUNT(*) FROM sys_role WHERE id IN (:ids) AND code='ADMIN'",
                    Db.p("ids", b.roleIds()))
                > 0;
    if (!staysAdmin)
      Problem.require(
          db.count(
                  "SELECT COUNT(DISTINCT u.id) FROM sys_user u JOIN sys_user_role ur ON"
                      + " ur.user_id=u.id JOIN sys_role r ON r.id=ur.role_id WHERE"
                      + " u.community_id=:community AND u.status='ACTIVE' AND r.code='ADMIN' AND"
                      + " u.id<>:id",
                  Db.p("community", a.communityId(), "id", id))
              > 0,
          "不能停用或移除最后一个有效管理员");
    Problem.changed(
        db.update(
            "UPDATE sys_user SET"
                + " display_name=:name,status=:status,version=version+1,updated_by=:actor WHERE"
                + " id=:id AND community_id=:community AND version=:v",
            Db.p(
                "name",
                b.displayName(),
                "status",
                b.status(),
                "actor",
                a.id(),
                "id",
                id,
                "community",
                a.communityId(),
                "v",
                b.version())));
    roles(id, b.roleIds());
    audit.log(a, "USER_CHANGED", "USER", id, Map.of("status", b.status(), "roles", b.roleIds()));
    return Api.ok(Map.of("id", id, "version", b.version() + 1));
  }

  @PostMapping("/users/{id}/password")
  @Transactional
  @Operation(summary = "管理员重置应用用户密码，不涉及MySQL密码")
  public Object password(@PathVariable long id, @Valid @RequestBody Password b) {
    var a = auth.require("system:user:manage");
    Problem.changed(
        db.update(
            "UPDATE sys_user SET"
                + " password_hash=:hash,failed_login_count=0,locked_until=NULL,version=version+1,updated_by=:actor"
                + " WHERE id=:id AND community_id=:community AND version=:v",
            Db.p(
                "hash",
                encoder.encode(b.password()),
                "actor",
                a.id(),
                "id",
                id,
                "community",
                a.communityId(),
                "v",
                b.version())));
    audit.log(a, "PASSWORD_RESET", "USER", id, Map.of());
    return Api.ok(Map.of("id", id, "version", b.version() + 1));
  }

  @GetMapping("/roles")
  @Operation(summary = "角色和权限关联")
  public Object roles() {
    auth.require("system:role:manage");
    var rows = db.list("SELECT id,code,name,status,version FROM sys_role ORDER BY id", Map.of());
    for (var row : rows)
      row.put(
          "permissionIds",
          db
              .list(
                  "SELECT permission_id FROM sys_role_permission WHERE role_id=:id",
                  Db.p("id", row.get("id")))
              .stream()
              .map(r -> r.get("permission_id"))
              .toList());
    return Api.ok(rows);
  }

  @GetMapping("/permissions")
  @Operation(summary = "全部权限字典")
  public Object permissions() {
    auth.require("system:role:manage");
    return Api.ok(
        db.list("SELECT id,code,name,resource_type FROM sys_permission ORDER BY id", Map.of()));
  }

  @PutMapping("/roles/{id}/permissions")
  @Transactional
  @Operation(summary = "替换角色权限，使该角色所有旧会话失效")
  public Object permissions(@PathVariable long id, @Valid @RequestBody RoleChange b) {
    var a = auth.require("system:role:manage");
    Problem.require(
        db.count("SELECT COUNT(*) FROM community WHERE status='ACTIVE'", Map.of()) == 1,
        "全局角色权限配置当前仅支持单社区部署");
    db.one("SELECT id FROM sys_role WHERE id=:id FOR UPDATE", Db.p("id", id));
    Problem.require(
        db.count(
                "SELECT COUNT(*) FROM sys_permission WHERE id IN (:ids)",
                Db.p("ids", b.permissionIds()))
            == b.permissionIds().size(),
        "权限编号无效");
    // ADMIN保留账号及角色管理，防止整个系统被配置为不可管理。
    if (db.count("SELECT COUNT(*) FROM sys_role WHERE id=:id AND code='ADMIN'", Db.p("id", id)) > 0)
      Problem.require(
          db.count(
                  "SELECT COUNT(*) FROM sys_permission WHERE id IN (:ids) AND code IN"
                      + " ('system:user:manage','system:role:manage')",
                  Db.p("ids", b.permissionIds()))
              == 2,
          "管理员必须保留用户与角色管理权限");
    db.update("DELETE FROM sys_role_permission WHERE role_id=:id", Db.p("id", id));
    for (long pid : b.permissionIds())
      db.insert(
          "sys_role_permission", Db.p("role_id", id, "permission_id", pid, "created_by", a.id()));
    db.update(
        "UPDATE sys_user SET version=version+1 WHERE id IN (SELECT user_id FROM sys_user_role WHERE"
            + " role_id=:id)",
        Db.p("id", id));
    audit.log(
        a, "ROLE_PERMISSIONS_CHANGED", "ROLE", id, Map.of("permissionIds", b.permissionIds()));
    return Api.ok(Map.of("id", id));
  }

  @GetMapping("/dictionaries")
  @Operation(summary = "本社区网格与需求疫苗字典")
  public Object dictionaries() {
    var a = auth.current();
    return Api.ok(
        Db.p(
            "grids",
            db.list(
                "SELECT id,code,name FROM grid WHERE community_id=:community AND status='ACTIVE'",
                a.scope()),
            "serviceNeeds",
            db.list("SELECT id,code,name FROM service_need WHERE status='ACTIVE'", Map.of()),
            "vaccineProducts",
            db.list("SELECT id,code,name FROM vaccine_product WHERE status='ACTIVE'", Map.of()),
            "vaccineCampaigns",
            db.list("SELECT * FROM vaccine_campaign WHERE community_id=:community", a.scope())));
  }

  @GetMapping("/audit-logs")
  @Operation(summary = "授权查看本社区脱敏审计")
  public Object logs(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    var a = auth.require("audit:read");
    return Api.ok(
        db.page(
            "SELECT"
                + " id,actor_user_id,system_actor,action,target_type,target_id,trace_id,result,change_summary_json,occurred_at"
                + " FROM audit_log WHERE community_id=:community ORDER BY occurred_at DESC,id DESC",
            "SELECT COUNT(*) FROM audit_log WHERE community_id=:community",
            a.scope(),
            page,
            pageSize));
  }

  private void validateRoles(Set<Long> ids) {
    Problem.require(
        db.count(
                "SELECT COUNT(*) FROM sys_role WHERE id IN (:ids) AND status='ACTIVE'",
                Db.p("ids", ids))
            == ids.size(),
        "角色无效");
  }

  private void roles(long uid, Set<Long> ids) {
    db.update("DELETE FROM sys_user_role WHERE user_id=:id", Db.p("id", uid));
    for (long id : ids) db.insert("sys_user_role", Db.p("user_id", uid, "role_id", id));
  }
}
