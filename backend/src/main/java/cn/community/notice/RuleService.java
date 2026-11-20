package cn.community.notice;

import cn.community.common.*;
import cn.community.identity.*;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuleService {
  public record Rule(
      @Min(0) int version,
      @NotBlank @Pattern(regexp = "[A-Z0-9_]{1,32}") String code,
      @NotBlank @Size(max = 80) String name,
      @Pattern(regexp = "BIRTHDAY|VACCINE") @NotNull String noticeType,
      @Min(0) @Max(30) int leadDays,
      Long campaignId,
      boolean enabled) {}

  private final Db db;
  private final NoticeService notices;
  private final Json json;
  private final Audit audit;
  private final Privacy privacy;

  public RuleService(Db db, NoticeService notices, Json json, Audit audit, Privacy privacy) {
    this.db = db;
    this.notices = notices;
    this.json = json;
    this.audit = audit;
    this.privacy = privacy;
  }

  @Transactional
  public Object save(Actor a, Long id, Rule b) {
    a.require("notice:rule:manage");
    privacy.check(b.code());
    privacy.check(b.name());
    if (b.noticeType().equals("VACCINE")) {
      Problem.require(b.campaignId() != null, "疫苗规则需指定周期");
      db.one(
          "SELECT id FROM vaccine_campaign WHERE id=:id AND community_id=:community",
          Db.p("id", b.campaignId(), "community", a.communityId()));
    }
    var config = Db.p("leadDays", b.leadDays(), "campaignId", b.campaignId());
    var p =
        Db.p(
            "community_id",
            a.communityId(),
            "code",
            b.code(),
            "name",
            b.name(),
            "notice_type",
            b.noticeType(),
            "trigger_kind",
            b.noticeType().equals("BIRTHDAY") ? "BIRTH_MONTH" : "VACCINE_STATUS",
            "config_json",
            json.write(config),
            "enabled",
            b.enabled() ? 1 : 0);
    if (id == null) {
      p.put("created_by", a.id());
      id = db.insert("notice_rule", p);
    } else {
      p.put("id", id);
      p.put("v", b.version());
      p.put("actor", a.id());
      Problem.changed(
          db.update(
              "UPDATE notice_rule SET"
                  + " code=:code,name=:name,notice_type=:notice_type,trigger_kind=:trigger_kind,config_json=:config_json,enabled=:enabled,rule_version=rule_version+1,version=version+1,updated_by=:actor"
                  + " WHERE id=:id AND community_id=:community_id AND version=:v",
              p));
    }
    audit.log(a, "NOTICE_RULE_SAVED", "NOTICE_RULE", id, Map.of());
    return db.one("SELECT * FROM notice_rule WHERE id=:id", Db.p("id", id));
  }

  /** 一个用户的一次生成同事务完成；automation_key排除本周期已生成老人。 */
  @Transactional
  public void generate(Actor a, long ruleId, String period, String batchKey) {
    a.require("notice:create");
    var rule =
        db.one(
            "SELECT * FROM notice_rule WHERE id=:id AND community_id=:community AND enabled=1 FOR"
                + " UPDATE",
            Db.p("id", ruleId, "community", a.communityId()));
    var config = json.map(rule.get("config_json"));
    String prefix = "rule:" + ruleId + ":period:" + period + ":elder:";
    var p = a.scope();
    p.put("prefix", prefix);
    String where =
        "e.community_id=:community AND e.owner_user_id=:actor AND e.status='ACTIVE' AND NOT"
            + " EXISTS(SELECT 1 FROM notice_recipient r WHERE"
            + " r.automation_key=CONCAT(:prefix,e.id))";
    Long campaign = null;
    if ("BIRTHDAY".equals(rule.get("notice_type"))) {
      YearMonth month = YearMonth.parse(period);
      p.put("month", month.getMonthValue());
      where +=
          " AND e.birth_month=:month AND EXISTS(SELECT 1 FROM elder_service_need n JOIN"
              + " service_need d ON d.id=n.need_id WHERE n.elder_id=e.id AND n.status='ACTIVE' AND"
              + " d.code='BIRTHDAY_CARE')";
    } else {
      Object cid = config.get("campaignId");
      Problem.require(cid != null, "规则缺少疫苗周期");
      campaign = Long.valueOf(cid.toString());
      p.put("campaign", campaign);
      where +=
          " AND EXISTS(SELECT 1 FROM elder_vaccine_status v WHERE v.elder_id=e.id AND"
              + " v.campaign_id=:campaign AND v.eligibility='ELIGIBLE' AND"
              + " v.vaccination_status='NOT_VACCINATED')";
    }
    // 每批最多500人，按id分批继续直到候选全部处理，主键游标持久在本事务内。
    while (true) {
      var candidates =
          db.list(
              "SELECT e.id FROM elder_profile e WHERE " + where + " ORDER BY e.id LIMIT 500", p);
      if (candidates.isEmpty()) break;
      Set<Long> ids = new LinkedHashSet<>();
      for (var c : candidates) ids.add(Db.id(c, "id"));
      @SuppressWarnings("unchecked")
      var result =
          (Map<String, Object>)
              notices.create(
                  a,
                  new NoticeService.Draft(
                      rule.get("notice_type").toString(),
                      rule.get("name") + " " + period,
                      "按已发布社区规则生成，请经办人确认服务安排。",
                      ids,
                      campaign,
                      period));
      long nid = ((Number) result.get("id")).longValue();
      db.update(
          "UPDATE notice SET"
              + " source='RULE',rule_id=:rule,rule_version=:rv,rule_snapshot_json=:snapshot,generation_key=:key"
              + " WHERE id=:id",
          Db.p(
              "rule",
              ruleId,
              "rv",
              rule.get("rule_version"),
              "snapshot",
              rule.get("config_json").toString(),
              "key",
              batchKey + ":" + nid,
              "id",
              nid));
      for (long elder : ids)
        db.update(
            "UPDATE notice_recipient SET automation_key=:key WHERE notice_id=:notice AND"
                + " elder_id=:elder",
            Db.p("key", prefix + elder, "notice", nid, "elder", elder));
      notices.activate(a, nid, 0);
    }
  }
}
