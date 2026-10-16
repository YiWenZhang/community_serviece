package cn.community.identity;

import cn.community.common.Db;
import cn.community.common.Problem;
import java.util.Map;
import java.util.Set;

/** 身份只由会话及数据库取得，禁止信任请求中的communityId和ownerId。 */
public record Actor(
    long id, long communityId, String username, Set<String> roles, Set<String> permissions) {
  public void require(String permission) {
    if (!permissions.contains(permission)) throw new Problem(403, "没有操作权限：" + permission);
  }

  public boolean has(String permission) {
    return permissions.contains(permission);
  }

  public Map<String, Object> scope() {
    return Db.p("community", communityId, "actor", id);
  }
}
