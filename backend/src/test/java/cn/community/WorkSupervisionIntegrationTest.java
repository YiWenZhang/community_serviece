package cn.community;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.community.common.*;
import cn.community.elder.*;
import cn.community.followup.FollowupController;
import cn.community.identity.*;
import cn.community.notice.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
class WorkSupervisionIntegrationTest {
  @Autowired Db db;
  @Autowired Auth auth;
  @Autowired ElderService elders;
  @Autowired NoticeService notices;
  @Autowired WorkController work;
  @Autowired SmsService sms;
  @Autowired Privacy privacy;
  @Autowired Audit audit;
  @Autowired Json json;
  @Autowired Crypto crypto;
  Actor admin, worker;
  long nid, rid;

  @BeforeEach
  void prepare() {
    assertThat(db.one("SELECT DATABASE() AS d", Map.of()).get("d").toString())
        .endsWith("_regression");
    admin = auth.worker(1, 1);
    worker = auth.worker(2, 1);
    long e =
        ((Number)
                ((Map<?, ?>)
                        elders.create(
                            worker,
                            new ElderService.Draft(
                                1L, "MALE", "1950-05", "服务监督验证", Set.of(), null)))
                    .get("id"))
            .longValue();
    nid =
        ((Number)
                ((Map<?, ?>)
                        notices.create(
                            worker,
                            new NoticeService.Draft(
                                "OTHER", "服务进度核验", "上门告知工作", Set.of(e), null, "2026-11")))
                    .get("id"))
            .longValue();
    notices.activate(worker, nid, 0);
    rid = db.count("SELECT id FROM notice_recipient WHERE notice_id=:id", Db.p("id", nid));
    notices.attempt(
        worker,
        rid,
        "supervision-failed-contact",
        new NoticeService.Attempt(
            1, "HOME_VISIT", "UNREACHABLE", "NO_ONE_HOME", null, null, null, null, null, Set.of()));
    db.update(
        "UPDATE followup_task SET due_at=UTC_TIMESTAMP(3)-INTERVAL 1 HOUR WHERE recipient_id=:id",
        Db.p("id", rid));
  }

  @Test
  void adminCannotPerformBusinessEvenWithAllDatabasePermissions() {
    for (String p :
        List.of(
            "elder:create",
            "elder:update",
            "vaccine:verify",
            "notice:create",
            "notice:receive",
            "followup:update",
            "feedback:create")) {
      assertThat(admin.has(p)).as(p).isFalse();
      assertThatThrownBy(() -> admin.require(p))
          .isInstanceOfSatisfying(Problem.class, x -> assertThat(x.status).isEqualTo(403));
    }
    assertThat(admin.has("notice:rule:manage")).isTrue();
    assertThat(admin.has("elder:transfer")).isTrue();
    var version =
        Db.id(
            db.one("SELECT version FROM notice_recipient WHERE id=:id", Db.p("id", rid)),
            "version");
    assertThatThrownBy(
            () ->
                notices.attempt(
                    admin,
                    rid,
                    "admin-should-not-contact",
                    new NoticeService.Attempt(
                        (int) version,
                        "HOME_VISIT",
                        "UNREACHABLE",
                        "NO_ONE_HOME",
                        null,
                        null,
                        null,
                        null,
                        null,
                        Set.of())))
        .isInstanceOfSatisfying(Problem.class, x -> assertThat(x.status).isEqualTo(403));
    assertThatThrownBy(() -> sms.enqueue(admin, rid, "admin-should-not-sms", (int) version))
        .isInstanceOfSatisfying(Problem.class, x -> assertThat(x.status).isEqualTo(403));
    assertThatThrownBy(
            () ->
                notices.feedback(
                    admin, rid, new NoticeService.Feedback(0, "SATISFIED", "ELDER", "同意参加")))
        .isInstanceOfSatisfying(Problem.class, x -> assertThat(x.status).isEqualTo(403));
  }

  @Test
  void supervisionAggregatesByResponsibleWorkerAndIgnoresDraftsAndCancelledNotices() {
    var all = work.overview(admin, "OTHER", worker.id());
    var rows = (List<Map<String, Object>>) all.get("items");
    assertThat(rows).hasSize(1);
    var row = rows.getFirst();
    assertThat(((Number) row.get("pending")).longValue()).isGreaterThanOrEqualTo(1);
    assertThat(((Number) row.get("overdue")).longValue()).isGreaterThanOrEqualTo(1);
    assertThat(((Number) row.get("followup_pending")).longValue()).isGreaterThanOrEqualTo(1);
    var total = row.get("total");
    var recipient = notices.recipient(worker, rid, false);
    notices.create(
        worker,
        new NoticeService.Draft(
            "OTHER", "尚未发布", "草稿不计入", Set.of(Db.id(recipient, "elder_id")), null, "2026-11"));
    assertThat(
            ((List<Map<String, Object>>) work.overview(admin, "OTHER", worker.id()).get("items"))
                .getFirst()
                .get("total"))
        .isEqualTo(total);
    assertThat(work.overview(admin, "OTHER", 999999L).get("items")).asList().isEmpty();
    assertThatThrownBy(() -> work.overview(worker, null, null))
        .isInstanceOfSatisfying(Problem.class, x -> assertThat(x.status).isEqualTo(403));
  }

  @Test
  void assigneeAndDeadlineFiltersIntersectWorkerScopeAndPreserveHistoryContext() {
    assertThat(
            ((Map<?, ?>) notices.work(admin, 1, 100, null, "OTHER", "TODO", nid, worker.id()))
                .get("total")
                .toString())
        .isEqualTo("1");
    assertThat(
            ((Map<?, ?>) notices.work(worker, 1, 100, null, "OTHER", "TODO", nid, 999999L))
                .get("total")
                .toString())
        .isEqualTo("0");
    var current = mock(Auth.class);
    when(current.require("followup:read")).thenReturn(admin);
    var followups = new FollowupController(db, current, notices, privacy, audit, json, crypto);
    String result =
        followups
            .list(1, 100, "OPEN", false, rid, "OTHER", worker.id(), "RECONTACT", "OVERDUE")
            .toString();
    assertThat(result).contains("total=1", "noticeTitle=服务进度核验");
    assertThat(followups.list(1, 100, "OPEN", false, rid, "OTHER", 999999L, null, "ALL").toString())
        .contains("total=0");
    long task =
        db.count(
            "SELECT id FROM followup_task WHERE recipient_id=:id AND status='PENDING'",
            Db.p("id", rid));
    assertThat(followups.detail(task).toString()).contains("noticeTitle=服务进度核验", "assigneeName");
    when(current.require("followup:read")).thenReturn(worker);
    assertThat(followups.list(1, 100, "OPEN", false, rid, null, 999999L, null, "ALL").toString())
        .contains("total=0");
    assertThatThrownBy(
            () -> followups.list(1, 100, "INVALID", false, null, null, null, null, "ALL"))
        .isInstanceOf(Problem.class);
    var tomorrow =
        java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
            .plusDays(1)
            .atTime(12, 0)
            .atZone(java.time.ZoneId.of("Asia/Shanghai"))
            .withZoneSameInstant(java.time.ZoneOffset.UTC)
            .toLocalDateTime();
    db.update(
        "UPDATE followup_task SET due_at=:due WHERE id=:id", Db.p("due", tomorrow, "id", task));
    assertThat(followups.list(1, 100, "OPEN", false, rid, null, null, null, "TODAY").toString())
        .contains("total=0");
    assertThat(followups.list(1, 100, "OPEN", false, rid, null, null, null, "NEXT7").toString())
        .contains("total=1");
    db.update(
        "UPDATE followup_task SET due_at=:due WHERE id=:id",
        Db.p("due", tomorrow.plusDays(8), "id", task));
    assertThat(followups.list(1, 100, "OPEN", false, rid, null, null, null, "NEXT7").toString())
        .contains("total=0");
  }
}
