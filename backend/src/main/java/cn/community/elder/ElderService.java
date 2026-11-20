package cn.community.elder;

import cn.community.common.*;
import cn.community.identity.*;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ElderService {
  @io.swagger.v3.oas.annotations.media.Schema(name = "ElderDraft")
  public record Draft(
      @NotNull Long gridId,
      @Pattern(regexp = "MALE|FEMALE|UNKNOWN") @NotNull String sex,
      @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") @NotNull String birthMonth,
      @Size(max = 500) String remark,
      @NotNull Set<Long> needIds,
      Long ownerUserId,
      @NotNull @jakarta.validation.Valid ElderPersonalInfo.Info personalInfo) {
    public Draft(
        Long gridId,
        String sex,
        String birthMonth,
        String remark,
        Set<Long> needIds,
        Long ownerUserId) {
      this(gridId, sex, birthMonth, remark, needIds, ownerUserId, null);
    }

    public Draft(Long gridId, String sex, String birthMonth, String remark, Set<Long> needIds) {
      this(gridId, sex, birthMonth, remark, needIds, null, null);
    }
  }

  public record Edit(
      @Min(0) int version,
      @NotNull @jakarta.validation.Valid Draft profile,
      @Pattern(regexp = "ACTIVE|INACTIVE|ARCHIVED") @NotNull String status) {}

  public record Transfer(
      @Min(0) int version,
      @NotNull Long gridId,
      @NotNull Long ownerUserId,
      @NotBlank @Size(max = 300) String reason,
      Long communityId) {
    public Transfer(int version, Long gridId, Long ownerUserId, String reason) {
      this(version, gridId, ownerUserId, reason, null);
    }
  }

  public record Vaccine(
      @Min(0) int version,
      @NotNull Long campaignId,
      @Pattern(regexp = "ELIGIBLE|INELIGIBLE|UNKNOWN") @NotNull String eligibility,
      @Pattern(regexp = "NOT_VACCINATED|PARTIAL|COMPLETED|UNKNOWN") @NotNull
          String vaccinationStatus,
      LocalDate appointmentDate,
      LocalDate vaccinatedOn,
      @Min(1) Integer doseNo,
      @NotBlank @Size(max = 300) String reason) {}

  private final Db db;
  private final Crypto crypto;
  private final Audit audit;
  private final Privacy privacy;
  private final Auth auth;
  private final ElderPersonalInfo personal;

  public ElderService(
      Db db, Crypto crypto, Audit audit, Privacy privacy, Auth auth, ElderPersonalInfo personal) {
    this.db = db;
    this.crypto = crypto;
    this.audit = audit;
    this.privacy = privacy;
    this.auth = auth;
    this.personal = personal;
  }

  public Map<String, Object> owned(Actor a, long id, boolean lock) {
    return db.one(
        "SELECT * FROM elder_profile WHERE id=:id AND community_id=:community AND"
            + " (owner_user_id=:actor OR "
            + a.admin()
            + ")"
            + (lock ? " FOR UPDATE" : ""),
        Db.p("id", id, "community", a.communityId(), "actor", a.id()));
  }

  public Map<String, Object> display(Map<String, Object> row) {
    var copy = new LinkedHashMap<>(row);
    copy.put("elderNo", crypto.decrypt((byte[]) copy.remove("elder_no_cipher")));
    copy.remove("elder_no_hash");
    copy.putAll(personal.read(copy.remove("personal_info_cipher")));
    copy.remove("name_hash");
    copy.remove("phone_hash");
    return copy;
  }

  public Map<String, Object> reference(Map<String, Object> row) {
    return personal.reference(row);
  }

  public Object birthdayCare(Actor a, String month, int page) {
    a.require("elder:read");
    YearMonth period =
        month == null ? YearMonth.now(ZoneId.of("Asia/Shanghai")) : YearMonth.parse(month);
    var p = Db.p("community", a.communityId(), "actor", a.id(), "month", period.getMonthValue());
    String from =
        " FROM elder_profile e JOIN community c ON c.id=e.community_id JOIN sys_user u ON"
            + " u.id=e.owner_user_id WHERE e.status='ACTIVE' AND c.status='ACTIVE' AND"
            + " e.birth_month=:month AND "
            + (a.admin() ? "TRUE" : "e.community_id=:community AND e.owner_user_id=:actor");
    var result =
        db.page(
            "SELECT e.id,e.community_id,c.name AS community_name,e.owner_user_id,u.display_name AS"
                + " owner_name,e.birth_year,e.birth_month,e.elder_no_cipher,e.personal_info_cipher,EXISTS(SELECT"
                + " 1 FROM elder_service_need n JOIN service_need d ON d.id=n.need_id WHERE"
                + " n.elder_id=e.id AND n.status='ACTIVE' AND d.code='BIRTHDAY_CARE') AS"
                + " care_enrolled"
                + from
                + " ORDER BY e.community_id,e.id",
            "SELECT COUNT(*)" + from,
            p,
            page,
            20);
    @SuppressWarnings("unchecked")
    var items = (List<Map<String, Object>>) result.get("items");
    result.put("items", items.stream().map(this::reference).toList());
    result.put("month", period.toString());
    return result;
  }

  public Map<String, Object> detail(Actor a, long id) {
    a.require("elder:read");
    var row = display(owned(a, id, false));
    row.put(
        "needIds",
        db
            .list(
                "SELECT need_id FROM elder_service_need WHERE elder_id=:id AND status='ACTIVE'",
                Db.p("id", id))
            .stream()
            .map(r -> r.get("need_id"))
            .toList());
    row.put(
        "vaccines",
        db.list("SELECT * FROM elder_vaccine_status WHERE elder_id=:id", Db.p("id", id)));
    return row;
  }

  public Object list(
      Actor a,
      int page,
      int size,
      String elderNo,
      Integer month,
      String vaccineStatus,
      Long needId) {
    return list(a, page, size, elderNo, month, vaccineStatus, needId, null, null);
  }

  public Object list(
      Actor a,
      int page,
      int size,
      String elderNo,
      Integer month,
      String vaccineStatus,
      Long needId,
      String name,
      String phone) {
    return list(a, page, size, elderNo, month, vaccineStatus, needId, name, phone, null, null);
  }

  public Object list(
      Actor a,
      int page,
      int size,
      String elderNo,
      Integer month,
      String vaccineStatus,
      Long needId,
      String name,
      String phone,
      String birthFrom,
      String birthTo) {
    a.require("elder:read");
    var p = a.scope();
    String where =
        " FROM elder_profile e WHERE e.community_id=:community AND (e.owner_user_id=:actor OR "
            + a.admin()
            + ")";
    if (elderNo != null && !elderNo.isBlank()) {
      where += " AND e.elder_no_hash=:hash";
      p.put("hash", crypto.hash(elderNo.trim()));
    }
    if (name != null && !name.isBlank()) {
      where += " AND e.name_hash=:nameHash";
      p.put("nameHash", personal.searchHash("name", name));
    }
    if (phone != null && !phone.isBlank()) {
      where += " AND e.phone_hash=:phoneHash";
      p.put("phoneHash", personal.searchHash("phone", phone));
    }
    if (month != null) {
      Problem.require(month >= 1 && month <= 12, "出生月份需1至12");
      where += " AND e.birth_month=:month";
      p.put("month", month);
    }
    if (birthFrom != null && !birthFrom.isBlank()) {
      var b = YearMonth.parse(birthFrom);
      where += " AND (e.birth_year*12+e.birth_month)>=:birthFrom";
      p.put("birthFrom", b.getYear() * 12 + b.getMonthValue());
    }
    if (birthTo != null && !birthTo.isBlank()) {
      var b = YearMonth.parse(birthTo);
      where += " AND (e.birth_year*12+e.birth_month)<=:birthTo";
      p.put("birthTo", b.getYear() * 12 + b.getMonthValue());
    }
    if (birthFrom != null && birthTo != null && !birthFrom.isBlank() && !birthTo.isBlank())
      Problem.require(!YearMonth.parse(birthFrom).isAfter(YearMonth.parse(birthTo)), "出生年月范围不正确");
    if (vaccineStatus != null && !vaccineStatus.isBlank()) {
      where +=
          " AND EXISTS (SELECT 1 FROM elder_vaccine_status v WHERE v.elder_id=e.id AND"
              + " v.vaccination_status=:vs)";
      p.put("vs", vaccineStatus);
    }
    if (needId != null) {
      where +=
          " AND EXISTS (SELECT 1 FROM elder_service_need n WHERE n.elder_id=e.id AND"
              + " n.need_id=:need AND n.status='ACTIVE')";
      p.put("need", needId);
    }
    var result =
        db.page(
            "SELECT e.*,(SELECT display_name FROM sys_user WHERE id=e.registered_by) AS"
                + " registrar_name,(SELECT display_name FROM sys_user WHERE id=e.owner_user_id) AS"
                + " owner_name"
                + where
                + " ORDER BY e.id DESC",
            "SELECT COUNT(*)" + where,
            p,
            page,
            size);
    @SuppressWarnings("unchecked")
    var rows = (List<Map<String, Object>>) result.get("items");
    var profiles = rows.stream().map(this::display).toList();
    if (!rows.isEmpty()) {
      var statuses =
          db.list(
              "SELECT s.elder_id,c.name,s.vaccination_status,s.eligibility FROM"
                  + " elder_vaccine_status s JOIN vaccine_campaign c ON c.id=s.campaign_id WHERE"
                  + " s.elder_id IN (:ids) ORDER BY s.id",
              Db.p("ids", rows.stream().map(r -> r.get("id")).toList()));
      for (var profile : profiles)
        profile.put(
            "vaccineStatuses",
            statuses.stream()
                .filter(s -> s.get("elder_id").toString().equals(profile.get("id").toString()))
                .toList());
    }
    result.put("items", profiles);
    return result;
  }

  @Transactional
  public Object create(Actor a, Draft b) {
    a.require("elder:create");
    long owner = b.ownerUserId() == null ? a.id() : b.ownerUserId();
    Problem.require(a.admin() || owner == a.id(), "网格员只能登记本人负责的老人");
    auth.sameCommunityUser(a, owner, true);
    validate(a, b);
    int year = Year.now(ZoneId.of("Asia/Shanghai")).getValue();
    db.update(
        "INSERT INTO elder_global_sequence(register_year,next_value) VALUES(:year,1) ON DUPLICATE"
            + " KEY UPDATE register_year=register_year",
        Db.p("community", a.communityId(), "year", year));
    var seq =
        db.one(
            "SELECT * FROM elder_global_sequence WHERE register_year=:year" + " FOR UPDATE",
            Db.p("community", a.communityId(), "year", year));
    long next = Db.id(seq, "next_value");
    db.update(
        "UPDATE elder_global_sequence SET next_value=next_value+1,version=version+1 WHERE"
            + " register_year=:year",
        Db.p("year", year));
    String number = "SH-L-" + year + "-" + String.format(Locale.ROOT, "%03d", next);
    YearMonth birth = YearMonth.parse(b.birthMonth());
    var encryptedPersonal = personal.fields(b.personalInfo());
    long id =
        db.insert(
            "elder_profile",
            Db.p(
                "community_id",
                a.communityId(),
                "grid_id",
                b.gridId(),
                "owner_user_id",
                owner,
                "elder_no_cipher",
                crypto.encrypt(number),
                "elder_no_hash",
                crypto.hash(number),
                "key_version",
                1,
                "register_year",
                year,
                "sex",
                b.sex(),
                "birth_year",
                birth.getYear(),
                "birth_month",
                birth.getMonthValue(),
                "registered_by",
                a.id(),
                "info_updated_at",
                utc(),
                "remark",
                b.remark(),
                "created_by",
                a.id(),
                "personal_info_cipher",
                encryptedPersonal.get("personal_info_cipher"),
                "name_hash",
                encryptedPersonal.get("name_hash"),
                "phone_hash",
                encryptedPersonal.get("phone_hash")));
    db.insert(
        "elder_assignment_history",
        Db.p(
            "elder_id",
            id,
            "new_grid_id",
            b.gridId(),
            "new_owner_id",
            owner,
            "effective_at",
            utc(),
            "reason",
            "首次登记",
            "created_by",
            a.id()));
    needs(a, id, b.needIds());
    audit.log(a, "ELDER_CREATED", "ELDER", id, Map.of());
    return Db.p("id", id, "elderNo", number, "version", 0);
  }

  @Transactional
  public Object update(Actor a, long id, Edit b) {
    a.require("elder:update");
    owned(a, id, true);
    validate(a, b.profile());
    var d = b.profile();
    var encryptedPersonal = personal.fields(d.personalInfo());
    // Legacy internal callers without a private profile preserve existing encrypted values.
    if (d.personalInfo() != null)
      db.update(
          "UPDATE elder_profile SET"
              + " personal_info_cipher=:cipher,name_hash=:nameHash,phone_hash=:phoneHash WHERE"
              + " id=:id AND version=:v AND community_id=:community",
          Db.p(
              "cipher",
              encryptedPersonal.get("personal_info_cipher"),
              "nameHash",
              encryptedPersonal.get("name_hash"),
              "phoneHash",
              encryptedPersonal.get("phone_hash"),
              "id",
              id,
              "v",
              b.version(),
              "community",
              a.communityId()));
    YearMonth birth = YearMonth.parse(d.birthMonth());
    Problem.changed(
        db.update(
            "UPDATE elder_profile SET"
                + " grid_id=:grid,sex=:sex,birth_year=:y,birth_month=:m,remark=:remark,status=:status,info_updated_at=UTC_TIMESTAMP(3),version=version+1,updated_by=:actor"
                + " WHERE id=:id AND (owner_user_id=:actor OR "
                + a.admin()
                + ") AND community_id=:community AND"
                + " version=:v",
            Db.p(
                "grid",
                d.gridId(),
                "sex",
                d.sex(),
                "y",
                birth.getYear(),
                "m",
                birth.getMonthValue(),
                "remark",
                d.remark(),
                "status",
                b.status(),
                "actor",
                a.id(),
                "id",
                id,
                "community",
                a.communityId(),
                "v",
                b.version())));
    needs(a, id, d.needIds());
    audit.log(a, "ELDER_CHANGED", "ELDER", id, Map.of("status", b.status()));
    return Db.p("id", id, "version", b.version() + 1);
  }

  @Transactional
  public Object transfer(Actor a, long id, Transfer b) {
    a.require("elder:transfer");
    privacy.check(b.reason());
    var before =
        db.one(
            "SELECT * FROM elder_profile WHERE id=:id AND community_id=:community FOR UPDATE",
            Db.p("id", id, "community", a.communityId()));
    var target = b.communityId() == null ? a : auth.inCommunity(a, b.communityId());
    if (target.communityId() != a.communityId()) {
      Problem.require(
          db.count(
                  "SELECT COUNT(*) FROM notice_recipient WHERE elder_id=:id AND workflow_status IN"
                      + " ('OPEN','FOLLOWING')",
                  Db.p("id", id))
              == 0,
          "跨社区移交前请先完成或取消未闭环通知；历史通知保留原社区");
      Problem.require(
          db.count(
                  "SELECT COUNT(*) FROM followup_task t JOIN notice_recipient r ON"
                      + " r.id=t.recipient_id WHERE r.elder_id=:id AND t.status IN"
                      + " ('PENDING','PROCESSING')",
                  Db.p("id", id))
              == 0,
          "跨社区移交前请先结束未完成跟进任务");
      Problem.require(
          db.count(
                  "SELECT COUNT(*) FROM elder_profile WHERE community_id=:community AND"
                      + " elder_no_hash=:hash",
                  Db.p("community", target.communityId(), "hash", before.get("elder_no_hash")))
              == 0,
          "目标社区存在同编号档案，请先核实数据");
    }
    auth.sameCommunityUser(target, b.ownerUserId(), true);
    db.one(
        "SELECT id FROM grid WHERE id=:id AND community_id=:community AND status='ACTIVE'",
        Db.p("id", b.gridId(), "community", target.communityId()));
    Problem.changed(
        db.update(
            "UPDATE elder_profile SET"
                + " community_id=:target,grid_id=:grid,owner_user_id=:owner,version=version+1,updated_by=:actor,info_updated_at=UTC_TIMESTAMP(3)"
                + " WHERE id=:id AND version=:v",
            Db.p(
                "target",
                target.communityId(),
                "grid",
                b.gridId(),
                "owner",
                b.ownerUserId(),
                "actor",
                a.id(),
                "id",
                id,
                "v",
                b.version())));
    db.update(
        "UPDATE notice_recipient SET owner_user_id=:owner,version=version+1,updated_by=:actor WHERE"
            + " elder_id=:id AND workflow_status IN ('OPEN','FOLLOWING')",
        Db.p("owner", b.ownerUserId(), "actor", a.id(), "id", id));
    db.update(
        "UPDATE followup_task t JOIN notice_recipient r ON r.id=t.recipient_id SET"
            + " t.assignee_id=:owner,t.version=t.version+1,t.updated_by=:actor WHERE r.elder_id=:id"
            + " AND t.status IN ('PENDING','PROCESSING')",
        Db.p("owner", b.ownerUserId(), "actor", a.id(), "id", id));
    db.insert(
        "elder_assignment_history",
        Db.p(
            "elder_id",
            id,
            "old_grid_id",
            before.get("grid_id"),
            "new_grid_id",
            b.gridId(),
            "old_owner_id",
            before.get("owner_user_id"),
            "new_owner_id",
            b.ownerUserId(),
            "effective_at",
            utc(),
            "reason",
            b.reason(),
            "created_by",
            a.id()));
    audit.log(
        a,
        "ELDER_TRANSFERRED",
        "ELDER",
        id,
        Db.p(
            "from",
            before.get("owner_user_id"),
            "to",
            b.ownerUserId(),
            "fromCommunity",
            a.communityId(),
            "toCommunity",
            target.communityId(),
            "reason",
            b.reason()));
    return Db.p("id", id, "version", b.version() + 1);
  }

  @Transactional
  public Object vaccine(Actor a, long id, Vaccine b) {
    a.require("vaccine:verify");
    owned(a, id, true);
    privacy.check(b.reason());
    db.one(
        "SELECT id FROM vaccine_campaign WHERE id=:id AND community_id=:community AND"
            + " status='ACTIVE'",
        Db.p("id", b.campaignId(), "community", a.communityId()));
    Problem.require(
        !Set.of("PARTIAL", "COMPLETED").contains(b.vaccinationStatus())
            || (b.vaccinatedOn() != null && b.doseNo() != null),
        "已接种必须记录日期和针次");
    Problem.require(
        b.vaccinatedOn() == null
            || !b.vaccinatedOn().isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))),
        "接种日期不能在未来");
    var existing =
        db.optional(
            "SELECT * FROM elder_vaccine_status WHERE elder_id=:elder AND campaign_id=:campaign FOR"
                + " UPDATE",
            Db.p("elder", id, "campaign", b.campaignId()));
    long sid;
    var values =
        Db.p(
            "elder_id",
            id,
            "campaign_id",
            b.campaignId(),
            "eligibility",
            b.eligibility(),
            "eligibility_checked_by",
            a.id(),
            "eligibility_checked_at",
            utc(),
            "vaccination_status",
            b.vaccinationStatus(),
            "appointment_date",
            b.appointmentDate(),
            "status_effective_at",
            utc(),
            "source",
            "MANUAL");
    if (existing.isEmpty()) {
      Problem.require(b.version() == 0, "新状态版本必须为0");
      values.put("created_by", a.id());
      sid = db.insert("elder_vaccine_status", values);
    } else {
      sid = Db.id(existing.get(), "id");
      values.put("id", sid);
      values.put("actor", a.id());
      values.put("v", b.version());
      Problem.changed(
          db.update(
              "UPDATE elder_vaccine_status SET"
                  + " eligibility=:eligibility,eligibility_checked_by=:actor,eligibility_checked_at=UTC_TIMESTAMP(3),vaccination_status=:vaccination_status,appointment_date=:appointment_date,status_effective_at=UTC_TIMESTAMP(3),source='MANUAL',updated_by=:actor,version=version+1"
                  + " WHERE id=:id AND version=:v",
              values));
    }
    boolean correction =
        existing.isPresent()
            && Set.of("PARTIAL", "COMPLETED")
                .contains(existing.get().get("vaccination_status").toString());
    Long previous =
        correction
            ? db.count(
                "SELECT MAX(id) FROM vaccination_record WHERE elder_vaccine_status_id=:id",
                Db.p("id", sid))
            : null;
    db.insert(
        "vaccination_record",
        Db.p(
            "elder_vaccine_status_id",
            sid,
            "action",
            correction ? "CORRECTION" : b.vaccinatedOn() == null ? "ASSESSMENT" : "VACCINATION",
            "vaccinated_on",
            b.vaccinatedOn(),
            "dose_no",
            b.doseNo(),
            "resulting_status",
            b.vaccinationStatus(),
            "eligibility_snapshot",
            b.eligibility(),
            "effective_at",
            utc(),
            "verified_by",
            a.id(),
            "source",
            "MANUAL",
            "supersedes_record_id",
            previous,
            "reason",
            b.reason(),
            "created_by",
            a.id()));
    // 已预约的提前提醒跟随改期；不会把接收确认当作接种核验。
    if (b.appointmentDate() != null)
      db.update(
          "UPDATE followup_task t JOIN notice_recipient r ON r.id=t.recipient_id JOIN notice n ON"
              + " n.id=r.notice_id SET t.due_at=:due,t.version=t.version+1 WHERE r.elder_id=:elder"
              + " AND n.campaign_id=:campaign AND t.task_type='PRE_VACCINE_REMINDER' AND"
              + " t.status='PENDING'",
          Db.p(
              "due",
              b.appointmentDate()
                  .minusDays(3)
                  .atTime(9, 0)
                  .atZone(ZoneId.of("Asia/Shanghai"))
                  .withZoneSameInstant(ZoneOffset.UTC)
                  .toLocalDateTime(),
              "elder",
              id,
              "campaign",
              b.campaignId()));
    audit.log(
        a,
        "VACCINE_VERIFIED",
        "ELDER",
        id,
        Map.of("campaignId", b.campaignId(), "status", b.vaccinationStatus()));
    return db.one("SELECT * FROM elder_vaccine_status WHERE id=:id", Db.p("id", sid));
  }

  private void validate(Actor a, Draft b) {
    personal.validate(b.personalInfo());
    privacy.check(b.remark());
    YearMonth birth = YearMonth.parse(b.birthMonth());
    Problem.require(
        birth.getYear() >= 1800 && !birth.isAfter(YearMonth.now(ZoneId.of("Asia/Shanghai"))),
        "出生年月无效");
    db.one(
        "SELECT id FROM grid WHERE id=:id AND community_id=:community AND status='ACTIVE'",
        Db.p("id", b.gridId(), "community", a.communityId()));
    if (!b.needIds().isEmpty())
      Problem.require(
          db.count(
                  "SELECT COUNT(*) FROM service_need WHERE id IN (:ids) AND status='ACTIVE'",
                  Db.p("ids", b.needIds()))
              == b.needIds().size(),
          "服务需求无效");
  }

  private void needs(Actor a, long elder, Set<Long> ids) {
    db.update(
        "UPDATE elder_service_need SET status='INACTIVE',version=version+1,updated_by=:actor WHERE"
            + " elder_id=:id",
        Db.p("actor", a.id(), "id", elder));
    for (long id : ids)
      db.update(
          "INSERT INTO elder_service_need(elder_id,need_id,status,created_by)"
              + " VALUES(:elder,:need,'ACTIVE',:actor) ON DUPLICATE KEY UPDATE"
              + " status='ACTIVE',version=version+1,updated_by=:actor",
          Db.p("elder", elder, "need", id, "actor", a.id()));
  }

  public static LocalDateTime utc() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }
}
