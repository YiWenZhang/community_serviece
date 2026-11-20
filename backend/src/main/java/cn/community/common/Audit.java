package cn.community.common;

import cn.community.identity.Actor;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class Audit {
  private final Db db;
  private final Json json;

  public Audit(Db db, Json json) {
    this.db = db;
    this.json = json;
  }

  public void log(Actor actor, String action, String type, Object id, Object summary) {
    db.insert(
        "audit_log",
        Db.p(
            "community_id",
            actor.communityId(),
            "actor_user_id",
            actor.id(),
            "action",
            action,
            "target_type",
            type,
            "target_id",
            id == null ? null : id.toString(),
            "trace_id",
            trace(),
            "result",
            "SUCCESS",
            "change_summary_json",
            json.write(summary),
            "occurred_at",
            java.time.LocalDateTime.now(java.time.ZoneOffset.UTC),
            "created_by",
            actor.id()));
  }

  public void system(String action, String result) {
    db.insert(
        "audit_log",
        Db.p(
            "system_actor",
            "COMMUNITY_SERVICE",
            "action",
            action,
            "target_type",
            "AUTH",
            "trace_id",
            trace(),
            "result",
            result,
            "occurred_at",
            java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));
  }

  @org.springframework.transaction.annotation.Transactional(
      propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
  public void request(long user, Object community, String method, String path, int status) {
    if (community == null)
      community =
          db.one("SELECT community_id FROM sys_user WHERE id=:id", Db.p("id", user))
              .get("community_id");
    db.insert(
        "audit_log",
        Db.p(
            "community_id",
            community,
            "actor_user_id",
            user,
            "action",
            "HTTP_" + method,
            "target_type",
            "HTTP_REQUEST",
            "target_id",
            path.substring(0, Math.min(64, path.length())),
            "trace_id",
            trace(),
            "result",
            status < 400 ? "SUCCESS" : status == 401 || status == 403 ? "DENIED" : "FAILED",
            "change_summary_json",
            json.write(Db.p("method", method, "path", path, "status", status)),
            "occurred_at",
            java.time.LocalDateTime.now(java.time.ZoneOffset.UTC),
            "created_by",
            user));
  }

  private String trace() {
    String t = MDC.get("traceId");
    return t == null ? UUID.randomUUID().toString() : t;
  }
}
