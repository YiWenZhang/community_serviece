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

  @GetMapping("/report-jobs")
  public Object list(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    var a = auth.current();
    a.require(a.globalReports() ? "report:read:community" : "report:read:own");
    String where =
        " FROM report_job r JOIN community c ON c.id=r.community_id WHERE ("
            + a.globalReports()
            + " OR (r.requested_by=:actor AND r.community_id=:community))";
    return Api.ok(
        db.page(
            "SELECT"
                + " r.id,r.status,r.format,r.period_start,r.output_file_id,r.error_summary,r.created_at,r.community_id,c.name"
                + " AS community_name"
                + where
                + " ORDER BY r.id DESC",
            "SELECT COUNT(*)" + where,
            a.scope(),
            page,
            pageSize));
  }

  @GetMapping("/report-jobs/{id}")
  @Operation(summary = "查询本人申请的月报状态与文件编号")
  public Object detail(@PathVariable long id) {
    var a = auth.current();
    var r =
        db.one(
            "SELECT r.*,c.name AS community_name FROM report_job r JOIN community c ON"
                + " c.id=r.community_id WHERE r.id=:id AND ("
                + a.globalReports()
                + " OR (r.requested_by=:actor AND r.community_id=:community))",
            a.parameters("id", id));
    a.require(a.globalReports() ? "report:read:community" : "report:read:own");
    return Api.ok(r);
  }
}
