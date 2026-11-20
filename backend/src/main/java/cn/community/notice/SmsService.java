package cn.community.notice;

import cn.community.common.*;
import cn.community.elder.*;
import cn.community.identity.*;
import cn.community.jobs.JobService;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SmsService {
  public record Settings(
      @Min(0) int version,
      boolean enabled,
      @Size(max = 500) String endpoint,
      @Size(max = 500) String apiKey,
      @Size(max = 80) String signature,
      @Size(max = 100) String templateId) {}

  private final Db db;
  private final Crypto crypto;
  private final Json json;
  private final NoticeService notices;
  private final ElderService elders;
  private final JobService jobs;
  private final SmsGateway gateway;
  private final Audit audit;

  public SmsService(
      Db db,
      Crypto crypto,
      Json json,
      NoticeService notices,
      ElderService elders,
      JobService jobs,
      SmsGateway gateway,
      Audit audit) {
    this.db = db;
    this.crypto = crypto;
    this.json = json;
    this.notices = notices;
    this.elders = elders;
    this.jobs = jobs;
    this.gateway = gateway;
    this.audit = audit;
  }

  private Map<String, Object> config(long community) {
    Object cipher =
        db.optional(
                "SELECT sms_config_cipher FROM community_profile WHERE community_id=:c",
                Db.p("c", community))
            .orElseGet(LinkedHashMap::new)
            .get("sms_config_cipher");
    return cipher instanceof byte[] bytes ? json.map(crypto.decrypt(bytes)) : new LinkedHashMap<>();
  }

  public Object settings(Actor a) {
    a.require("system:config:manage");
    var c = config(a.communityId());
    c.put("hasKey", !Objects.toString(c.remove("apiKey"), "").isBlank());
    c.put(
        "version",
        db.count(
            "SELECT COALESCE(MAX(sms_version),0) FROM community_profile WHERE community_id=:c",
            Db.p("c", a.communityId())));
    return c;
  }

  @Transactional
  public Object save(Actor a, Settings b) {
    a.require("system:config:manage");
    db.update(
        "INSERT IGNORE INTO community_profile(community_id) VALUES(:c)",
        Db.p("c", a.communityId()));
    db.one(
        "SELECT community_id FROM community_profile WHERE community_id=:c FOR UPDATE",
        Db.p("c", a.communityId()));
    var old = config(a.communityId());
    String key =
        b.apiKey() == null || b.apiKey().isBlank()
            ? Objects.toString(old.get("apiKey"), "")
            : b.apiKey().strip();
    String url = Objects.toString(b.endpoint(), "").strip(),
        sign = Objects.toString(b.signature(), "").strip(),
        template = Objects.toString(b.templateId(), "").strip();
    if (b.enabled()) {
      SmsGateway.endpoint(url);
      Problem.require(
          !key.isBlank() && !sign.isBlank() && !template.isBlank(), "启用短信前请填写密钥、备案签名和模板编号");
      Problem.require(key.chars().allMatch(c -> c >= 33 && c <= 126), "短信密钥格式不正确");
    }
    var data =
        Db.p(
            "enabled",
            b.enabled(),
            "endpoint",
            url,
            "apiKey",
            key,
            "signature",
            sign,
            "templateId",
            template);
    Problem.changed(
        db.update(
            "UPDATE community_profile SET sms_config_cipher=:cipher,sms_version=sms_version+1 WHERE"
                + " community_id=:c AND sms_version=:v",
            Db.p(
                "cipher",
                crypto.encrypt(json.write(data)),
                "c",
                a.communityId(),
                "v",
                b.version())));
    audit.log(
        a, "SMS_SETTINGS_SAVED", "COMMUNITY", a.communityId(), Map.of("enabled", b.enabled()));
    return settings(a);
  }

  @Transactional
  public Object enqueue(Actor a, long recipient, String key, int version) {
    a.require("notice:receive");
    NoticeService.validateKey(key);
    var r = notices.recipient(a, recipient, true);
    var config = config(a.communityId());
    Problem.require(Boolean.TRUE.equals(config.get("enabled")), "本社区尚未配置并启用备案短信平台");
    var n = db.one("SELECT status FROM notice WHERE id=:id", Db.p("id", r.get("notice_id")));
    Problem.require(
        "ACTIVE".equals(n.get("status"))
            && Set.of("OPEN", "FOLLOWING").contains(r.get("workflow_status")),
        "通知未激活或接收对象已终结");
    var profile = elders.detail(a, Db.id(r, "elder_id"));
    String phone = Objects.toString(profile.get("familyPhone"), "");
    Problem.require(
        Boolean.TRUE.equals(profile.get("familySmsAllowed")), "请先在老人档案确认家属手机号已备案并获短信通知授权");
    Problem.require(phone.matches("1[3-9][0-9]{9}"), "请先在老人档案中登记有效的家属手机号");
    var payload =
        Db.p(
            "recipientId",
            recipient,
            "recipientVersion",
            version,
            "contactHash",
            HexFormat.of().formatHex(crypto.hash("sms.family:" + phone)));
    String businessKey = "sms:" + recipient + ":" + key;
    var prior =
        db.optional(
            "SELECT id,payload_json FROM job_run WHERE job_type='SMS_DISPATCH' AND business_key=:k",
            Db.p("k", businessKey));
    if (prior.isEmpty()) {
      if (Db.id(r, "version") != version) throw new Problem(409, "接收对象版本已变化，请刷新");
      Problem.require(
          db.count(
                  "SELECT COUNT(*) FROM job_run WHERE job_type='SMS_DISPATCH' AND status IN"
                      + " ('PENDING','PROCESSING') AND"
                      + " JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.recipientId'))=:r",
                  Db.p("r", Long.toString(recipient)))
              == 0,
          "该对象已有待发送短信，请勿重复提交");
    }
    var result = jobs.enqueue(a, "SMS_DISPATCH", businessKey, payload);
    audit.log(a, "SMS_QUEUED", "RECIPIENT", recipient, Map.of());
    return result;
  }

  @Transactional
  public void dispatch(Actor a, long jobId, Map<String, Object> payload) {
    a.require("notice:receive");
    long rid = Long.parseLong(payload.get("recipientId").toString());
    var r = notices.recipient(a, rid, true);
    String requestId = "community-sms-" + jobId;
    if (db.count(
            "SELECT COUNT(*) FROM delivery_attempt WHERE provider_request_id=:k",
            Db.p("k", requestId))
        > 0) return;
    var c = config(a.communityId());
    Problem.require(Boolean.TRUE.equals(c.get("enabled")), "短信配置已停用");
    var n = db.one("SELECT * FROM notice WHERE id=:id", Db.p("id", r.get("notice_id")));
    Problem.require(
        "ACTIVE".equals(n.get("status"))
            && Set.of("OPEN", "FOLLOWING").contains(r.get("workflow_status")),
        "通知或对象已终结，停止发送");
    Problem.require(
        Db.id(r, "version") == Long.parseLong(payload.get("recipientVersion").toString()),
        "接收对象已更新，停止发送，请重新确认");
    var profile = elders.detail(a, Db.id(r, "elder_id"));
    String phone = Objects.toString(profile.get("familyPhone"), "");
    Problem.require(Boolean.TRUE.equals(profile.get("familySmsAllowed")), "备案家属的短信通知授权已撤销，停止发送");
    Problem.require(
        HexFormat.of()
            .formatHex(crypto.hash("sms.family:" + phone))
            .equals(payload.get("contactHash")),
        "备案家属联系方式已变化，请重新确认");
    String providerId =
        gateway.send(
            c,
            requestId,
            phone,
            Db.p(
                "noticeTitle",
                n.get("title"),
                "noticeContent",
                n.get("content"),
                "elderNo",
                profile.get("elderNo")));
    long attempt =
        db.insert(
            "delivery_attempt",
            Db.p(
                "recipient_id",
                rid,
                "channel",
                "SMS",
                "attempted_at",
                ElderService.utc(),
                "result",
                "SENT",
                "provider_request_id",
                requestId,
                "external_contact_token",
                crypto.encrypt(providerId),
                "idempotency_key",
                requestId,
                "created_by",
                a.id()));
    db.update(
        "UPDATE notice_recipient SET"
            + " last_attempt_at=UTC_TIMESTAMP(3),version=version+1,updated_by=:a WHERE id=:id",
        Db.p("a", a.id(), "id", rid));
    audit.log(a, "SMS_ACCEPTED", "ATTEMPT", attempt, Map.of("jobId", jobId));
    notices.task(a, rid, "RECONTACT", attempt, 0, ElderService.utc().plusDays(1));
  }

  public Object history(Actor a, long recipient) {
    a.require("notice:read");
    notices.recipient(a, recipient, false);
    return db.list(
        "SELECT id,status,retry_count,error_summary,created_at,updated_at FROM job_run WHERE"
            + " job_type='SMS_DISPATCH' AND community_id=:c AND"
            + " JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.recipientId'))=:r ORDER BY id DESC LIMIT"
            + " 30",
        Db.p("c", a.communityId(), "r", Long.toString(recipient)));
  }
}
