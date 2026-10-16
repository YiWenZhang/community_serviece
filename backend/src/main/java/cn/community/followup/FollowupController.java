package cn.community.followup;

import cn.community.common.*;
import cn.community.elder.ElderService;
import cn.community.identity.*;
import cn.community.notice.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/followups")
@Tag(name = "05 服务跟进")
public class FollowupController {
  public record Record(
      @Min(0) int version,
      @Pattern(regexp = "RESOLVED|RETRY_REQUIRED|REFUSED|NO_LONGER_APPLICABLE") @NotNull
          String outcome,
      @NotBlank @Size(max = 1000) String resultText,
      OffsetDateTime nextDueAt,
      @Valid NoticeService.Attempt attempt) {}

  public record NewTask(
      @NotNull Long recipientId,
      @Pattern(regexp = "RECONTACT|VERIFY_APPOINTMENT|PRE_VACCINE_REMINDER") @NotNull String type,
      @NotNull OffsetDateTime dueAt) {}

  private final Db db;
  private final Auth auth;
  private final NoticeService notices;
  private final Privacy privacy;
  private final Audit audit;
  private final Json json;
  private final Crypto crypto;

  public FollowupController(
      Db db,
      Auth auth,
      NoticeService notices,
      Privacy privacy,
      Audit audit,
      Json json,
      Crypto crypto) {
    this.db = db;
    this.auth = auth;
    this.notices = notices;
    this.privacy = privacy;
    this.audit = audit;
    this.json = json;
    this.crypto = crypto;
  }

  @GetMapping
  @Operation(summary = "本人跟进任务分页，支持状态及逾期筛选")
  public Object list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "false") boolean overdue) {
    var a = auth.require("followup:read");
    var p = a.scope();
    String where =
        " FROM followup_task t JOIN notice_recipient r ON r.id=t.recipient_id JOIN elder_profile e"
            + " ON e.id=r.elder_id WHERE t.community_id=:community AND e.owner_user_id=:actor";
    if (status != null) {
      where += " AND t.status=:status";
      p.put("status", status);
    }
    if (overdue) where += " AND t.status IN ('PENDING','PROCESSING') AND t.due_at<UTC_TIMESTAMP(3)";
    return Api.ok(
        db.page(
            "SELECT t.*,IF(t.status IN ('PENDING','PROCESSING') AND t.due_at<UTC_TIMESTAMP(3),1,0)"
                + " AS overdue"
                + where
                + " ORDER BY t.due_at,t.id",
            "SELECT COUNT(*)" + where,
            p,
            page,
            pageSize));
  }

  @GetMapping("/{id}")
  @Operation(summary = "跟进任务及执行历史")
  public Object detail(@PathVariable long id) {
    var a = auth.require("followup:read");
    var t = db.one("SELECT * FROM followup_task WHERE id=:id", Db.p("id", id));
    notices.recipient(a, Db.id(t, "recipient_id"), false);
    t.put(
        "records",
        db.list(
            "SELECT * FROM followup_record WHERE task_id=:id ORDER BY performed_at,id",
            Db.p("id", id)));
    return Api.ok(t);
  }

  @PostMapping
  @Transactional
  @Operation(summary = "为本人未终结通知创建人工跟进，同类型未完成任务去重")
  public Object create(@Valid @RequestBody NewTask b) {
    var a = auth.require("followup:update");
    var r = notices.recipient(a, b.recipientId(), true);
    Problem.require(Set.of("OPEN", "FOLLOWING").contains(r.get("workflow_status")), "对象已终结");
    notices.task(
        a,
        b.recipientId(),
        b.type(),
        null,
        0,
        b.dueAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
    return Api.ok(
        db.one(
            "SELECT * FROM followup_task WHERE open_dedupe_key=:key",
            Db.p("key", "recipient:" + b.recipientId() + ":" + b.type())));
  }

  @PostMapping("/{id}/records")
  @Transactional
  @Operation(summary = "幂等记录跟进，原子结束旧任务并维护接收及后续任务")
  public Object record(
      @PathVariable long id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Record b) {
    var a = auth.require("followup:update");
    NoticeService.validateKey(key);
    privacy.check(b.resultText());
    var t0 = db.one("SELECT recipient_id FROM followup_task WHERE id=:id", Db.p("id", id));
    long recipientId = Db.id(t0, "recipient_id");
    var r = notices.recipient(a, recipientId, true);
    var t = db.one("SELECT * FROM followup_task WHERE id=:id FOR UPDATE", Db.p("id", id));
    String hash =
        crypto.digest(
            json.write(
                Db.p(
                    "outcome",
                    b.outcome(),
                    "text",
                    b.resultText(),
                    "nextDueAt",
                    b.nextDueAt(),
                    "attempt",
                    b.attempt() == null ? null : notices.attemptHash(b.attempt()))));
    var prior =
        db.optional(
            "SELECT id FROM followup_record WHERE task_id=:id AND idempotency_key=:key",
            Db.p("id", id, "key", key));
    if (prior.isPresent()) {
      long recordId = Db.id(prior.get(), "id");
      notices.checkReplay("FOLLOWUP_RECORDED", recordId, hash);
      return Api.ok(Db.p("id", recordId, "replayed", true));
    }
    Problem.require(Db.id(t, "assignee_id") == a.id(), "仅当前经办人可执行任务");
    Problem.require(Set.of("PENDING", "PROCESSING").contains(t.get("status")), "任务已结束");
    Problem.require(Set.of("OPEN", "FOLLOWING").contains(r.get("workflow_status")), "通知对象已终结");
    if (b.outcome().equals("RETRY_REQUIRED"))
      Problem.require(
          b.nextDueAt() != null && b.nextDueAt().toInstant().isAfter(Instant.now()), "再次跟进需未来期限");
    if (b.outcome().equals("REFUSED"))
      Problem.require(
          b.attempt() != null && b.attempt().result().equals("REFUSED"), "拒绝必须同时提交拒绝触达记录");
    if (b.outcome().equals("RESOLVED")
        && Set.of("RECONTACT", "PRE_VACCINE_REMINDER").contains(t.get("task_type")))
      Problem.require(
          b.attempt() != null && b.attempt().result().equals("CONFIRMED"), "联系或接种前提醒解决必须有确认接收记录");
    if (b.outcome().equals("RESOLVED") && t.get("task_type").equals("VERIFY_APPOINTMENT")) {
      var appointment =
          db.one(
              "SELECT v.appointment_date FROM elder_vaccine_status v JOIN notice n ON"
                  + " n.campaign_id=v.campaign_id WHERE n.id=:notice AND v.elder_id=:elder AND"
                  + " v.appointment_date IS NOT NULL",
              Db.p("notice", r.get("notice_id"), "elder", r.get("elder_id")));
      LocalDate date = ((java.sql.Date) appointment.get("appointment_date")).toLocalDate();
      notices.task(
          a,
          recipientId,
          "PRE_VACCINE_REMINDER",
          null,
          0,
          date.minusDays(3)
              .atTime(9, 0)
              .atZone(ZoneId.of("Asia/Shanghai"))
              .withZoneSameInstant(ZoneOffset.UTC)
              .toLocalDateTime());
      db.update(
          "UPDATE notice_recipient SET service_due_date=:date WHERE id=:id",
          Db.p("date", date, "id", recipientId));
    }
    Problem.changed(
        db.update(
            "UPDATE followup_task SET"
                + " status='COMPLETED',completed_at=UTC_TIMESTAMP(3),open_dedupe_key=NULL,version=version+1,updated_by=:actor"
                + " WHERE id=:id AND version=:v",
            Db.p("actor", a.id(), "id", id, "v", b.version())));
    Long attemptId = null;
    if (b.attempt() != null) {
      @SuppressWarnings("unchecked")
      var result =
          (Map<String, Object>)
              notices.attempt(
                  a,
                  recipientId,
                  "follow-" + crypto.digest(id + ":" + key).substring(0, 48),
                  b.attempt());
      attemptId = ((Number) result.get("id")).longValue();
    }
    if (b.outcome().equals("RETRY_REQUIRED")) {
      Problem.require(
          !Set.of("CONFIRMED", "REFUSED")
              .contains(
                  db.one(
                          "SELECT receipt_status FROM notice_recipient WHERE id=:id",
                          Db.p("id", recipientId))
                      .get("receipt_status")),
          "已接收或拒绝不能继续重试");
      Problem.require(Db.id(t, "retry_no") < 3, "已达到重试上限，应记录不再适用并说明终结原因");
      notices.task(
          a,
          recipientId,
          t.get("task_type").toString(),
          attemptId,
          (int) Db.id(t, "retry_no") + 1,
          b.nextDueAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
    }
    if (b.outcome().equals("NO_LONGER_APPLICABLE")) {
      String reason = Db.id(t, "retry_no") >= 3 ? "RETRY_EXHAUSTED" : "NO_LONGER_APPLICABLE";
      db.update(
          "UPDATE notice_recipient SET"
              + " workflow_status='CLOSED',closed_at=UTC_TIMESTAMP(3),closed_reason=:reason,version=version+1"
              + " WHERE id=:id",
          Db.p("reason", reason, "id", recipientId));
      notices.event(
          a,
          r,
          "CLOSED",
          r.get("receipt_status").toString(),
          "CLOSED",
          attemptId,
          b.resultText(),
          "follow-close-" + key);
      notices.closeTasks(a, recipientId, b.resultText());
      notices.completeBatch(Db.id(r, "notice_id"));
    }
    long recordId =
        db.insert(
            "followup_record",
            Db.p(
                "task_id",
                id,
                "performed_at",
                ElderService.utc(),
                "outcome",
                b.outcome(),
                "result_text",
                b.resultText(),
                "delivery_attempt_id",
                attemptId,
                "next_due_at",
                b.nextDueAt() == null
                    ? null
                    : b.nextDueAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
                "idempotency_key",
                key,
                "created_by",
                a.id()));
    audit.log(a, "FOLLOWUP_RECORDED", "FOLLOWUP_RECORD", recordId, Map.of("requestHash", hash));
    return Api.ok(Db.p("id", recordId, "taskId", id, "status", "COMPLETED"));
  }
}
