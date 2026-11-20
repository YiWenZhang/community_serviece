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

  @GetMapping("/reports/filters")
  @Operation(summary = "报表经办人与疫苗周期筛选字典，不返回老人明细")
  public Object filters(@RequestParam(required = false) Long communityId) {
    var a = auth.current();
    a.require(a.globalReports() ? "report:read:community" : "report:read:own");
    Problem.require(
        a.globalReports() || communityId == null || communityId == a.communityId(), "只能查看当前授权社区");
    Long community = a.globalReports() ? communityId : Long.valueOf(a.communityId());
    var p = Db.p("c", community, "actor", a.id(), "global", a.globalReports());
    return Api.ok(
        Db.p(
            "workers",
            db.list(
                "SELECT DISTINCT u.id,u.username,u.display_name FROM sys_user u JOIN sys_user_role"
                    + " ur ON ur.user_id=u.id JOIN sys_role r ON r.id=ur.role_id WHERE"
                    + " r.code='GRID_WORKER' AND (:global OR u.id=:actor) AND (:c IS NULL OR"
                    + " EXISTS(SELECT 1 FROM user_community m WHERE m.user_id=u.id AND"
                    + " m.community_id=:c) OR EXISTS(SELECT 1 FROM elder_assignment_history ah JOIN"
                    + " grid g ON g.id=ah.new_grid_id WHERE ah.new_owner_id=u.id AND"
                    + " g.community_id=:c)) ORDER BY u.id",
                p),
            "campaigns",
            db.list(
                "SELECT v.id,v.community_id,v.name FROM vaccine_campaign v WHERE :c IS NULL OR"
                    + " v.community_id=:c ORDER BY v.id DESC",
                p)));
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
                + " r.id,r.status,r.format,r.period_start,r.period_end_exclusive,r.output_file_id,r.error_summary,r.created_at,r.community_id,c.name"
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
