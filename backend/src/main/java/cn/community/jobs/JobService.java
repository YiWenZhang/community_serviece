package cn.community.jobs;

import cn.community.common.*;
import cn.community.identity.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobService {
  private final Db db;
  private final Json json;

  public JobService(Db db, Json json) {
    this.db = db;
    this.json = json;
  }

  @Transactional
  public Object enqueue(Actor a, String type, String key, Map<String, Object> payload) {
    payload.put("actorId", a.id());
    payload.put("communityId", a.communityId());
    var prior =
        db.optional(
            "SELECT id,created_by,payload_json FROM job_run WHERE job_type=:type AND"
                + " business_key=:key FOR UPDATE",
            Db.p("type", type, "key", key));
    if (prior.isPresent()) {
      if (Db.id(prior.get(), "created_by") != a.id()) throw Problem.missing();
      if (!json.equal(json.map(prior.get().get("payload_json")), payload))
        throw new Problem(409, "幂等键已用于不同作业参数");
      return Db.p("id", prior.get().get("id"), "replayed", true);
    }
    long id =
        db.insert(
            "job_run",
            Db.p(
                "community_id",
                a.communityId(),
                "job_type",
                type,
                "business_key",
                key,
                "payload_json",
                json.write(payload),
                "next_run_at",
                cn.community.elder.ElderService.utc(),
                "created_by",
                a.id()));
    return Db.p("id", id, "status", "PENDING");
  }

  public Object detail(Actor a, long id) {
    var row =
        db.one(
            "SELECT id,job_type,status,retry_count,max_retries,error_summary,created_at,updated_at"
                + " FROM job_run WHERE id=:id AND community_id=:community AND created_by=:actor",
            Db.p("id", id, "community", a.communityId(), "actor", a.id()));
    return row;
  }
}
