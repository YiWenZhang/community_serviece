package cn.community.elder;

import cn.community.common.*;
import cn.community.identity.Auth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/elders")
@Tag(name = "03 老人档案与接种核验")
public class ElderController {
  private final ElderService service;
  private final Auth auth;

  @GetMapping
  @Operation(summary = "本人老人分页，编号精确检索及出生月份疫苗需求筛选")
  public Object list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) String elderNo,
      @RequestParam(required = false) Integer birthMonth,
      @RequestParam(required = false) String vaccinationStatus,
      @RequestParam(required = false) Long needId) {
    return Api.ok(
        service.list(
            auth.current(), page, pageSize, elderNo, birthMonth, vaccinationStatus, needId));
  }

  @GetMapping("/{id}")
  @Operation(summary = "本人档案详情及需求疫苗信息")
  public Object detail(@PathVariable long id) {
    return Api.ok(service.detail(auth.current(), id));
  }

  @PostMapping
  @Operation(summary = "登记老人，事务分配编号并加密")
  public Object create(@Valid @RequestBody ElderService.Draft b) {
    return Api.ok(service.create(auth.current(), b));
  }

  @PatchMapping("/{id}")
  @Operation(summary = "维护本人档案和需求，校验version")
  public Object update(@PathVariable long id, @Valid @RequestBody ElderService.Edit b) {
    return Api.ok(service.update(auth.current(), id, b));
  }

  @PostMapping("/{id}/transfer")
  @Operation(summary = "管理员原子移交档案及未完成通知跟进")
  public Object transfer(@PathVariable long id, @Valid @RequestBody ElderService.Transfer b) {
    return Api.ok(service.transfer(auth.current(), id, b));
  }

  @PutMapping("/{id}/vaccines")
  @Operation(summary = "人工核验具体疫苗周期资格、预约及接种事实")
  public Object vaccine(@PathVariable long id, @Valid @RequestBody ElderService.Vaccine b) {
    return Api.ok(service.vaccine(auth.current(), id, b));
  }

  @GetMapping("/{id}/history")
  @Operation(summary = "本人档案接种和归属时间线")
  public Object history(@PathVariable long id) {
    var a = auth.require("elder:read");
    service.owned(a, id, false);
    return Api.ok(
        Db.p(
            "assignments",
            db.list(
                "SELECT * FROM elder_assignment_history WHERE elder_id=:id ORDER BY"
                    + " effective_at,id",
                Db.p("id", id)),
            "vaccinations",
            db.list(
                "SELECT r.* FROM vaccination_record r JOIN elder_vaccine_status s ON"
                    + " s.id=r.elder_vaccine_status_id WHERE s.elder_id=:id ORDER BY"
                    + " r.effective_at,r.id",
                Db.p("id", id))));
  }

  private final Db db;

  @org.springframework.beans.factory.annotation.Autowired
  public ElderController(ElderService service, Auth auth, Db db) {
    this.service = service;
    this.auth = auth;
    this.db = db;
  }
}
