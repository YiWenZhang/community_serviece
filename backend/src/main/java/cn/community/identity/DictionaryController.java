package cn.community.identity;

import cn.community.common.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/settings")
@Tag(name = "09 社区与疫苗服务配置")
public class DictionaryController {
  public record Name(
      @NotBlank @Pattern(regexp = "[A-Z0-9_-]{1,32}") String code,
      @NotBlank @Size(max = 64) String name) {}

  public record Campaign(
      @NotNull Long vaccineId,
      @NotBlank @Size(max = 32) String cycleKey,
      @NotBlank @Size(max = 32) String doseStage,
      @NotBlank @Size(max = 100) String name,
      @NotNull LocalDate startDate,
      @NotNull LocalDate endDate,
      @Size(max = 200) String eligibilityRuleRef) {}

  private final Db db;
  private final Auth auth;
  private final Audit audit;
  private final Privacy privacy;

  public DictionaryController(Db db, Auth auth, Audit audit, Privacy privacy) {
    this.db = db;
    this.auth = auth;
    this.audit = audit;
    this.privacy = privacy;
  }

  @PostMapping("/grids")
  @Transactional
  @Operation(summary = "管理员新增本社区网格")
  public Object grid(@Valid @RequestBody Name b) {
    var a = auth.require("system:config:manage");
    privacy.check(b.name());
    long id =
        db.insert(
            "grid",
            Db.p(
                "community_id",
                a.communityId(),
                "code",
                b.code(),
                "name",
                b.name(),
                "created_by",
                a.id()));
    audit.log(a, "GRID_CREATED", "GRID", id, Map.of());
    return Api.ok(Map.of("id", id));
  }

  @PostMapping("/service-needs")
  @Transactional
  @Operation(summary = "新增服务需求字典，不修改档案表结构")
  public Object need(@Valid @RequestBody Name b) {
    return dictionary("service_need", b);
  }

  @PostMapping("/vaccine-products")
  @Transactional
  @Operation(summary = "新增疫苗产品字典")
  public Object vaccine(@Valid @RequestBody Name b) {
    return dictionary("vaccine_product", b);
  }

  private Object dictionary(String table, Name b) {
    var a = auth.require("system:config:manage");
    privacy.check(b.name());
    long id = db.insert(table, Db.p("code", b.code(), "name", b.name(), "created_by", a.id()));
    audit.log(a, "DICTIONARY_CREATED", table, id, Map.of());
    return Api.ok(Map.of("id", id));
  }

  @PostMapping("/vaccine-campaigns")
  @Transactional
  @Operation(summary = "新增年度周期和接种阶段，资格由授权人员核验")
  public Object campaign(@Valid @RequestBody Campaign b) {
    var a = auth.require("system:config:manage");
    Problem.require(!b.startDate().isAfter(b.endDate()), "开始日期不得晚于结束日期");
    privacy.check(b.name());
    privacy.check(b.eligibilityRuleRef());
    db.one(
        "SELECT id FROM vaccine_product WHERE id=:id AND status='ACTIVE'",
        Db.p("id", b.vaccineId()));
    long id =
        db.insert(
            "vaccine_campaign",
            Db.p(
                "community_id",
                a.communityId(),
                "vaccine_id",
                b.vaccineId(),
                "cycle_key",
                b.cycleKey(),
                "dose_stage",
                b.doseStage(),
                "name",
                b.name(),
                "start_date",
                b.startDate(),
                "end_date",
                b.endDate(),
                "eligibility_rule_ref",
                b.eligibilityRuleRef(),
                "created_by",
                a.id()));
    audit.log(a, "VACCINE_CAMPAIGN_CREATED", "CAMPAIGN", id, Map.of());
    return Api.ok(Map.of("id", id));
  }
}
