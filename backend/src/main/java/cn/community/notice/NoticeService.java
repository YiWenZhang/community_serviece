package cn.community.notice;

import cn.community.common.*;
import cn.community.elder.ElderService;
import cn.community.files.FileService;
import cn.community.identity.*;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 通知接收、事件、跟进任务与审计同事务提交。 */
@Service
public class NoticeService {
  @io.swagger.v3.oas.annotations.media.Schema(name = "NoticeDraft")
  public record Draft(
      @Pattern(regexp = "VACCINE|BIRTHDAY|LECTURE|OTHER") @NotNull String type,
      @NotBlank @Size(max = 120) String title,
      @NotBlank @Size(max = 5000) String content,
      @NotEmpty @Size(max = 1000) Set<Long> elderIds,
      Long campaignId,
      @NotBlank @Size(max = 32) String periodKey) {}

  public record Version(@Min(0) int version) {}

  public record Reason(@Min(0) int version, @NotBlank @Size(max = 300) String reason) {}

  public record Attempt(
      @Min(0) int version,
      @Pattern(regexp = "HOME_VISIT|FAMILY_RELAY|BULLETIN|SMS") @NotNull String channel,
      @Pattern(regexp = "POSTED|CONFIRMED|UNREACHABLE|REFUSED") @NotNull String result,
      @Size(max = 32) String unreachableReason,
      @Size(max = 300) String reasonText,
      @Size(max = 500) String feedbackQuote,
      @Size(max = 40) String familyNameMasked,
      @Size(max = 32) String familyRelation,
      Boolean relayConfirmed,
      @NotNull Set<Long> fileIds) {}

  public record Feedback(
      @Min(0) int revision,
      @Pattern(regexp = "SATISFIED|NEUTRAL|DISSATISFIED") @NotNull String rating,
      @Pattern(regexp = "ELDER|FAMILY") @NotNull String respondentType,
      @Size(max = 500) String text) {}

  private final Db db;
  private final ElderService elders;
  private final FileService files;
  private final Audit audit;
  private final Privacy privacy;
  private final Json json;
  private final Crypto crypto;

  public NoticeService(
      Db db,
      ElderService elders,
      FileService files,
      Audit audit,
      Privacy privacy,
      Json json,
      Crypto crypto) {
    this.db = db;
    this.elders = elders;
    this.files = files;
    this.audit = audit;
    this.privacy = privacy;
    this.json = json;
    this.crypto = crypto;
  }

  public Map<String, Object> recipient(Actor a, long id, boolean lock) {
    return db.one(
        "SELECT r.* FROM notice_recipient r JOIN elder_profile e ON e.id=r.elder_id WHERE r.id=:id"
            + " AND r.community_id=:community AND (e.owner_user_id=:actor OR "
            + a.admin()
            + ")"
            + (lock ? " FOR UPDATE" : ""),
        Db.p("id", id, "community", a.communityId(), "actor", a.id()));
  }

  public Object list(Actor a, int page, int size) {
    a.require("notice:read");
    String where =
        " FROM notice n WHERE n.community_id=:community AND ((n.created_by=:actor OR "
            + a.admin()
            + ") OR EXISTS (SELECT"
            + " 1 FROM notice_recipient r JOIN elder_profile e ON e.id=r.elder_id WHERE"
            + " r.notice_id=n.id AND (e.owner_user_id=:actor OR "
            + a.admin()
            + ")))";
    return db.page(
        "SELECT n.id,n.notice_no,n.type,n.title,n.status,n.active_at,n.version"
            + where
            + " ORDER BY n.id DESC",
        "SELECT COUNT(*)" + where,
        a.scope(),
        page,
        size);
  }

  public Object detail(Actor a, long id) {
    a.require("notice:read");
    var p = Db.p("id", id, "community", a.communityId(), "actor", a.id());
    var n =
        db.one(
            "SELECT n.* FROM notice n WHERE n.id=:id AND n.community_id=:community AND"
                + " ((n.created_by=:actor OR "
                + a.admin()
                + ") OR EXISTS (SELECT 1 FROM notice_recipient r JOIN"
                + " elder_profile e ON e.id=r.elder_id WHERE r.notice_id=n.id AND"
                + " (e.owner_user_id=:actor OR "
                + a.admin()
                + ")))",
            p);
    n.put(
        "recipients",
        db.list(
            "SELECT r.* FROM notice_recipient r JOIN elder_profile e ON e.id=r.elder_id WHERE"
                + " r.notice_id=:id AND (e.owner_user_id=:actor OR "
                + a.admin()
                + ")",
            p));
    return n;
  }

  @Transactional
  public Object create(Actor a, Draft b) {
    a.require("notice:create");
    privacy.check(b.title());
    privacy.check(b.content());
    if (b.type().equals("VACCINE")) {
      Problem.require(b.campaignId() != null, "疫苗通知必须选择周期");
      db.one(
          "SELECT id FROM vaccine_campaign WHERE id=:id AND community_id=:community AND"
              + " status='ACTIVE'",
          Db.p("id", b.campaignId(), "community", a.communityId()));
    }
    for (long id : b.elderIds()) {
      var e = elders.owned(a, id, true);
      Problem.require("ACTIVE".equals(e.get("status")), "只能为启用档案创建通知");
    }
    long id =
        db.insert(
            "notice",
            Db.p(
                "community_id",
                a.communityId(),
                "notice_no",
                "NT-" + UUID.randomUUID().toString().replace("-", ""),
                "type",
                b.type(),
                "title",
                b.title(),
                "content",
                b.content(),
                "source",
                "MANUAL",
                "period_key",
                b.periodKey(),
                "campaign_id",
                b.campaignId(),
                "created_by",
                a.id()));
    for (long elder : b.elderIds())
      db.insert(
          "notice_recipient",
          Db.p(
              "community_id",
              a.communityId(),
              "notice_id",
              id,
              "elder_id",
              elder,
              "owner_user_id",
              Db.id(elders.owned(a, elder, false), "owner_user_id"),
              "owner_at_issue",
              Db.id(elders.owned(a, elder, false), "owner_user_id"),
              "created_by",
              a.id()));
    audit.log(a, "NOTICE_CREATED", "NOTICE", id, Map.of("recipientCount", b.elderIds().size()));
    return Db.p("id", id, "version", 0, "status", "DRAFT");
  }

  @Transactional
  public Object activate(Actor a, long id, int version) {
    a.require("notice:create");
    var n =
        db.one(
            "SELECT * FROM notice WHERE id=:id AND community_id=:community AND (created_by=:actor"
                + " OR "
                + a.admin()
                + ")"
                + " FOR UPDATE",
            Db.p("id", id, "community", a.communityId(), "actor", a.id()));
    Problem.require("DRAFT".equals(n.get("status")), "只有草稿可以激活");
    var recipients =
        db.list(
            "SELECT r.*,e.status AS elder_status,e.owner_user_id AS current_owner FROM"
                + " notice_recipient r JOIN elder_profile e ON e.id=r.elder_id WHERE"
                + " r.notice_id=:id FOR UPDATE",
            Db.p("id", id));
    for (var r : recipients)
      Problem.require(
          (a.admin() || Db.id(r, "current_owner") == a.id())
              && "ACTIVE".equals(r.get("elder_status")),
          "名单含已移交或停用档案，请重新创建通知");
    Problem.changed(
        db.update(
            "UPDATE notice SET"
                + " status='ACTIVE',active_at=UTC_TIMESTAMP(3),version=version+1,updated_by=:actor"
                + " WHERE id=:id AND version=:v",
            Db.p("actor", a.id(), "id", id, "v", version)));
    for (var r : recipients) {
      long rid = Db.id(r, "id");
      db.update(
          "UPDATE notice_recipient SET"
              + " issued_at=UTC_TIMESTAMP(3),owner_at_issue=owner_user_id,version=version+1 WHERE"
              + " id=:id",
          Db.p("actor", a.id(), "id", rid));
      event(a, r, "ISSUED", "PENDING", "OPEN", null, "通知激活", "activate-" + rid);
      if ("VACCINE".equals(n.get("type"))) vaccineTask(a, r, n);
    }
    audit.log(a, "NOTICE_ACTIVATED", "NOTICE", id, Map.of());
    return Db.p("id", id, "version", version + 1, "status", "ACTIVE");
  }

  private void vaccineTask(Actor a, Map<String, Object> r, Map<String, Object> n) {
    var v =
        db.optional(
            "SELECT appointment_date FROM elder_vaccine_status WHERE elder_id=:elder AND"
                + " campaign_id=:campaign",
            Db.p("elder", r.get("elder_id"), "campaign", n.get("campaign_id")));
    LocalDate date =
        v.isPresent() && v.get().get("appointment_date") != null
            ? ((java.sql.Date) v.get().get("appointment_date")).toLocalDate()
            : null;
    if (date == null)
      task(a, Db.id(r, "id"), "VERIFY_APPOINTMENT", null, 0, ElderService.utc().plusDays(1));
    else {
      db.update(
          "UPDATE notice_recipient SET service_due_date=:date WHERE id=:id",
          Db.p("date", date, "id", r.get("id")));
      task(
          a,
          Db.id(r, "id"),
          "PRE_VACCINE_REMINDER",
          null,
          0,
          date.minusDays(3)
              .atTime(9, 0)
              .atZone(ZoneId.of("Asia/Shanghai"))
              .withZoneSameInstant(ZoneOffset.UTC)
              .toLocalDateTime());
    }
  }

  @Transactional
  public Object cancel(Actor a, long id, Reason b) {
    a.require("notice:create");
    privacy.check(b.reason());
    var n =
        db.one(
            "SELECT * FROM notice WHERE id=:id AND community_id=:community AND (created_by=:actor"
                + " OR "
                + a.admin()
                + ")"
                + " FOR UPDATE",
            Db.p("id", id, "community", a.communityId(), "actor", a.id()));
    Problem.require(!Set.of("CANCELLED", "COMPLETED").contains(n.get("status")), "通知已经终结");
    var rs =
        db.list(
            "SELECT r.* FROM notice_recipient r JOIN elder_profile e ON e.id=r.elder_id WHERE"
                + " r.notice_id=:id FOR UPDATE",
            Db.p("id", id));
    for (var r : rs) {
      elders.owned(a, Db.id(r, "elder_id"), false);
      if (Set.of("OPEN", "FOLLOWING").contains(r.get("workflow_status"))) {
        db.update(
            "UPDATE notice_recipient SET"
                + " workflow_status='CANCELLED',closed_at=UTC_TIMESTAMP(3),closed_reason='CANCELLED',version=version+1"
                + " WHERE id=:id",
            Db.p("id", r.get("id")));
        event(
            a,
            r,
            "CANCELLED",
            r.get("receipt_status").toString(),
            "CANCELLED",
            null,
            b.reason(),
            "cancel-" + r.get("id") + "-" + b.version());
        closeTasks(a, Db.id(r, "id"), b.reason());
      }
    }
    Problem.changed(
        db.update(
            "UPDATE notice SET status='CANCELLED',cancel_reason=:reason,version=version+1 WHERE"
                + " id=:id AND version=:v",
            Db.p("reason", b.reason(), "id", id, "v", b.version())));
    audit.log(a, "NOTICE_CANCELLED", "NOTICE", id, Map.of("reason", b.reason()));
    return Db.p("id", id, "status", "CANCELLED");
  }

  @Transactional
  public Object attempt(Actor a, long rid, String key, Attempt b) {
    a.require("notice:receive");
    validateKey(key);
    var r = recipient(a, rid, true);
    var n = db.one("SELECT * FROM notice WHERE id=:id", Db.p("id", r.get("notice_id")));
    String hash = attemptHash(b);
    var existing =
        db.optional(
            "SELECT id FROM delivery_attempt WHERE recipient_id=:id AND idempotency_key=:key",
            Db.p("id", rid, "key", key));
    if (existing.isPresent()) {
      long aid = Db.id(existing.get(), "id");
      checkReplay("NOTICE_ATTEMPT_CREATED", aid, hash);
      return Db.p("id", aid, "replayed", true);
    }
    Problem.require("ACTIVE".equals(n.get("status")), "通知尚未激活或已经终结");
    Problem.require(
        Set.of("OPEN", "FOLLOWING").contains(r.get("workflow_status")), "接收对象已终结，需先提交有原因的重开请求");
    if (Db.id(r, "version") != b.version()) throw new Problem(409, "接收对象版本已变化");
    validateAttempt(b);
    for (long id : b.fileIds()) files.attachable(a, id);
    long aid =
        db.insert(
            "delivery_attempt",
            Db.p(
                "recipient_id",
                rid,
                "channel",
                b.channel(),
                "attempted_at",
                ElderService.utc(),
                "result",
                b.result(),
                "unreachable_reason",
                b.unreachableReason(),
                "reason_text",
                b.reasonText(),
                "feedback_quote",
                b.feedbackQuote(),
                "family_name_masked",
                b.familyNameMasked(),
                "family_relation",
                b.familyRelation(),
                "relay_confirmed",
                b.relayConfirmed() == null ? null : b.relayConfirmed() ? 1 : 0,
                "idempotency_key",
                key,
                "created_by",
                a.id()));
    for (long file : b.fileIds())
      db.insert(
          "delivery_attachment",
          Db.p(
              "delivery_attempt_id",
              aid,
              "file_id",
              file,
              "evidence_type",
              b.channel().equals("HOME_VISIT")
                  ? "SIGN_CONFIRM"
                  : b.channel().equals("BULLETIN") ? "BULLETIN_PHOTO" : "OTHER_ALLOWED",
              "created_by",
              a.id()));
    String receipt = b.result().equals("POSTED") ? r.get("receipt_status").toString() : b.result();
    String flow = Set.of("CONFIRMED", "REFUSED").contains(receipt) ? "CLOSED" : "FOLLOWING";
    // 已接收初次疫苗通知，仍须保留预约前3天提醒；最终提醒执行后才关闭业务。
    if (receipt.equals("CONFIRMED")
        && db.count(
                "SELECT COUNT(*) FROM followup_task WHERE recipient_id=:id AND"
                    + " task_type='PRE_VACCINE_REMINDER' AND status IN ('PENDING','PROCESSING')",
                Db.p("id", rid))
            > 0) {
      flow = "FOLLOWING";
      db.update(
          "UPDATE followup_task SET"
              + " status='CANCELLED',cancel_reason='已确认初次通知',open_dedupe_key=NULL,version=version+1"
              + " WHERE recipient_id=:id AND task_type IN ('RECONTACT','VERIFY_APPOINTMENT') AND"
              + " status IN ('PENDING','PROCESSING')",
          Db.p("id", rid));
    }
    db.update(
        "UPDATE notice_recipient SET"
            + " receipt_status=:receipt,workflow_status=:flow,last_attempt_at=UTC_TIMESTAMP(3),first_confirmed_at=IF(:receipt='CONFIRMED',COALESCE(first_confirmed_at,UTC_TIMESTAMP(3)),first_confirmed_at),closed_at=IF(:flow='CLOSED',UTC_TIMESTAMP(3),NULL),closed_reason=IF(:flow='CLOSED',:receipt,NULL),version=version+1,updated_by=:actor"
            + " WHERE id=:id",
        Db.p("receipt", receipt, "flow", flow, "actor", a.id(), "id", rid));
    event(a, r, "RECEIPT_CHANGED", receipt, flow, aid, b.reasonText(), "attempt-" + key);
    if (flow.equals("CLOSED")) closeTasks(a, rid, "通知对象已终结");
    else if (receipt.equals("UNREACHABLE")) {
      long retries =
          db.count(
              "SELECT COUNT(*) FROM delivery_attempt WHERE recipient_id=:id AND"
                  + " result='UNREACHABLE'",
              Db.p("id", rid));
      if (retries < 3)
        task(a, rid, "RECONTACT", aid, (int) retries, ElderService.utc().plusDays(1));
    }
    audit.log(a, "NOTICE_ATTEMPT_CREATED", "ATTEMPT", aid, Map.of("requestHash", hash));
    completeBatch(Db.id(r, "notice_id"));
    return Db.p(
        "id",
        aid,
        "recipientId",
        rid,
        "receiptStatus",
        receipt,
        "workflowStatus",
        flow,
        "version",
        b.version() + 1);
  }

  private void validateAttempt(Attempt b) {
    privacy.check(b.reasonText());
    privacy.check(b.feedbackQuote());
    privacy.check(b.familyNameMasked());
    privacy.check(b.familyRelation());
    if (b.channel().equals("SMS")) throw new Problem(501, "备案短信平台尚未接入，不记录虚假发送成功");
    if (b.result().equals("UNREACHABLE")) {
      Problem.require(
          b.unreachableReason() != null
              && Set.of("NO_ONE_HOME", "NO_ANSWER", "OTHER").contains(b.unreachableReason()),
          "未联系上必须选择原因");
      if (b.unreachableReason().equals("OTHER"))
        Problem.require(b.reasonText() != null && !b.reasonText().isBlank(), "其他原因必须填写说明");
    }
    if (b.result().equals("REFUSED"))
      Problem.require(b.reasonText() != null && !b.reasonText().isBlank(), "拒绝接收必须填写原因");
    if (b.channel().equals("HOME_VISIT") && b.result().equals("CONFIRMED"))
      Problem.require(!b.fileIds().isEmpty(), "上门确认接收需要脱敏签字凭证");
    if (b.channel().equals("BULLETIN"))
      Problem.require(b.result().equals("POSTED") && !b.fileIds().isEmpty(), "公告只能记录已张贴，必须附脱敏照片");
    else Problem.require(!b.result().equals("POSTED"), "只有公告栏可以记录张贴结果");
    if (b.channel().equals("FAMILY_RELAY")) {
      Problem.require(
          b.familyNameMasked() != null
              && !b.familyNameMasked().isBlank()
              && b.familyRelation() != null
              && !b.familyRelation().isBlank(),
          "家属转告需填写脱敏称谓和关系");
      Problem.require(
          b.familyNameMasked().contains("某") || b.familyNameMasked().contains("*"),
          "家属称谓须脱敏，例如张某 女儿");
      if (b.result().equals("CONFIRMED"))
        Problem.require(Boolean.TRUE.equals(b.relayConfirmed()), "家属必须明确确认已转告");
    }
  }

  public String attemptHash(Attempt b) {
    return crypto.digest(
        json.write(
            Db.p(
                "channel",
                b.channel(),
                "result",
                b.result(),
                "unreachableReason",
                b.unreachableReason(),
                "reasonText",
                b.reasonText(),
                "feedbackQuote",
                b.feedbackQuote(),
                "familyNameMasked",
                b.familyNameMasked(),
                "familyRelation",
                b.familyRelation(),
                "relayConfirmed",
                b.relayConfirmed(),
                "fileIds",
                b.fileIds().stream().sorted().toList())));
  }

  public void checkReplay(String action, long id, String hash) {
    var log =
        db.one(
            "SELECT change_summary_json FROM audit_log WHERE action=:action AND target_id=:id ORDER"
                + " BY id DESC LIMIT 1",
            Db.p("action", action, "id", Long.toString(id)));
    if (!hash.equals(json.map(log.get("change_summary_json")).get("requestHash")))
      throw new Problem(409, "幂等键已用于不同请求内容");
  }

  @Transactional
  public Object reopen(Actor a, long id, Reason b) {
    a.require("notice:receive");
    privacy.check(b.reason());
    var r = recipient(a, id, true);
    Problem.require(Set.of("CLOSED", "CANCELLED").contains(r.get("workflow_status")), "只有已终结对象可重开");
    Problem.changed(
        db.update(
            "UPDATE notice_recipient SET"
                + " receipt_status='PENDING',workflow_status='OPEN',closed_at=NULL,closed_reason=NULL,version=version+1,updated_by=:actor"
                + " WHERE id=:id AND version=:v",
            Db.p("actor", a.id(), "id", id, "v", b.version())));
    db.update(
        "UPDATE notice SET status='ACTIVE',cancel_reason=NULL,version=version+1 WHERE id=:id",
        Db.p("id", r.get("notice_id")));
    event(a, r, "REOPENED", "PENDING", "OPEN", null, b.reason(), "reopen-" + b.version());
    audit.log(a, "NOTICE_REOPENED", "RECIPIENT", id, Map.of("reason", b.reason()));
    return Db.p("id", id, "version", b.version() + 1);
  }

  @Transactional
  public Object feedback(Actor a, long id, Feedback b) {
    a.require("feedback:create");
    recipient(a, id, true);
    privacy.check(b.text());
    long current =
        db.count(
            "SELECT COALESCE(MAX(revision_no),0) FROM service_feedback WHERE recipient_id=:id",
            Db.p("id", id));
    if (current != b.revision()) throw new Problem(409, "反馈版本已变化");
    Long previous =
        current == 0
            ? null
            : db.count(
                "SELECT id FROM service_feedback WHERE recipient_id=:id AND revision_no=:v",
                Db.p("id", id, "v", current));
    long fid =
        db.insert(
            "service_feedback",
            Db.p(
                "recipient_id",
                id,
                "revision_no",
                current + 1,
                "rating",
                b.rating(),
                "respondent_type",
                b.respondentType(),
                "feedback_text",
                b.text(),
                "collected_at",
                ElderService.utc(),
                "effective_at",
                ElderService.utc(),
                "supersedes_feedback_id",
                previous,
                "created_by",
                a.id()));
    audit.log(a, "FEEDBACK_RECORDED", "FEEDBACK", fid, Map.of("revision", current + 1));
    return Db.p("id", fid, "revision", current + 1);
  }

  public void task(
      Actor a, long recipient, String type, Long attempt, int retry, LocalDateTime due) {
    String key = "recipient:" + recipient + ":" + type;
    if (db.count("SELECT COUNT(*) FROM followup_task WHERE open_dedupe_key=:key", Db.p("key", key))
        > 0) return;
    db.insert(
        "followup_task",
        Db.p(
            "community_id",
            a.communityId(),
            "recipient_id",
            recipient,
            "task_type",
            type,
            "assignee_id",
            Db.id(recipient(a, recipient, false), "owner_user_id"),
            "source_attempt_id",
            attempt,
            "due_at",
            due,
            "retry_no",
            retry,
            "rule_snapshot_json",
            json.write(Map.of("retryAfterDays", 1, "maxRetries", 3)),
            "open_dedupe_key",
            key,
            "created_by",
            a.id()));
  }

  public void closeTasks(Actor a, long recipient, String reason) {
    db.update(
        "UPDATE followup_task SET"
            + " status='CANCELLED',cancel_reason=:reason,open_dedupe_key=NULL,version=version+1,updated_by=:actor"
            + " WHERE recipient_id=:id AND status IN ('PENDING','PROCESSING')",
        Db.p("reason", reason, "actor", a.id(), "id", recipient));
  }

  public void event(
      Actor a,
      Map<String, Object> before,
      String type,
      String receipt,
      String flow,
      Long attempt,
      String reason,
      String key) {
    db.insert(
        "notice_recipient_event",
        Db.p(
            "recipient_id",
            before.get("id"),
            "event_type",
            type,
            "from_receipt_status",
            before.get("receipt_status"),
            "to_receipt_status",
            receipt,
            "from_workflow_status",
            before.get("workflow_status"),
            "to_workflow_status",
            flow,
            "delivery_attempt_id",
            attempt,
            "effective_at",
            ElderService.utc(),
            "owner_snapshot_id",
            before.get("owner_user_id"),
            "reason",
            reason,
            "request_key",
            key,
            "created_by",
            a.id()));
  }

  public void completeBatch(long id) {
    db.update(
        "UPDATE notice SET status='COMPLETED',version=version+1 WHERE id=:id AND status='ACTIVE'"
            + " AND NOT EXISTS(SELECT 1 FROM notice_recipient r WHERE r.notice_id=:id AND"
            + " r.workflow_status IN ('OPEN','FOLLOWING'))",
        Db.p("id", id));
  }

  public static void validateKey(String key) {
    Problem.require(
        key != null && key.matches("[A-Za-z0-9_.:-]{1,60}"), "Idempotency-Key必须为1至60位英文数字或_.:-");
    new Privacy().check(key);
  }
}
