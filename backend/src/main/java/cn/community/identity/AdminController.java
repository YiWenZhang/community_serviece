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
      @Size(max = 72) String password,
      @NotBlank @Size(max = 64) String displayName,
      @NotEmpty Set<Long> roleIds,
      Set<Long> communityIds) {
    public UserCreate(String username, String password, String displayName, Set<Long> roleIds) {
      this(username, password, displayName, roleIds, null);
    }
  }

  public record UserChange(
      @Min(0) int version,
      @NotBlank @Size(max = 64) String displayName,
      @NotNull @Pattern(regexp = "ACTIVE|DISABLED") String status,
      @NotEmpty Set<Long> roleIds,
      Set<Long> communityIds) {
    public UserChange(int version, String displayName, String status, Set<Long> roleIds) {
      this(version, displayName, status, roleIds, null);
    }
  }

  public record Password(@Min(0) int version, @Size(max = 72) String password) {}

  public record RoleChange(@NotEmpty Set<Long> permissionIds) {}

  @GetMapping("/users")
  @Operation(summary = "本社区工作人员列表，不返回密码散列")
  public Object users(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    var a = auth.require("system:user:manage");
    var result =
        db.page(
            "SELECT id,username,display_name,status,version,created_at FROM sys_user ORDER BY id",
            "SELECT COUNT(*) FROM sys_user",
            a.scope(),
            page,
            pageSize);
    @SuppressWarnings("unchecked")
    var items = (List<Map<String, Object>>) result.get("items");
    for (var user : items) {
      user.put(
          "communityIds",
          db
              .list(
                  "SELECT community_id FROM user_community WHERE user_id=:id AND status='ACTIVE'",
                  Db.p("id", user.get("id")))
              .stream()
              .map(r -> r.get("community_id"))
              .toList());
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
    privacy.check(b.username());
    privacy.check(b.displayName());
    validateRoles(b.roleIds());
    Problem.require(
        b.password() == null || b.password().isBlank() || Passwords.INITIAL.equals(b.password()),
        "新账号初始密码统一为123456，用户登录后可自行修改");
    long id =
        db.insert(
            "sys_user",
            Db.p(
                "community_id",
                a.communityId(),
                "username",
                b.username(),
                "password_hash",
                encoder.encode(Passwords.INITIAL),
                "display_name",
                b.displayName(),
                "status",
                "ACTIVE",
                "created_by",
                a.id()));
    roles(id, b.roleIds());
    assign(
        a, id, b.communityIds() == null ? Set.of(a.communityId()) : b.communityIds(), b.roleIds());
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
        "SELECT id FROM sys_user WHERE id=:id FOR UPDATE",
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
                      + " u.status='ACTIVE' AND r.code='ADMIN' AND"
                      + " u.id<>:id",
                  Db.p("community", a.communityId(), "id", id))
              > 0,
          "不能停用或移除最后一个有效管理员");
    Problem.changed(
        db.update(
            "UPDATE sys_user SET"
                + " display_name=:name,status=:status,version=version+1,updated_by=:actor WHERE"
                + " id=:id AND version=:v",
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
    if (b.communityIds() != null) assign(a, id, b.communityIds(), b.roleIds());
    audit.log(a, "USER_CHANGED", "USER", id, Map.of("status", b.status(), "roles", b.roleIds()));
    return Api.ok(Map.of("id", id, "version", b.version() + 1));
  }

  @PostMapping({"/users/{id}/password", "/users/{id}/reset-password"})
  @Transactional
  @Operation(summary = "管理员重置应用用户密码，不涉及MySQL密码")
  public Object password(@PathVariable long id, @Valid @RequestBody Password b) {
    var a = auth.require("system:user:manage");
    Problem.require(
        b.password() == null || b.password().isBlank() || Passwords.INITIAL.equals(b.password()),
        "管理员重置密码统一为123456");
    Problem.changed(
        db.update(
            "UPDATE sys_user SET"
                + " password_hash=:hash,failed_login_count=0,locked_until=NULL,version=version+1,updated_by=:actor"
                + " WHERE id=:id AND version=:v",
            Db.p(
                "hash",
                encoder.encode(Passwords.INITIAL),
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
            "workers",
            db.list(
                "SELECT DISTINCT u.id,u.username,u.display_name FROM sys_user u JOIN sys_user_role"
                    + " ur ON ur.user_id=u.id JOIN sys_role r ON r.id=ur.role_id JOIN"
                    + " user_community m ON m.user_id=u.id WHERE m.community_id=:community AND"
                    + " m.status='ACTIVE' AND u.status='ACTIVE' AND r.code='GRID_WORKER' AND"
                    + " r.status='ACTIVE'",
                a.scope()),
            "vaccineProducts",
            db.list("SELECT id,code,name FROM vaccine_product WHERE status='ACTIVE'", Map.of()),
            "vaccineCampaigns",
            db.list("SELECT * FROM vaccine_campaign WHERE community_id=:community", a.scope())));
  }

  @GetMapping("/audit-logs")
  public Object logs(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) Long communityId,
      @RequestParam(defaultValue = "") String action,
      @RequestParam(defaultValue = "") String actor,
      @RequestParam(defaultValue = "") String result) {
    auth.require("audit:read");
    Problem.require(
        action.length() <= 80 && actor.length() <= 64 && result.length() <= 16, "筛选条件过长");
    String where =
        " FROM audit_log l LEFT JOIN sys_user u ON u.id=l.actor_user_id LEFT JOIN community c ON"
            + " c.id=l.community_id WHERE (:community IS NULL OR l.community_id=:community) AND"
            + " (:action='' OR l.action=:action) AND (:actor='' OR u.username LIKE :search) AND"
            + " (:result='' OR l.result=:result)";
    return Api.ok(
        db.page(
            "SELECT l.id,l.actor_user_id,u.username,u.display_name,l.community_id,c.name AS"
                + " community_name,l.system_actor,l.action,l.target_type,l.target_id,l.trace_id,l.result,l.change_summary_json,l.occurred_at"
                + where
                + " ORDER BY l.id DESC",
            "SELECT COUNT(*)" + where,
            Db.p(
                "community",
                communityId,
                "action",
                action,
                "actor",
                actor,
                "search",
                "%" + actor + "%",
                "result",
                result),
            page,
            pageSize));
  }

  private void assign(Actor a, long id, Set<Long> communities, Set<Long> roles) {
    boolean worker =
        db.count(
                "SELECT COUNT(*) FROM sys_role WHERE id IN (:roles) AND code='GRID_WORKER'",
                Db.p("roles", roles))
            > 0;
    Problem.require(!worker || !communities.isEmpty(), "网格员至少分配一个社区");
    if (!communities.isEmpty())
      Problem.require(
          db.count(
                  "SELECT COUNT(*) FROM community WHERE id IN (:ids) AND status='ACTIVE'",
                  Db.p("ids", communities))
              == communities.size(),
          "分配的社区不存在或已停用");
    db.update("UPDATE user_community SET status='INACTIVE' WHERE user_id=:id", Db.p("id", id));
    for (long c : communities)
      db.update(
          "INSERT INTO user_community(user_id,community_id,created_by) VALUES(:id,:c,:actor) ON"
              + " DUPLICATE KEY UPDATE status='ACTIVE'",
          Db.p("id", id, "c", c, "actor", a.id()));
    audit.log(a, "USER_COMMUNITIES_CHANGED", "USER", id, Map.of("communityIds", communities));
  }

  private void validateRoles(Set<Long> ids) {
    Problem.require(
        ids.size() == 1
            || db.count(
                    "SELECT COUNT(*) FROM sys_role WHERE id IN (:ids) AND code='STATISTICIAN'",
                    Db.p("ids", ids))
                == 0,
        "统计员不能同时拥有其他岗位角色");
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
