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

  public JobController(Db db, Auth auth, RuleService rules, JobService jobs) {
    this.db = db;
    this.auth = auth;
    this.rules = rules;
    this.jobs = jobs;
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

  @GetMapping("/jobs/{id}")
  @Operation(summary = "仅申请人查看作业状态及脱敏错误")
  public Object job(@PathVariable long id) {
    return Api.ok(jobs.detail(auth.current(), id));
  }
}
