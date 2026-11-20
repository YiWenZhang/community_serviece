package cn.community;

import static org.assertj.core.api.Assertions.*;

import cn.community.common.*;
import cn.community.elder.*;
import cn.community.files.*;
import cn.community.identity.*;
import cn.community.notice.*;
import cn.community.reporting.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
class MultiCommunityIntegrationTest {
  @Autowired Db db;
  @Autowired Auth auth;
  @Autowired ElderService elders;
  @Autowired NoticeService notices;
  @Autowired ReportService reports;
  @Autowired FileService files;
  long community, grid, other;
  Actor admin, worker;

  @BeforeEach
  void prepare() {
    assertThat(db.one("SELECT DATABASE() AS db", Map.of()).get("db").toString())
        .matches(".*(_test|_regression)$");
    db.update("UPDATE sys_user SET status='ACTIVE' WHERE id IN (1,2,3)", Map.of());
    community = db.insert("community", Db.p("code", "MULTI-TEST", "name", "第二测试社区"));
    db.update(
        "INSERT INTO community_profile(community_id,description) VALUES(:c,'虚构社区')",
        Db.p("c", community));
    grid = db.insert("grid", Db.p("community_id", community, "code", "T-2", "name", "测试网格"));
    db.update(
        "INSERT INTO user_community(user_id,community_id) VALUES(2,:c)", Db.p("c", community));
    other =
        db.insert(
            "sys_user",
            Db.p(
                "community_id",
                1,
                "username",
                "multi_worker",
                "display_name",
                "另一个测试网格员",
                "password_hash",
                "!disabled",
                "status",
                "ACTIVE"));
    db.insert("sys_user_role", Db.p("user_id", other, "role_id", 2));
    db.update(
        "INSERT INTO user_community(user_id,community_id) VALUES(:u,:c)",
        Db.p("u", other, "c", community));
    admin = auth.worker(1, community);
    worker = auth.worker(2, community);
  }

  long elder(Actor a, Long owner) {
    return ((Number)
            ((Map<?, ?>)
                    elders.create(
                        a,
                        new ElderService.Draft(
                            grid, "FEMALE", "1950-05", "虚构测试", Set.of(1L), owner)))
                .get("id"))
        .longValue();
  }

  long notice(long elder) {
    long id =
        ((Number)
                ((Map<?, ?>)
                        notices.create(
                            worker,
                            new NoticeService.Draft(
                                "OTHER", "演示通知", "虚构通知", Set.of(elder), null, "test")))
                    .get("id"))
            .longValue();
    notices.activate(worker, id, 0);
    return id;
  }

  @Test
  void workerCanEnterTwoCommunitiesButCannotReadColleaguesElders() {
    assertThat(auth.communities(worker)).hasSize(2);
    long mine = elder(worker, null), theirs = elder(auth.worker(other, community), null);
    assertThat(elders.detail(worker, mine)).containsKey("elderNo");
    assertThatThrownBy(() -> elders.detail(worker, theirs))
        .isInstanceOfSatisfying(Problem.class, e -> assertThat(e.status).isEqualTo(404));
    assertThatThrownBy(() -> elders.detail(auth.worker(2, 1), mine)).isInstanceOf(Problem.class);
  }

  @Test
  void adminReadsAndAssignsWhileWorkerMaintainsCommunityElders() {
    assertThatThrownBy(() -> elder(admin, 2L)).isInstanceOf(Problem.class);
    long id = elder(worker, null);
    var d = elders.detail(admin, id);
    assertThat(d.get("owner_user_id").toString()).isEqualTo("2");
    elders.update(
        worker,
        id,
        new ElderService.Edit(
            0, new ElderService.Draft(grid, "MALE", "1950-06", "更新虚构备注", Set.of()), "ACTIVE"));
    assertThat(elders.detail(worker, id)).containsEntry("remark", "更新虚构备注");
  }

  @Test
  void revokingCommunityImmediatelyBlocksWorkerAndBackgroundIdentity() {
    db.update(
        "UPDATE user_community SET status='INACTIVE' WHERE user_id=2 AND community_id=:c",
        Db.p("c", community));
    assertThatThrownBy(() -> auth.worker(2, community))
        .isInstanceOfSatisfying(Problem.class, e -> assertThat(e.status).isEqualTo(403));
    assertThat(auth.communities(worker)).hasSize(1);
  }

  @Test
  void workerCannotAssignSomeoneElseOrUseUnassignedCommunity() {
    assertThatThrownBy(() -> elder(worker, other)).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> auth.worker(other, 1)).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> auth.sameCommunityUser(auth.worker(1, 1), other, true))
        .isInstanceOf(Problem.class);
  }

  @Test
  void statisticsAggregateAllCommunitiesAndCanFilterOne() {
    long id = elder(worker, null);
    notice(id);
    var stat = auth.worker(3, 1);
    String month = YearMonth.now(ZoneId.of("Asia/Shanghai")).toString();
    var all = reports.metrics(stat, new ReportService.Query(month, null, null, null));
    var selected =
        reports.metrics(stat, new ReportService.Query(month, null, null, null, community));
    assertThat(all.get("scopeLabel")).isEqualTo("全部社区");
    assertThat(denominator(all)).isEqualTo(denominator(selected)).isGreaterThan(0);
    assertThat(
            denominator(
                reports.metrics(stat, new ReportService.Query(month, null, null, null, 1L))))
        .isZero();
    assertThatThrownBy(
            () -> reports.metrics(worker, new ReportService.Query(month, null, null, null, 1L)))
        .isInstanceOf(Problem.class);
  }

  long denominator(Map<String, Object> result) {
    return ((List<Map<String, Object>>) result.get("metrics"))
        .stream()
            .filter(m -> m.get("code").equals("NOTICE_COVERAGE"))
            .mapToLong(m -> ((Number) m.get("denominator")).longValue())
            .findFirst()
            .orElseThrow();
  }

  @Test
  void statsCannotModifyEvenIfBusinessPermissionWasGranted() {
    var stat =
        new Actor(
            3,
            community,
            "stats",
            Set.of("STATISTICIAN"),
            Set.of("elder:create", "elder:read", "system:user:manage", "report:read:community"));
    assertThat(stat.has("elder:create")).isFalse();
    assertThatThrownBy(() -> elders.detail(stat, 1)).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> stat.require("system:user:manage")).isInstanceOf(Problem.class);
  }

  @Test
  void crossCommunityTransferRequiresClosedWorkAndPreservesHistory() {
    long id = elder(worker, null), notice = notice(id);
    assertThatThrownBy(
            () -> elders.transfer(admin, id, new ElderService.Transfer(0, 1L, 2L, "跨社区移交", 1L)))
        .isInstanceOf(Problem.class)
        .hasMessageContaining("未闭环");
    notices.cancel(worker, notice, new NoticeService.Reason(1, "移交前取消"));
    elders.transfer(admin, id, new ElderService.Transfer(0, 1L, 2L, "跨社区移交", 1L));
    assertThat(elders.detail(auth.worker(2, 1), id).get("community_id").toString()).isEqualTo("1");
    assertThat(
            db.one("SELECT community_id FROM notice_recipient WHERE elder_id=:id", Db.p("id", id))
                .get("community_id")
                .toString())
        .isEqualTo(Long.toString(community));
  }

  @Test
  void adminCannotCreateNoticeAndWorkerRetainsResponsibility() {
    long id = elder(worker, null);
    assertThatThrownBy(
            () ->
                notices.create(
                    admin,
                    new NoticeService.Draft("OTHER", "越权代办", "管理员不能代办", Set.of(id), null, "multi")))
        .isInstanceOf(Problem.class);
    long n =
        ((Number)
                ((Map<?, ?>)
                        notices.create(
                            worker,
                            new NoticeService.Draft(
                                "OTHER", "管理员通知", "测试内容", Set.of(id), null, "multi")))
                    .get("id"))
            .longValue();
    notices.activate(worker, n, 0);
    assertThat(
            db.one("SELECT owner_at_issue FROM notice_recipient WHERE notice_id=:id", Db.p("id", n))
                .get("owner_at_issue")
                .toString())
        .isEqualTo("2");
    assertThat(notices.detail(worker, n)).isNotNull();
  }

  @Test
  void crossCommunityTransferDoesNotRewritePastMonthlyStatistics() {
    var stat = auth.worker(3, 1);
    var q = new ReportService.Query("2026-10", null, null, null, 1L);
    var before = reports.metrics(stat, q).get("metrics");
    var sourceAdmin = auth.worker(1, 1);
    notices.cancel(auth.worker(2, 1), 1, new NoticeService.Reason(0, "历史口径验证移交前取消"));
    elders.transfer(sourceAdmin, 1, new ElderService.Transfer(0, grid, 2L, "历史口径验证", community));
    assertThat(reports.metrics(stat, q).get("metrics")).isEqualTo(before);
  }
}
