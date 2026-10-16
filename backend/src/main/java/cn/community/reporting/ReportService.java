package cn.community.reporting;

import cn.community.common.*;
import cn.community.files.FileService;
import cn.community.identity.*;
import cn.community.jobs.JobService;
import jakarta.validation.constraints.*;
import java.io.ByteArrayOutputStream;
import java.math.*;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 所有报表只返回汇总；指标先冻结再生成文件，绝不夹带老人明细。 */
@Service
public class ReportService {
  public record Query(
      @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") @NotNull String month,
      Long ownerId,
      @Pattern(regexp = "VACCINE|BIRTHDAY|LECTURE|OTHER") String noticeType,
      Long campaignId) {}

  public record Export(
      @jakarta.validation.Valid @NotNull Query query,
      @Pattern(regexp = "XLSX|DOCX") @NotNull String format) {}

  private final Db db;
  private final Json json;
  private final FileService files;
  private final JobService jobs;
  private final Audit audit;

  public ReportService(Db db, Json json, FileService files, JobService jobs, Audit audit) {
    this.db = db;
    this.json = json;
    this.files = files;
    this.jobs = jobs;
    this.audit = audit;
  }

  public Long owner(Actor a, Query q, boolean export) {
    String community = export ? "report:export:community" : "report:read:community";
    String own = export ? "report:export:own" : "report:read:own";
    if (a.has(community)) return q.ownerId();
    a.require(own);
    if (q.ownerId() != null && q.ownerId() != a.id()) throw new Problem(403, "只能查看本人汇总");
    return a.id();
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> metrics(Actor a, Query q) {
    Long owner = owner(a, q, false);
    return calculate(a, q, owner, LocalDateTime.now(ZoneOffset.UTC));
  }

  private Map<String, Object> metric(
      String code, long numerator, long denominator, String dimension) {
    BigDecimal value =
        denominator == 0
            ? null
            : BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    return Db.p(
        "code",
        code,
        "dimension",
        dimension,
        "numerator",
        numerator,
        "denominator",
        denominator,
        "value",
        value,
        "nullReason",
        value == null ? "无有效样本" : null);
  }

  public Map<String, Object> calculate(Actor a, Query q, Long owner, LocalDateTime snapshot) {
    YearMonth month = YearMonth.parse(q.month());
    LocalDateTime start =
        month
            .atDay(1)
            .atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .withZoneSameInstant(ZoneOffset.UTC)
            .toLocalDateTime();
    LocalDateTime end =
        month
            .plusMonths(1)
            .atDay(1)
            .atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .withZoneSameInstant(ZoneOffset.UTC)
            .toLocalDateTime();
    LocalDateTime cutoff = end.isBefore(snapshot) ? end : snapshot;
    var p =
        Db.p(
            "community",
            a.communityId(),
            "start",
            start,
            "end",
            end,
            "cutoff",
            cutoff,
            "snapshot",
            snapshot,
            "owner",
            owner,
            "type",
            q.noticeType(),
            "campaign",
            q.campaignId());
    String where =
        "r.community_id=:community AND r.issued_at>=:start AND r.issued_at<:end AND"
            + " r.issued_at<:cutoff AND r.created_at<=:snapshot AND (:owner IS NULL OR"
            + " r.owner_at_issue=:owner) AND (:type IS NULL OR n.type=:type) AND (:campaign IS NULL"
            + " OR n.campaign_id=:campaign)";
    String facts =
        "SELECT r.id,COALESCE(ev.to_receipt_status,'PENDING') AS"
            + " receipt,COALESCE(ev.to_workflow_status,'OPEN') AS workflow FROM notice_recipient r"
            + " JOIN notice n ON n.id=r.notice_id LEFT JOIN notice_recipient_event ev ON"
            + " ev.id=(SELECT e.id FROM notice_recipient_event e WHERE e.recipient_id=r.id AND"
            + " e.effective_at<:cutoff AND e.created_at<=:snapshot ORDER BY e.effective_at"
            + " DESC,e.id DESC LIMIT 1) WHERE "
            + where;
    var notification =
        db.one(
            "SELECT COUNT(*) AS total,COALESCE(SUM(receipt='CONFIRMED'),0) AS"
                + " confirmed,COALESCE(SUM(workflow='CLOSED'),0) AS closed FROM ("
                + facts
                + ") x WHERE workflow<>'CANCELLED'",
            p);
    long total = Db.id(notification, "total");
    var metrics = new ArrayList<Map<String, Object>>();
    metrics.add(metric("NOTICE_COVERAGE", Db.id(notification, "confirmed"), total, "ALL"));
    metrics.add(metric("SERVICE_CLOSURE", Db.id(notification, "closed"), total, "ALL"));
    var feedback =
        db.one(
            "SELECT COUNT(*) AS total,COALESCE(SUM(f.rating='SATISFIED'),0) AS satisfied FROM"
                + " service_feedback f JOIN ("
                + facts
                + ") x ON x.id=f.recipient_id WHERE x.workflow<>'CANCELLED' AND f.is_valid=1 AND"
                + " f.id=(SELECT f2.id FROM service_feedback f2 WHERE"
                + " f2.recipient_id=f.recipient_id AND f2.effective_at<:cutoff AND"
                + " f2.created_at<=:snapshot ORDER BY f2.effective_at DESC,f2.revision_no DESC"
                + " LIMIT 1)",
            p);
    metrics.add(
        metric("SATISFACTION", Db.id(feedback, "satisfied"), Db.id(feedback, "total"), "ALL"));
    metrics.add(metric("FEEDBACK_RATE", Db.id(feedback, "total"), total, "ALL"));
    var channels =
        db.list(
            "SELECT d.channel,COUNT(*) AS attempts FROM delivery_attempt d JOIN notice_recipient r"
                + " ON r.id=d.recipient_id JOIN notice n ON n.id=r.notice_id WHERE "
                + where
                + " AND d.attempted_at<:cutoff AND d.created_at<=:snapshot AND d.result<>'FAILED'"
                + " GROUP BY d.channel ORDER BY d.channel",
            p);
    long attempts = channels.stream().mapToLong(r -> Db.id(r, "attempts")).sum();
    for (var channel : channels)
      metrics.add(
          metric(
              "CHANNEL_USAGE",
              Db.id(channel, "attempts"),
              attempts,
              channel.get("channel").toString()));
    if (q.noticeType() == null || q.noticeType().equals("VACCINE")) {
      String vaccinated =
          "SELECT s.campaign_id,h.resulting_status,h.eligibility_snapshot FROM elder_vaccine_status"
              + " s JOIN elder_profile e ON e.id=s.elder_id JOIN vaccination_record h ON"
              + " h.id=(SELECT h2.id FROM vaccination_record h2 WHERE"
              + " h2.elder_vaccine_status_id=s.id AND h2.effective_at<:cutoff AND"
              + " h2.created_at<=:snapshot ORDER BY h2.effective_at DESC,h2.id DESC LIMIT 1) WHERE"
              + " e.community_id=:community AND (:campaign IS NULL OR s.campaign_id=:campaign) AND"
              + " (:owner IS NULL OR :owner=(SELECT a.new_owner_id FROM elder_assignment_history a"
              + " WHERE a.elder_id=e.id AND a.effective_at<:cutoff AND a.created_at<=:snapshot"
              + " ORDER BY a.effective_at DESC,a.id DESC LIMIT 1))";
      if ("VACCINE".equals(q.noticeType()))
        vaccinated +=
            " AND EXISTS(SELECT 1 FROM notice_recipient r JOIN notice n ON n.id=r.notice_id WHERE"
                + " r.elder_id=e.id AND n.campaign_id=s.campaign_id AND "
                + where
                + ")";
      var vaccine =
          db.list(
              "SELECT campaign_id,COALESCE(SUM(eligibility_snapshot='ELIGIBLE'),0) AS"
                  + " eligible,COALESCE(SUM(eligibility_snapshot='ELIGIBLE' AND"
                  + " resulting_status='COMPLETED'),0) AS completed FROM ("
                  + vaccinated
                  + ") v GROUP BY campaign_id ORDER BY campaign_id",
              p);
      for (var v : vaccine)
        metrics.add(
            metric(
                "VACCINATION_RATE",
                Db.id(v, "completed"),
                Db.id(v, "eligible"),
                "CAMPAIGN:" + v.get("campaign_id")));
    }
    var task =
        db.one(
            "SELECT COUNT(*) AS total,COALESCE(SUM(t.completed_at IS NOT NULL AND"
                + " t.completed_at<=t.due_at AND t.completed_at<:cutoff),0) AS on_time FROM"
                + " followup_task t JOIN notice_recipient r ON r.id=t.recipient_id JOIN notice n ON"
                + " n.id=r.notice_id WHERE t.community_id=:community AND t.due_at>=:start AND"
                + " t.due_at<:end AND t.created_at<=:snapshot AND (:owner IS NULL OR"
                + " t.assignee_id=:owner) AND (:type IS NULL OR n.type=:type) AND (:campaign IS"
                + " NULL OR n.campaign_id=:campaign) AND NOT(t.status='CANCELLED' AND"
                + " t.updated_at<:cutoff)",
            p);
    metrics.add(metric("FOLLOWUP_ON_TIME", Db.id(task, "on_time"), Db.id(task, "total"), "ALL"));
    return Db.p(
        "month",
        q.month(),
        "ownerId",
        owner,
        "noticeType",
        q.noticeType(),
        "campaignId",
        q.campaignId(),
        "metricVersion",
        "v1",
        "snapshotAt",
        snapshot.toInstant(ZoneOffset.UTC).toString(),
        "cutoffAt",
        cutoff.toInstant(ZoneOffset.UTC).toString(),
        "metrics",
        metrics);
  }

  @Transactional
  public Object enqueue(Actor a, String key, Export b) {
    Long owner = owner(a, b.query(), true);
    cn.community.notice.NoticeService.validateKey(key);
    var existing =
        db.optional(
            "SELECT id,payload_json FROM job_run WHERE job_type='REPORT_EXPORT' AND"
                + " business_key=:key",
            Db.p("key", "report:" + a.id() + ":" + key));
    if (existing.isPresent()) {
      var old = json.map(existing.get().get("payload_json"));
      if (!json.equal(old.get("query"), b.query()) || !b.format().equals(old.get("format")))
        throw new Problem(409, "幂等键已用于不同报表参数");
      return Db.p(
          "reportJobId", old.get("reportId"), "jobId", existing.get().get("id"), "replayed", true);
    }
    YearMonth month = YearMonth.parse(b.query().month());
    long report =
        db.insert(
            "report_job",
            Db.p(
                "community_id",
                a.communityId(),
                "report_type",
                "MONTHLY_SERVICE",
                "period_start",
                month.atDay(1),
                "period_end_exclusive",
                month.plusMonths(1).atDay(1),
                "scope_owner_id",
                owner,
                "filter_json",
                json.write(b.query()),
                "metric_version",
                "v1",
                "snapshot_at",
                LocalDateTime.now(ZoneOffset.UTC),
                "requested_by",
                a.id(),
                "format",
                b.format(),
                "expires_at",
                LocalDateTime.now(ZoneOffset.UTC).plusDays(7),
                "created_by",
                a.id()));
    @SuppressWarnings("unchecked")
    var job =
        (Map<String, Object>)
            jobs.enqueue(
                a,
                "REPORT_EXPORT",
                "report:" + a.id() + ":" + key,
                Db.p("reportId", report, "query", b.query(), "format", b.format()));
    audit.log(a, "REPORT_REQUESTED", "REPORT", report, Map.of("format", b.format()));
    return Db.p("reportJobId", report, "jobId", job.get("id"));
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public void export(Actor a, long id) throws Exception {
    var report =
        db.one(
            "SELECT * FROM report_job WHERE id=:id AND requested_by=:actor AND"
                + " community_id=:community FOR UPDATE",
            Db.p("id", id, "actor", a.id(), "community", a.communityId()));
    if ("COMPLETED".equals(report.get("status"))) return;
    var filter = json.map(report.get("filter_json"));
    var q =
        new Query(
            filter.get("month").toString(),
            number(filter.get("ownerId")),
            (String) filter.get("noticeType"),
            number(filter.get("campaignId")));
    Long scope = owner(a, q, true);
    LocalDateTime snapshot = LocalDateTime.now(ZoneOffset.UTC);
    var data = calculate(a, q, scope, snapshot);
    @SuppressWarnings("unchecked")
    var rows = (List<Map<String, Object>>) data.get("metrics");
    for (var metric : rows)
      db.insert(
          "report_metric",
          Db.p(
              "report_job_id",
              id,
              "metric_code",
              metric.get("code"),
              "dimension_key",
              metric.get("dimension"),
              "dimension_json",
              json.write(
                  Db.p(
                      "dimension",
                      metric.get("dimension"),
                      "ownerId",
                      scope,
                      "noticeType",
                      q.noticeType())),
              "numerator",
              metric.get("numerator"),
              "denominator",
              metric.get("denominator"),
              "value_decimal",
              metric.get("value"),
              "null_reason",
              metric.get("nullReason"),
              "created_by",
              a.id()));
    boolean excel = "XLSX".equals(report.get("format"));
    var bytes = new ByteArrayOutputStream();
    String[] headers = {"指标", "维度", "分子", "分母", "比例", "说明"};
    if (excel) {
      try (var workbook = new XSSFWorkbook()) {
        var sheet = workbook.createSheet("养老服务月报");
        var title = sheet.createRow(0);
        title.createCell(0).setCellValue(q.month() + " 养老服务月报 v1 快照 " + data.get("snapshotAt"));
        var header = sheet.createRow(1);
        for (int c = 0; c < headers.length; c++) header.createCell(c).setCellValue(headers[c]);
        int i = 2;
        for (var r : rows) {
          var row = sheet.createRow(i++);
          String[] vals = values(r);
          for (int c = 0; c < vals.length; c++) row.createCell(c).setCellValue(vals[c]);
        }
        for (int c = 0; c < headers.length; c++) sheet.setColumnWidth(c, c < 2 ? 7000 : 4500);
        workbook.write(bytes);
      }
    } else {
      try (var doc = new XWPFDocument()) {
        doc.createParagraph().createRun().setText(q.month() + " 养老服务月报");
        doc.createParagraph().createRun().setText("口径 v1 快照 " + data.get("snapshotAt") + " 仅汇总数据");
        var table = doc.createTable(rows.size() + 1, headers.length);
        for (int c = 0; c < headers.length; c++) table.getRow(0).getCell(c).setText(headers[c]);
        for (int r = 0; r < rows.size(); r++) {
          String[] vals = values(rows.get(r));
          for (int c = 0; c < vals.length; c++) table.getRow(r + 1).getCell(c).setText(vals[c]);
        }
        doc.write(bytes);
      }
    }
    long file =
        files.save(
            a,
            "REPORT",
            "monthly-" + q.month() + (excel ? ".xlsx" : ".docx"),
            excel
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            bytes.toByteArray(),
            true);
    db.update(
        "UPDATE report_job SET"
            + " status='COMPLETED',output_file_id=:file,snapshot_at=:snapshot,version=version+1,error_summary=NULL"
            + " WHERE id=:id",
        Db.p("file", file, "snapshot", snapshot, "id", id));
    audit.log(a, "REPORT_EXPORTED", "REPORT", id, Map.of("fileId", file));
  }

  private String[] values(Map<String, Object> row) {
    return new String[] {
      row.get("code").toString(),
      row.get("dimension").toString(),
      row.get("numerator").toString(),
      row.get("denominator").toString(),
      row.get("value") == null
          ? "无有效样本"
          : new BigDecimal(row.get("value").toString())
                  .multiply(BigDecimal.valueOf(100))
                  .setScale(2, RoundingMode.HALF_UP)
              + "%",
      row.get("nullReason") == null ? "" : row.get("nullReason").toString()
    };
  }

  private Long number(Object n) {
    return n == null ? null : Long.valueOf(n.toString());
  }
}
