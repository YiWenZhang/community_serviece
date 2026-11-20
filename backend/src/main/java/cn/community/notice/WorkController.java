package cn.community.notice;

import cn.community.common.*;
import cn.community.identity.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/work")
@Tag(name = "04 工作监督")
public class WorkController {
  private final Auth auth;
  private final Db db;

  public WorkController(Auth auth, Db db) {
    this.auth = auth;
    this.db = db;
  }

  @GetMapping("/filters")
  @Operation(summary = "当前社区可查看的负责人选项，网格员仅返回本人")
  public Object filters() {
    var a = auth.require("notice:read");
    return Api.ok(Db.p("workers", workers(a)));
  }

  private List<Map<String, Object>> workers(Actor a) {
    return db.list(
        "SELECT u.id,u.username,u.display_name,u.status FROM sys_user u WHERE"
            + " (:admin OR u.id=:actor) AND (EXISTS(SELECT 1 FROM user_community m"
            + " JOIN sys_user_role ur ON ur.user_id=m.user_id JOIN sys_role sr ON sr.id=ur.role_id"
            + " WHERE m.user_id=u.id AND m.community_id=:community AND m.status='ACTIVE'"
            + " AND sr.code='GRID_WORKER' AND sr.status='ACTIVE') OR EXISTS(SELECT 1 FROM"
            + " elder_profile e WHERE e.owner_user_id=u.id AND e.community_id=:community))"
            + " ORDER BY u.username,u.id",
        a.scope());
  }

  @GetMapping("/overview")
  @Operation(summary = "管理员按网格员查看当前社区联系闭环与跟进完成情况")
  public Object overview(
      @RequestParam(required = false) String noticeType,
      @RequestParam(required = false) Long ownerId) {
    return Api.ok(overview(auth.require("notice:read"), noticeType, ownerId));
  }

  public Map<String, Object> overview(Actor a, String noticeType, Long ownerId) {
    a.require("notice:read");
    if (!a.admin()) throw new Problem(403, "工作监督仅供管理员查看");
    var p = a.scope();
    String type = "";
    if (noticeType != null) {
      Problem.require(
          Set.of("BIRTHDAY", "VACCINE", "LECTURE", "OTHER").contains(noticeType), "业务类型无效");
      p.put("type", noticeType);
      type = " AND n.type=:type";
    }
    var byOwner = new LinkedHashMap<Long, Map<String, Object>>();
    for (var u : workers(a)) {
      long id = Db.id(u, "id");
      if (ownerId != null && ownerId != id) continue;
      var row = new LinkedHashMap<>(u);
      for (var key :
          List.of(
              "elder_count",
              "total",
              "completed",
              "pending",
              "unreachable",
              "overdue",
              "followup_pending",
              "followup_completed")) row.put(key, 0L);
      byOwner.put(id, row);
    }
    for (var row :
        db.list(
            "SELECT owner_user_id AS owner,COUNT(*) AS elder_count FROM elder_profile WHERE"
                + " community_id=:community AND status='ACTIVE' GROUP BY owner_user_id",
            p)) {
      var target = byOwner.get(Db.id(row, "owner"));
      if (target != null) target.put("elder_count", row.get("elder_count"));
    }
    for (var row :
        db.list(
            "SELECT e.owner_user_id AS owner,COUNT(*) AS total, SUM(r.workflow_status='CLOSED') AS"
                + " completed,SUM(r.workflow_status IN ('OPEN','FOLLOWING')) AS pending,"
                + " SUM(r.workflow_status IN ('OPEN','FOLLOWING') AND"
                + " r.receipt_status='UNREACHABLE') AS unreachable, SUM(r.workflow_status IN"
                + " ('OPEN','FOLLOWING') AND EXISTS(SELECT 1 FROM followup_task t WHERE"
                + " t.recipient_id=r.id AND t.status IN ('PENDING','PROCESSING') AND"
                + " t.due_at<UTC_TIMESTAMP(3))) AS overdue FROM notice_recipient r JOIN"
                + " elder_profile e ON e.id=r.elder_id JOIN notice n ON n.id=r.notice_id WHERE"
                + " r.community_id=:community AND n.status IN ('ACTIVE','COMPLETED') AND"
                + " r.workflow_status<>'CANCELLED'"
                + type
                + " GROUP BY e.owner_user_id",
            p)) {
      var target = byOwner.get(Db.id(row, "owner"));
      if (target != null) {
        row.remove("owner");
        target.putAll(row);
      }
    }
    for (var row :
        db.list(
            "SELECT t.assignee_id AS owner,SUM(t.status IN ('PENDING','PROCESSING')) AS"
                + " followup_pending,SUM(t.status='COMPLETED') AS followup_completed FROM"
                + " followup_task t JOIN notice_recipient r ON r.id=t.recipient_id JOIN notice n ON"
                + " n.id=r.notice_id WHERE t.community_id=:community"
                + type
                + " GROUP BY t.assignee_id",
            p)) {
      var target = byOwner.get(Db.id(row, "owner"));
      if (target != null) {
        row.remove("owner");
        target.putAll(row);
      }
    }
    return Db.p(
        "items", new ArrayList<>(byOwner.values()), "scope", "当前社区累计；联系工作不含草稿及取消记录，闭环不等于满意或已接种");
  }
}
