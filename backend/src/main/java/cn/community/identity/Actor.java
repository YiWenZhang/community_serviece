package cn.community.identity;

import cn.community.common.Db;
import cn.community.common.Problem;
import java.util.Map;
import java.util.Set;

/** 身份只由会话及数据库取得，禁止信任请求中的communityId和ownerId。 */
public record Actor(
    long id, long communityId, String username, Set<String> roles, Set<String> permissions) {
  public void require(String permission) {
    if (!has(permission)) throw new Problem(403, "没有操作权限：" + permission);
    if (communityId == 0 && !permission.startsWith("report:") && !permission.startsWith("system:"))
      throw new Problem(403, "尚未分配可用社区，请联系管理员");
  }

  public boolean has(String permission) {
    if (!admin()
        && (permission.startsWith("system:")
            || permission.equals("audit:read")
            || permission.equals("elder:transfer")
            || permission.equals("notice:rule:manage")
            || permission.equals("ai:knowledge:manage"))) return false;
    return (!roles.equals(Set.of("STATISTICIAN")) || permission.startsWith("report:"))
        && permissions.contains(permission);
  }

  public Map<String, Object> scope() {
    return Db.p("community", communityId, "actor", id, "admin", admin());
  }

  public boolean admin() {
    return roles.contains("ADMIN");
  }

  public boolean globalReports() {
    return admin() || roles.contains("STATISTICIAN");
  }

  public Map<String, Object> parameters(Object... pairs) {
    var p = scope();
    p.putAll(Db.p(pairs));
    return p;
  }
}
