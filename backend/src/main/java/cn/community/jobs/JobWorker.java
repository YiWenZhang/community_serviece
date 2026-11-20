package cn.community.jobs;

import cn.community.common.*;
import cn.community.identity.*;
import cn.community.notice.RuleService;
import cn.community.notice.SmsService;
import cn.community.reporting.ReportService;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 单业务服务内持久工作器；租约和幂等保证崩溃后可重新认领。 */
@Component
public class JobWorker {
  private final Db db;
  private final Json json;
  private final TransactionTemplate tx;
  private final Auth auth;
  private final RuleService rules;
  private final ReportService reports;
  private final JobService jobs;
  private final SmsService sms;
  private final boolean enabled;
  private final String process = UUID.randomUUID().toString();

  public JobWorker(
      Db db,
      Json json,
      TransactionTemplate tx,
      Auth auth,
      RuleService rules,
      ReportService reports,
      JobService jobs,
      SmsService sms,
      @Value("${app.scheduler-enabled}") boolean enabled) {
    this.db = db;
    this.json = json;
    this.tx = tx;
    this.auth = auth;
    this.rules = rules;
    this.reports = reports;
    this.jobs = jobs;
    this.sms = sms;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelay = 5000)
  public void tick() {
    if (enabled) runOne();
  }

  public boolean runOne() {
    var job =
        tx.execute(
            s -> {
              var row =
                  db.optional(
                      "SELECT * FROM job_run WHERE (status='PENDING' AND"
                          + " next_run_at<=UTC_TIMESTAMP(3)) OR (status='PROCESSING' AND"
                          + " lease_until<UTC_TIMESTAMP(3)) ORDER BY id LIMIT 1 FOR UPDATE SKIP"
                          + " LOCKED",
                      Map.of());
              if (row.isEmpty()) return null;
              var j = row.get();
              db.update(
                  "UPDATE job_run SET"
                      + " status='PROCESSING',lease_owner=:process,lease_until=UTC_TIMESTAMP(3)+INTERVAL"
                      + " 30 MINUTE,version=version+1 WHERE id=:id",
                  Db.p("process", process, "id", j.get("id")));
              return j;
            });
    if (job == null) return false;
    var payload = json.map(job.get("payload_json"));
    try {
      var actor =
          auth.worker(
              Long.parseLong(payload.get("actorId").toString()),
              Long.parseLong(payload.get("communityId").toString()));
      switch (job.get("job_type").toString()) {
        case "NOTICE_GENERATION" ->
            rules.generate(
                actor,
                Long.parseLong(payload.get("ruleId").toString()),
                payload.get("period").toString(),
                job.get("business_key").toString());
        case "SMS_DISPATCH" -> sms.dispatch(actor, Db.id(job, "id"), payload);
        case "REPORT_EXPORT" ->
            reports.export(actor, Long.parseLong(payload.get("reportId").toString()));
        default -> throw new Problem(501, "该外部作业类型尚未接入");
      }
      db.update(
          "UPDATE job_run SET"
              + " status='COMPLETED',lease_owner=NULL,lease_until=NULL,error_summary=NULL,version=version+1"
              + " WHERE id=:id AND lease_owner=:process",
          Db.p("id", job.get("id"), "process", process));
    } catch (Exception e) {
      String reason = e instanceof Problem ? e.getMessage() : "作业失败，请联系管理员检查服务日志";
      boolean terminal = Db.id(job, "retry_count") + 1 >= Db.id(job, "max_retries");
      db.update(
          "UPDATE job_run SET"
              + " status=:status,retry_count=retry_count+1,next_run_at=UTC_TIMESTAMP(3)+INTERVAL 1"
              + " MINUTE,lease_owner=NULL,lease_until=NULL,error_summary=:error,version=version+1"
              + " WHERE id=:id AND lease_owner=:process",
          Db.p(
              "status",
              terminal ? "FAILED" : "PENDING",
              "error",
              reason,
              "id",
              job.get("id"),
              "process",
              process));
      if (payload.get("reportId") != null)
        db.update(
            "UPDATE report_job SET status=:status,error_summary=:error WHERE id=:id",
            Db.p(
                "status",
                terminal ? "FAILED" : "PENDING",
                "error",
                reason,
                "id",
                payload.get("reportId")));
      org.slf4j.LoggerFactory.getLogger(JobWorker.class)
          .warn("job failed id={} type={}", job.get("id"), e.getClass().getSimpleName());
    }
    return true;
  }

  @Scheduled(cron = "0 10 * * * *", zone = "Asia/Shanghai")
  public void queueRules() {
    if (!enabled) return;
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    for (var rule : db.list("SELECT * FROM notice_rule WHERE enabled=1", Map.of())) {
      var config = json.map(rule.get("config_json"));
      int lead =
          Integer.parseInt(
              config
                  .getOrDefault(
                      "leadDays", config.getOrDefault("prepare_days_before_next_month", 7))
                  .toString());
      var periods = new LinkedHashSet<String>();
      periods.add(YearMonth.from(today).toString());
      if ("BIRTHDAY".equals(rule.get("notice_type")))
        periods.add(YearMonth.from(today.plusDays(lead)).toString());
      for (var user :
          db.list(
              "SELECT DISTINCT u.id FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id JOIN"
                  + " sys_role r ON r.id=ur.role_id WHERE EXISTS(SELECT 1 FROM user_community m"
                  + " WHERE m.user_id=u.id AND m.community_id=:community AND m.status='ACTIVE') AND"
                  + " u.status='ACTIVE' AND r.code='GRID_WORKER' AND r.status='ACTIVE'",
              Db.p("community", rule.get("community_id")))) {
        try {
          var actor = auth.worker(Db.id(user, "id"), Db.id(rule, "community_id"));
          actor.require("notice:create");
          for (String period : periods)
            jobs.enqueue(
                actor,
                "NOTICE_GENERATION",
                "auto:" + rule.get("id") + ":" + today + ":" + period + ":" + actor.id(),
                Db.p("ruleId", rule.get("id"), "period", period));
        } catch (Problem ignored) {
          /*账号停用或权限撤销后不再派发。*/
        }
      }
    }
  }

  // Daily catch-up: the stable month key still creates one report per statistician per month.
  @Scheduled(cron = "0 30 1 * * *", zone = "Asia/Shanghai")
  public void queueMonthlyReports() {
    if (!enabled) return;
    String month = YearMonth.now(ZoneId.of("Asia/Shanghai")).minusMonths(1).toString();
    for (var user :
        db.list(
            "SELECT DISTINCT u.id FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id JOIN"
                + " sys_role r ON r.id=ur.role_id WHERE u.status='ACTIVE' AND r.code='STATISTICIAN'"
                + " AND r.status='ACTIVE'",
            Map.of())) {
      try {
        var actor = auth.worker(Db.id(user, "id"));
        actor.require("report:export:community");
        reports.enqueue(
            actor,
            "auto-month-" + month,
            new ReportService.Export(new ReportService.Query(month, null, null, null), "XLSX"));
      } catch (Problem ignored) {
        /*授权被撤回时不创建新的社区报表。*/
      }
    }
  }
}
