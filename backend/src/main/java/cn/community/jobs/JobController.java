package cn.community.jobs;

import cn.community.common.*;
import cn.community.identity.*;
import cn.community.notice.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "08 通知规则与异步作业")
public class JobController {
  public record Generation(
      @NotNull Long ruleId, @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") @NotNull String period) {}

  private final Db db;
  private final Auth auth;
  private final RuleService rules;
  private final JobService jobs;
  private final Audit audit;

  public JobController(Db db, Auth auth, RuleService rules, JobService jobs, Audit audit) {
    this.db = db;
    this.auth = auth;
    this.rules = rules;
    this.jobs = jobs;
    this.audit = audit;
  }

  @GetMapping("/notice-rules")
  @Operation(summary = "管理员读取本社区通知规则")
  public Object rules() {
    var a = auth.require("notice:rule:manage");
    return Api.ok(
        db.list("SELECT * FROM notice_rule WHERE community_id=:community ORDER BY id", a.scope()));
  }

  @PostMapping("/notice-rules")
  @Operation(summary = "创建生日或疫苗通知规则")
  public Object create(@Valid @RequestBody RuleService.Rule b) {
    return Api.ok(rules.save(auth.current(), null, b));
  }

  @PutMapping("/notice-rules/{id}")
  @Operation(summary = "更新规则版本与启用状态")
  public Object update(@PathVariable long id, @Valid @RequestBody RuleService.Rule b) {
    return Api.ok(rules.save(auth.current(), id, b));
  }

  @PostMapping("/notice-generations")
  @Operation(summary = "排队按规则生成本人通知；返回持久作业编号")
  public Object generate(
      @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Generation b) {
    var a = auth.require("notice:create");
    NoticeService.validateKey(key);
    db.one(
        "SELECT id FROM notice_rule WHERE id=:id AND community_id=:community AND enabled=1",
        Db.p("id", b.ruleId(), "community", a.communityId()));
    return Api.ok(
        jobs.enqueue(
            a,
            "NOTICE_GENERATION",
            "manual:" + a.id() + ":" + key,
            Db.p("ruleId", b.ruleId(), "period", b.period())));
  }

  @PostMapping("/notice-rules/{id}/generate")
  @Operation(summary = "管理员立即按本社区各负责人范围排队生成通知")
  @org.springframework.transaction.annotation.Transactional
  public Object generateCommunity(
      @PathVariable long id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Generation b) {
    var a = auth.require("notice:rule:manage");
    NoticeService.validateKey(key);
    Problem.require(b.ruleId() == id, "规则编号不一致");
    db.one(
        "SELECT id FROM notice_rule WHERE id=:id AND community_id=:community AND enabled=1",
        a.parameters("id", id));
    var users =
        db.list(
            "SELECT DISTINCT u.id FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id JOIN"
                + " sys_role r ON r.id=ur.role_id JOIN user_community m ON m.user_id=u.id WHERE"
                + " m.community_id=:community AND m.status='ACTIVE' AND u.status='ACTIVE' AND"
                + " r.code='GRID_WORKER' AND r.status='ACTIVE'",
            a.scope());
    var results = new java.util.ArrayList<Object>();
    int skipped = 0;
    for (var user : users) {
      var actor = auth.worker(Db.id(user, "id"), a.communityId());
      if (!actor.has("notice:create")) {
        skipped++;
        continue;
      }
      results.add(
          jobs.enqueue(
              actor,
              "NOTICE_GENERATION",
              "admin:" + a.id() + ":" + key + ":" + actor.id(),
              Db.p("ruleId", id, "period", b.period())));
    }
    Problem.require(!results.isEmpty(), "当前社区没有具备通知创建权限的有效网格员");
    audit.log(
        a,
        "NOTICE_GENERATION_QUEUED",
        "NOTICE_RULE",
        id,
        java.util.Map.of("workers", results.size(), "period", b.period()));
    return Api.ok(
        Db.p("jobs", results, "queuedWorkers", results.size(), "skippedWorkers", skipped));
  }

  @GetMapping("/jobs/{id}")
  @Operation(summary = "仅申请人查看作业状态及脱敏错误")
  public Object job(@PathVariable long id) {
    return Api.ok(jobs.detail(auth.current(), id));
  }
}
