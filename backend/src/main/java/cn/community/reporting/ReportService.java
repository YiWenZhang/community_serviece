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
      Long campaignId,
      Long communityId,
      LocalDate startDate,
      LocalDate endDate) {
    public Query(String month, Long ownerId, String noticeType, Long campaignId, Long communityId) {
      this(month, ownerId, noticeType, campaignId, communityId, null, null);
    }

    public Query(String month, Long ownerId, String noticeType, Long campaignId) {
      this(month, ownerId, noticeType, campaignId, null);
    }
  }

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
    Long scopeCommunity = a.globalReports() ? q.communityId() : Long.valueOf(a.communityId());
    Problem.require(
        a.globalReports() || q.communityId() == null || q.communityId() == a.communityId(),
        "只能统计当前授权社区内本人负责的数据");
    var period = period(q);
    LocalDateTime start =
        period[0]
            .atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .withZoneSameInstant(ZoneOffset.UTC)
            .toLocalDateTime();
    LocalDateTime end =
        period[1]
            .atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .withZoneSameInstant(ZoneOffset.UTC)
            .toLocalDateTime();
    LocalDateTime cutoff = end.isBefore(snapshot) ? end : snapshot;
    var p =
        Db.p(
            "community",
            scopeCommunity,
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
        "(:community IS NULL OR r.community_id=:community) AND r.issued_at>=:start AND"
            + " r.issued_at<:end AND r.issued_at<:cutoff AND r.created_at<=:snapshot AND (:owner IS"
            + " NULL OR r.owner_at_issue=:owner) AND (:type IS NULL OR n.type=:type) AND (:campaign"
            + " IS NULL OR n.campaign_id=:campaign)";
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
    var gaps = new ArrayList<Map<String, Object>>();
    var receiptCounts =
        db.list(
            "SELECT receipt,COUNT(*) AS amount FROM ("
                + facts
                + ") x WHERE workflow<>'CANCELLED' GROUP BY receipt",
            p);
    for (var count : receiptCounts) {
      String status = count.get("receipt").toString();
      long amount = Db.id(count, "amount");
      metrics.add(metric("NOTICE_STATUS_COUNT", amount, total, status));
      if (Set.of("PENDING", "UNREACHABLE", "REFUSED").contains(status))
        gaps.add(
            Db.p(
                "title",
                switch (status) {
                  case "PENDING" -> "待联系通知";
                  case "UNREACHABLE" -> "未联系上";
                  default -> "拒绝接收";
                },
                "count",
                amount,
                "analysis",
                switch (status) {
                  case "PENDING" -> "通知已生成，尚无确认接收记录。";
                  case "UNREACHABLE" -> "最新联系结果为未联系上，应查看上门无人、电话无人接或其他原因。";
                  default -> "已客观记录拒绝接收，尊重本人意愿。";
                },
                "suggestion",
                status.equals("REFUSED") ? "核对已记录的反馈原话；不强制重复联系。" : "按负责人落实联系或再次上门，在服务跟进中登记结果。"));
    }
    var vaccineSummary = new ArrayList<Map<String, Object>>();
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
              + " (:community IS NULL OR COALESCE((SELECT g.community_id FROM"
              + " elder_assignment_history ah JOIN grid g ON g.id=ah.new_grid_id WHERE"
              + " ah.elder_id=e.id AND ah.effective_at<:cutoff AND ah.created_at<=:snapshot ORDER"
              + " BY ah.effective_at DESC,ah.id DESC LIMIT 1),e.community_id)=:community) AND"
              + " (:campaign IS NULL OR s.campaign_id=:campaign) AND (:owner IS NULL OR"
              + " :owner=(SELECT a.new_owner_id FROM elder_assignment_history a WHERE"
              + " a.elder_id=e.id AND a.effective_at<:cutoff AND a.created_at<=:snapshot ORDER BY"
              + " a.effective_at DESC,a.id DESC LIMIT 1))";
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
                  + " resulting_status='COMPLETED'),0) AS completed,COUNT(*) AS total,"
                  + " COALESCE(SUM(resulting_status='NOT_VACCINATED'),0) AS not_vaccinated,"
                  + " COALESCE(SUM(resulting_status='PARTIAL'),0) AS partial,"
                  + " COALESCE(SUM(resulting_status='COMPLETED'),0) AS all_completed,"
                  + " COALESCE(SUM(resulting_status='UNKNOWN'),0) AS unknown_status,"
                  + " COALESCE(SUM(eligibility_snapshot='UNKNOWN'),0) AS unknown_eligibility FROM ("
                  + vaccinated
                  + ") v GROUP BY campaign_id ORDER BY campaign_id",
              p);
      for (var v : vaccine) {
        String dimension = "CAMPAIGN:" + v.get("campaign_id");
        String campaignName =
            db.one(
                    "SELECT name FROM vaccine_campaign WHERE id=:id",
                    Db.p("id", v.get("campaign_id")))
                .get("name")
                .toString();
        var summary = new LinkedHashMap<>(v);
        summary.put("campaignName", campaignName);
        vaccineSummary.add(summary);
        String[] states = {"NOT_VACCINATED", "PARTIAL", "COMPLETED", "UNKNOWN"},
            columns = {"not_vaccinated", "partial", "all_completed", "unknown_status"};
        for (int i = 0; i < states.length; i++)
          metrics.add(
              metric(
                  "VACCINE_STATUS_COUNT",
                  Db.id(v, columns[i]),
                  Db.id(v, "total"),
                  dimension + ":STATUS:" + states[i]));
        metrics.add(
            metric(
                "ELIGIBILITY_UNKNOWN",
                Db.id(v, "unknown_eligibility"),
                Db.id(v, "total"),
                dimension));
        long missing = Db.id(v, "eligible") - Db.id(v, "completed");
        if (missing > 0 || Db.id(v, "unknown_eligibility") > 0)
          gaps.add(
              Db.p(
                  "title",
                  campaignName + "接种服务缺口",
                  "count",
                  missing,
                  "analysis",
                  "符合条件尚未完成接种 "
                      + missing
                      + " 人；全部记录中未接种 "
                      + Db.id(v, "not_vaccinated")
                      + " 人、部分完成 "
                      + Db.id(v, "partial")
                      + " 人、资格待核实 "
                      + Db.id(v, "unknown_eligibility")
                      + " 人。以上为登记状态，不能据此推断医学原因。",
                  "suggestion",
                  "先核实资格和接种事实，查看未联系、拒绝及预约情况；由负责网格员提供自愿预约协助，并按预约日期提前3天提醒。"));
        metrics.add(
            metric(
                "VACCINATION_RATE",
                Db.id(v, "completed"),
                Db.id(v, "eligible"),
                "CAMPAIGN:" + v.get("campaign_id")));
      }
    }
    var task =
        db.one(
            "SELECT COUNT(*) AS total,COALESCE(SUM(t.completed_at IS NOT NULL AND"
                + " t.completed_at<=t.due_at AND t.completed_at<:cutoff),0) AS on_time FROM"
                + " followup_task t JOIN notice_recipient r ON r.id=t.recipient_id JOIN notice n ON"
                + " n.id=r.notice_id WHERE (:community IS NULL OR t.community_id=:community) AND"
                + " t.due_at>=:start AND t.due_at<:end AND t.created_at<=:snapshot AND (:owner IS"
                + " NULL OR t.assignee_id=:owner) AND (:type IS NULL OR n.type=:type) AND"
                + " (:campaign IS NULL OR n.campaign_id=:campaign) AND NOT(t.status='CANCELLED' AND"
                + " t.updated_at<:cutoff)",
            p);
    metrics.add(metric("FOLLOWUP_ON_TIME", Db.id(task, "on_time"), Db.id(task, "total"), "ALL"));
    return Db.p(
        "communityId",
        scopeCommunity,
        "scopeLabel",
        scopeCommunity == null ? "全部社区" : "社区 " + scopeCommunity,
        "month",
        q.month(),
        "ownerId",
        owner,
        "noticeType",
        q.noticeType(),
        "campaignId",
        q.campaignId(),
        "startDate",
        period[0].toString(),
        "endDate",
        period[1].minusDays(1).toString(),
        "vaccineSummary",
        vaccineSummary,
        "serviceGaps",
        gaps,
        "metricVersion",
        "v2",
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
      if (!String.valueOf(a.communityId()).equals(String.valueOf(old.get("communityId"))))
        throw new Problem(409, "幂等键已用于其他社区的报表");
      if (!json.equal(old.get("query"), b.query()) || !b.format().equals(old.get("format")))
        throw new Problem(409, "幂等键已用于不同报表参数");
      return Db.p(
          "reportJobId", old.get("reportId"), "jobId", existing.get().get("id"), "replayed", true);
    }
    var period = period(b.query());
    long report =
        db.insert(
            "report_job",
            Db.p(
                "community_id",
                a.communityId(),
                "report_type",
                "MONTHLY_SERVICE",
                "period_start",
                period[0],
                "period_end_exclusive",
                period[1],
                "scope_owner_id",
                owner,
                "filter_json",
                json.write(b.query()),
                "metric_version",
                "v2",
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
            number(filter.get("campaignId")),
            number(filter.get("communityId")),
            filter.get("startDate") == null
                ? null
                : LocalDate.parse(filter.get("startDate").toString()),
            filter.get("endDate") == null
                ? null
                : LocalDate.parse(filter.get("endDate").toString()));
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
    String[] headers = {"指标", "维度", "分子/人数", "分母", "比例", "说明"};
    @SuppressWarnings("unchecked")
    var gaps = (List<Map<String, Object>>) data.get("serviceGaps");
    String range = data.get("startDate") + "至" + data.get("endDate");
    if (excel) {
      try (var workbook = new XSSFWorkbook()) {
        var sheet = workbook.createSheet("养老服务月报");
        var title = sheet.createRow(0);
        title
            .createCell(0)
            .setCellValue(
                range + " " + data.get("scopeLabel") + " 养老服务报表 v2 快照 " + data.get("snapshotAt"));
        var header = sheet.createRow(1);
        for (int c = 0; c < headers.length; c++) header.createCell(c).setCellValue(headers[c]);
        int i = 2;
        for (var r : rows) {
          var row = sheet.createRow(i++);
          String[] vals = values(r);
          for (int c = 0; c < vals.length; c++) row.createCell(c).setCellValue(vals[c]);
        }
        for (int c = 0; c < headers.length; c++) sheet.setColumnWidth(c, c < 2 ? 7000 : 4500);
        var gapSheet = workbook.createSheet("服务缺口分析");
        String[] gapHeaders = {"服务事项", "缺口数量", "登记情况分析", "后续措施"};
        var gh = gapSheet.createRow(0);
        for (int c = 0; c < 4; c++) gh.createCell(c).setCellValue(gapHeaders[c]);
        int gi = 1;
        for (var gap : gaps) {
          var gr = gapSheet.createRow(gi++);
          String[] keys = {"title", "count", "analysis", "suggestion"};
          for (int c = 0; c < 4; c++)
            gr.createCell(c).setCellValue(Objects.toString(gap.get(keys[c]), ""));
        }
        for (int c = 0; c < 4; c++) gapSheet.setColumnWidth(c, c < 2 ? 6000 : 16000);
        if (gaps.isEmpty())
          gapSheet.createRow(1).createCell(0).setCellValue("本期已登记资料暂无待办缺口；请继续核实资料完整性。");
        workbook.write(bytes);
      }
    } else {
      try (var doc = new XWPFDocument()) {
        doc.createParagraph().createRun().setText(range + " " + data.get("scopeLabel") + " 养老服务报表");
        doc.createParagraph().createRun().setText("口径 v2 快照 " + data.get("snapshotAt") + " 仅汇总数据");
        var table = doc.createTable(rows.size() + 1, headers.length);
        for (int c = 0; c < headers.length; c++) table.getRow(0).getCell(c).setText(headers[c]);
        for (int r = 0; r < rows.size(); r++) {
          String[] vals = values(rows.get(r));
          for (int c = 0; c < vals.length; c++) table.getRow(r + 1).getCell(c).setText(vals[c]);
        }
        doc.createParagraph().createRun().setText("服务缺口分析（基于已登记数据）");
        for (var gap : gaps)
          doc.createParagraph()
              .createRun()
              .setText(
                  gap.get("title")
                      + "："
                      + gap.get("count")
                      + "。"
                      + gap.get("analysis")
                      + " 建议："
                      + gap.get("suggestion"));
        if (gaps.isEmpty()) doc.createParagraph().createRun().setText("本期已登记资料暂无待办缺口；请继续核实资料完整性。");
        doc.write(bytes);
      }
    }
    long file =
        files.save(
            a,
            "REPORT",
            "service-" + data.get("startDate") + "-" + data.get("endDate") + (excel ? ".xlsx" : ".docx"),
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
      metricName(row.get("code").toString()),
      dimensionName(row.get("dimension").toString()),
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

  public static LocalDate[] period(Query q) {
    if (q.startDate() != null || q.endDate() != null) {
      Problem.require(q.startDate() != null && q.endDate() != null, "开始和结束日期须同时填写");
      Problem.require(
          !q.startDate().isAfter(q.endDate())
              && java.time.temporal.ChronoUnit.DAYS.between(q.startDate(), q.endDate()) <= 3660,
          "统计日期范围不正确或超过10年");
      return new LocalDate[] {q.startDate(), q.endDate().plusDays(1)};
    }
    YearMonth m = YearMonth.parse(q.month());
    return new LocalDate[] {m.atDay(1), m.plusMonths(1).atDay(1)};
  }

  private String metricName(String code) {
    return switch (code) {
      case "NOTICE_COVERAGE" -> "通知覆盖率";
      case "SERVICE_CLOSURE" -> "服务闭环率";
      case "SATISFACTION" -> "满意度";
      case "FEEDBACK_RATE" -> "反馈率";
      case "VACCINATION_RATE" -> "疫苗接种率";
      case "FOLLOWUP_ON_TIME" -> "跟进按期完成率";
      case "CHANNEL_USAGE" -> "通知方式使用率";
      case "NOTICE_STATUS_COUNT" -> "通知接收状态数量";
      case "VACCINE_STATUS_COUNT" -> "疫苗接种状态人数";
      case "ELIGIBILITY_UNKNOWN" -> "接种资格待核实人数";
      default -> code;
    };
  }

  private String dimensionName(String dimension) {
    String value = dimension;
    if (value.startsWith("CAMPAIGN:")) {
      String[] parts = value.split(":");
      value =
          db.one(
                      "SELECT name FROM vaccine_campaign WHERE id=:id",
                      Db.p("id", Long.parseLong(parts[1])))
                  .get("name")
                  .toString()
              + (parts.length > 3 ? " / " + parts[3] : "");
    }
    return value
        .replace("NOT_VACCINATED", "未接种")
        .replace("COMPLETED", "已完成接种")
        .replace("PARTIAL", "部分完成")
        .replace("UNREACHABLE", "未联系上")
        .replace("CONFIRMED", "已确认接收")
        .replace("REFUSED", "拒绝接收")
        .replace("PENDING", "待联系")
        .replace("UNKNOWN", "待核实")
        .replace("HOME_VISIT", "上门告知")
        .replace("FAMILY_RELAY", "家属转告")
        .replace("BULLETIN", "公告栏张贴")
        .replace("SMS", "短信通知")
        .replace("ALL", "全部");
  }

  private Long number(Object n) {
    return n == null ? null : Long.valueOf(n.toString());
  }
}
