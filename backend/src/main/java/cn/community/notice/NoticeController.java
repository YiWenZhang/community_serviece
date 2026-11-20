package cn.community.notice;

import cn.community.common.*;
import cn.community.identity.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "04 通知与接收")
public class NoticeController {
  private final NoticeService notices;
  private final Auth auth;
  private final Db db;

  public NoticeController(NoticeService notices, Auth auth, Db db) {
    this.notices = notices;
    this.auth = auth;
    this.db = db;
  }

  @GetMapping("/notices")
  @Operation(summary = "本人可见通知分页")
  public Object list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) String noticeType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Long ownerId) {
    return Api.ok(notices.list(auth.current(), page, pageSize, noticeType, status, ownerId));
  }

  @GetMapping("/notices/{id}")
  @Operation(summary = "通知内容及本人负责的对象")
  public Object detail(@PathVariable long id) {
    return Api.ok(notices.detail(auth.current(), id));
  }

  @PostMapping("/notices")
  @Operation(summary = "创建草稿和本人对象名单")
  public Object create(@Valid @RequestBody NoticeService.Draft b) {
    return Api.ok(notices.create(auth.current(), b));
  }

  @PostMapping("/notices/{id}/activate")
  @Operation(summary = "激活草稿，冻结名单并生成疫苗提醒任务")
  public Object activate(@PathVariable long id, @Valid @RequestBody NoticeService.Version b) {
    return Api.ok(notices.activate(auth.current(), id, b.version()));
  }

  @PostMapping("/notices/{id}/cancel")
  @Operation(summary = "取消通知及尚未结束的本人对象")
  public Object cancel(@PathVariable long id, @Valid @RequestBody NoticeService.Reason b) {
    return Api.ok(notices.cancel(auth.current(), id, b));
  }

  @GetMapping("/notice-recipients")
  @Operation(summary = "本人接收对象分页")
  public Object recipients(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) String receiptStatus,
      @RequestParam(required = false) String noticeType,
      @RequestParam(defaultValue = "ALL") String workState,
      @RequestParam(required = false) Long noticeId,
      @RequestParam(required = false) Long ownerId) {
    return Api.ok(
        notices.work(
            auth.require("notice:read"),
            page,
            pageSize,
            receiptStatus,
            noticeType,
            workState,
            noticeId,
            ownerId));
  }

  @GetMapping("/notice-recipients/{id}")
  @Operation(summary = "接收对象详情、触达、状态历史和满意度版本")
  public Object recipient(@PathVariable long id) {
    var a = auth.require("notice:read");
    var r = notices.recipient(a, id, false);
    r.put(
        "attempts",
        db.list(
            "SELECT"
                + " id,channel,attempted_at,result,unreachable_reason,reason_text,feedback_quote,family_name_masked,family_relation,relay_confirmed,family_name_cipher"
                + " FROM delivery_attempt WHERE recipient_id=:id ORDER BY id",
            Db.p("id", id)));
    r.put(
        "events",
        db.list(
            "SELECT * FROM notice_recipient_event WHERE recipient_id=:id ORDER BY effective_at,id",
            Db.p("id", id)));
    r.put(
        "feedback",
        db.list(
            "SELECT * FROM service_feedback WHERE recipient_id=:id ORDER BY revision_no DESC",
            Db.p("id", id)));
    @SuppressWarnings("unchecked")
    var attempts = (java.util.List<java.util.Map<String, Object>>) r.get("attempts");
    for (var attempt : attempts) {
      attempt.put(
          "attachments",
          db.list(
              "SELECT file_id,evidence_type FROM delivery_attachment WHERE delivery_attempt_id=:id"
                  + " ORDER BY id",
              Db.p("id", attempt.get("id"))));
    }
    r.put("attempts", notices.displayAttempts(attempts));
    return Api.ok(r);
  }

  @PostMapping("/notice-recipients/{id}/attempts")
  @Operation(summary = "幂等登记触达，同事务变更接收状态和跟进任务")
  public Object attempt(
      @PathVariable long id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody NoticeService.Attempt b) {
    return Api.ok(notices.attempt(auth.current(), id, key, b));
  }

  @PostMapping("/notice-recipients/{id}/reopen")
  @Operation(summary = "有原因重开，保留既有触达与事件")
  public Object reopen(@PathVariable long id, @Valid @RequestBody NoticeService.Reason b) {
    return Api.ok(notices.reopen(auth.current(), id, b));
  }

  @PostMapping("/notice-recipients/{id}/feedback")
  @Operation(summary = "采集或更正满意度，版本追加不覆盖")
  public Object feedback(@PathVariable long id, @Valid @RequestBody NoticeService.Feedback b) {
    return Api.ok(notices.feedback(auth.current(), id, b));
  }
}
