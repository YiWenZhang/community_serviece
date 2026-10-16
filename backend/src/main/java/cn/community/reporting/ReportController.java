package cn.community.reporting;

import cn.community.common.*;
import cn.community.identity.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "07 统计与月报")
public class ReportController {
  private final ReportService reports;
  private final Auth auth;
  private final Db db;

  public ReportController(ReportService reports, Auth auth, Db db) {
    this.reports = reports;
    this.auth = auth;
    this.db = db;
  }

  @GetMapping("/reports/metrics")
  @Operation(summary = "月份汇总，权限限制本人或社区，比例保留分子分母")
  public Object metrics(@Valid @ModelAttribute ReportService.Query q) {
    return Api.ok(reports.metrics(auth.current(), q));
  }

  @PostMapping("/report-jobs")
  @Operation(summary = "异步导出Excel或Word月报，返回作业ID")
  public Object export(
      @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ReportService.Export b) {
    return Api.ok(reports.enqueue(auth.current(), key, b));
  }

  @GetMapping("/report-jobs/{id}")
  @Operation(summary = "查询本人申请的月报状态与文件编号")
  public Object detail(@PathVariable long id) {
    var a = auth.current();
    var r =
        db.one(
            "SELECT * FROM report_job WHERE id=:id AND community_id=:community AND"
                + " requested_by=:actor",
            Db.p("id", id, "community", a.communityId(), "actor", a.id()));
    reports.owner(
        a,
        new ReportService.Query(
            r.get("period_start").toString().substring(0, 7),
            r.get("scope_owner_id") == null ? null : Db.id(r, "scope_owner_id"),
            null,
            null),
        true);
    return Api.ok(r);
  }
}
