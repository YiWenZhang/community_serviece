package cn.community.identity;

import cn.community.common.*;
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
    } catch (NumberFormatException e) {
      throw new Problem(401, "登录会话无效");
    }
    var user =
        db.optional(
                "SELECT id,community_id,username,status,version FROM sys_user WHERE id=:id",
                Db.p("id", id))
            .orElseThrow(() -> new Problem(401, "登录会话无效"));
    if (!"ACTIVE".equals(user.get("status"))) throw new Problem(401, "账号已停用");
    if (RequestContextHolder.getRequestAttributes()
        instanceof ServletRequestAttributes attributes) {
      var session = attributes.getRequest().getSession(false);
      if (session == null
          || !String.valueOf(user.get("version"))
              .equals(String.valueOf(session.getAttribute("userVersion")))) {
        if (session != null) session.invalidate();
        throw new Problem(401, "账号权限或密码已变更，请重新登录");
      }
    }
    var p = Db.p("id", id);
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
        id, Db.id(user, "community_id"), user.get("username").toString(), roles, permissions);
  }

  public Actor require(String permission) {
    var actor = current();
    actor.require(permission);
    return actor;
  }

  /** 作业重新读取当前权限，不复用排队时的权限快照。仅供后台工作器调用。 */
  public Actor worker(long id) {
    var user =
        db.one(
            "SELECT id,community_id,username FROM sys_user WHERE id=:id AND status='ACTIVE'",
            Db.p("id", id));
    var p = Db.p("id", id);
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
        id, Db.id(user, "community_id"), user.get("username").toString(), roles, permissions);
  }

  public void sameCommunityUser(Actor actor, long id, boolean requireWorker) {
    var p = Db.p("id", id, "community", actor.communityId());
    db.one(
        "SELECT id FROM sys_user WHERE id=:id AND community_id=:community AND status='ACTIVE'", p);
    if (requireWorker)
      Problem.require(
          db.count(
                  "SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE"
                      + " ur.user_id=:id AND r.code='GRID_WORKER' AND r.status='ACTIVE'",
                  p)
              > 0,
          "负责人必须为有效网格员");
  }
}
