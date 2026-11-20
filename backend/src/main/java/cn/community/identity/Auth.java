package cn.community.identity;

import cn.community.common.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.*;

@Service
public class Auth {
  private final Db db;

  public Auth(Db db) {
    this.db = db;
  }

  public Actor current() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || "anonymousUser".equals(authentication.getName())) throw new Problem(401, "请先登录");
    long id;
    try {
      id = Long.parseLong(authentication.getName());
    } catch (Exception e) {
      throw new Problem(401, "登录会话无效");
    }
    var user =
        db.optional(
                "SELECT id,community_id,username,status,version FROM sys_user WHERE id=:id",
                Db.p("id", id))
            .orElseThrow(() -> new Problem(401, "登录会话无效"));
    if (!"ACTIVE".equals(user.get("status"))) throw new Problem(401, "账号已停用");
    var actor = load(user);
    if (RequestContextHolder.getRequestAttributes()
        instanceof ServletRequestAttributes attributes) {
      var request = attributes.getRequest();
      var session = request.getSession(false);
      if (session == null
          || !String.valueOf(user.get("version"))
              .equals(String.valueOf(session.getAttribute("userVersion")))) {
        if (session != null) session.invalidate();
        throw new Problem(401, "账号权限、社区分配或密码已变更，请重新登录");
      }
      String header = request.getHeader("X-Community-Id");
      if (header != null && !header.isBlank()) {
        try {
          long selected = Long.parseLong(header);
          Problem.require(selected > 0, "社区编号无效");
          actor = inCommunity(actor, selected);
        } catch (NumberFormatException e) {
          throw new Problem(422, "社区编号无效");
        }
      } else if (!actor.globalReports()) {
        var rows = communities(actor);
        long home = actor.communityId();
        long chosen =
            rows.stream().anyMatch(r -> Db.id(r, "id") == home)
                ? home
                : rows.isEmpty() ? 0 : Db.id(rows.getFirst(), "id");
        actor = new Actor(actor.id(), chosen, actor.username(), actor.roles(), actor.permissions());
      }
      request.setAttribute("auditActorId", actor.id());
      request.setAttribute(
          "auditCommunityId", actor.communityId() == 0 ? null : actor.communityId());
    }
    return actor;
  }

  private Actor load(Map<String, Object> user) {
    var p = Db.p("id", user.get("id"));
    var roles =
        db
            .list(
                "SELECT r.code FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.id WHERE"
                    + " ur.user_id=:id AND r.status='ACTIVE'",
                p)
            .stream()
            .map(r -> r.get("code").toString())
            .collect(Collectors.toSet());
    var permissions =
        db
            .list(
                "SELECT DISTINCT p.code FROM sys_permission p JOIN sys_role_permission rp ON"
                    + " rp.permission_id=p.id JOIN sys_user_role ur ON ur.role_id=rp.role_id JOIN"
                    + " sys_role r ON r.id=ur.role_id WHERE ur.user_id=:id AND r.status='ACTIVE'",
                p)
            .stream()
            .map(r -> r.get("code").toString())
            .collect(Collectors.toSet());
    return new Actor(
        Db.id(user, "id"),
        Db.id(user, "community_id"),
        user.get("username").toString(),
        roles,
        permissions);
  }

  public Actor require(String permission) {
    var a = current();
    a.require(permission);
    return a;
  }

  public List<Map<String, Object>> communities(Actor a) {
    String where =
        a.globalReports()
            ? "1=1"
            : "c.status='ACTIVE' AND EXISTS(SELECT 1 FROM user_community m WHERE"
                  + " m.community_id=c.id AND m.user_id=:actor AND m.status='ACTIVE')";
    return db.list(
        "SELECT c.id,c.code,c.name,c.status,c.version,COALESCE(p.description,'') AS"
            + " description,(p.icon_data IS NOT NULL) AS has_icon FROM community c LEFT JOIN"
            + " community_profile p ON p.community_id=c.id WHERE "
            + where
            + " ORDER BY c.id",
        a.scope());
  }

  public Actor inCommunity(Actor a, long community) {
    var row =
        db.optional(
                "SELECT id,status FROM community WHERE id=:community", Db.p("community", community))
            .orElseThrow(Problem::missing);
    if (!a.globalReports()
        && (!"ACTIVE".equals(row.get("status"))
            || db.count(
                    "SELECT COUNT(*) FROM user_community WHERE user_id=:actor AND"
                        + " community_id=:community AND status='ACTIVE'",
                    Db.p("actor", a.id(), "community", community))
                == 0)) throw new Problem(403, "没有该社区的访问权限");
    return new Actor(a.id(), community, a.username(), a.roles(), a.permissions());
  }

  public Actor worker(long id) {
    return load(
        db.one(
            "SELECT id,community_id,username FROM sys_user WHERE id=:id AND status='ACTIVE'",
            Db.p("id", id)));
  }

  public Actor worker(long id, long community) {
    return inCommunity(worker(id), community);
  }

  public void sameCommunityUser(Actor a, long id, boolean worker) {
    db.one("SELECT id FROM sys_user WHERE id=:id AND status='ACTIVE'", Db.p("id", id));
    if (worker)
      Problem.require(
          db.count(
                  "SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id JOIN"
                      + " user_community m ON m.user_id=ur.user_id WHERE ur.user_id=:id AND"
                      + " m.community_id=:community AND m.status='ACTIVE' AND r.code='GRID_WORKER'"
                      + " AND r.status='ACTIVE'",
                  Db.p("id", id, "community", a.communityId()))
              > 0,
          "负责人必须为已分配该社区的有效网格员");
  }
}
