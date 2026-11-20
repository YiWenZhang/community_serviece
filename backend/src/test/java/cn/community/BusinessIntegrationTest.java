package cn.community;

import static org.assertj.core.api.Assertions.*;

import cn.community.common.*;
import cn.community.elder.*;
import cn.community.files.*;
import cn.community.followup.*;
import cn.community.identity.*;
import cn.community.notice.*;
import cn.community.reporting.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/** 使用隔离MySQL而不是H2，覆盖JSON、外键、锁、UTC和真实SQL差异。 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
@Transactional
class BusinessIntegrationTest {
  @Autowired Db db;
  @Autowired ElderService elders;
  @Autowired NoticeService notices;
  @Autowired RuleService rules;
  @Autowired ReportService reports;
  @Autowired Crypto crypto;
  @Autowired FileService files;
  @Autowired FollowupController followups;
  @Autowired PasswordEncoder encoder;
  @Autowired cn.community.jobs.JobService jobs;
  private Actor worker;

  @BeforeEach
  void setup() {
    String name = db.one("SELECT DATABASE() AS db", Map.of()).get("db").toString();
    assertThat(name.endsWith("_test") || name.endsWith("_regression"))
        .as("禁止对业务数据库运行集成测试")
        .isTrue();
    db.update("UPDATE sys_user SET status='ACTIVE' WHERE id IN (1,2,3)", Map.of());
    worker = actor(2);
  }

  Actor actor(long id) {
    var roles =
        db.list(
            "SELECT r.code FROM sys_user_role u JOIN sys_role r ON r.id=u.role_id WHERE"
                + " u.user_id=:id",
            Db.p("id", id));
    var perms =
        db.list(
            "SELECT DISTINCT p.code FROM sys_permission p JOIN sys_role_permission rp ON"
                + " rp.permission_id=p.id JOIN sys_user_role ur ON ur.role_id=rp.role_id WHERE"
                + " ur.user_id=:id",
            Db.p("id", id));
    return new Actor(
        id,
        1,
        "test",
        roles.stream()
            .map(r -> r.get("code").toString())
            .collect(java.util.stream.Collectors.toSet()),
        perms.stream()
            .map(r -> r.get("code").toString())
            .collect(java.util.stream.Collectors.toSet()));
  }

  @Test
  void registrationUsesExistingCryptoAndAtomicSequence() {
    var result =
        (Map<?, ?>)
            elders.create(
                worker, new ElderService.Draft(1L, "FEMALE", "1950-05", "虚构测试", Set.of(1L)));
    assertThat(result.get("elderNo")).isEqualTo("SH-L-2026-004");
    var original = elders.detail(worker, 1);
    assertThat(original.get("elderNo")).isEqualTo("SH-L-2026-001");
    assertThat(original).doesNotContainKeys("elder_no_hash", "elder_no_cipher");
  }

  @Test
  void statisticianCannotReadAndOtherOwnerReturns404() {
    assertThatThrownBy(() -> elders.detail(actor(3), 1))
        .isInstanceOfSatisfying(Problem.class, e -> assertThat(e.status).isEqualTo(403));
    var outsider = new Actor(3, 1, "outsider", Set.of("GRID_WORKER"), Set.of("elder:read"));
    assertThatThrownBy(() -> elders.detail(outsider, 1))
        .isInstanceOfSatisfying(Problem.class, e -> assertThat(e.status).isEqualTo(404));
  }

  @Test
  void attemptsReplayAndRejectChangedBody() {
    var body =
        new NoticeService.Attempt(
            0, "HOME_VISIT", "UNREACHABLE", "NO_ONE_HOME", "无人", null, null, null, null, Set.of());
    long count = db.count("SELECT COUNT(*) FROM delivery_attempt", Map.of());
    notices.attempt(worker, 2, "test-attempt", body);
    assertThat(((Map<?, ?>) notices.attempt(worker, 2, "test-attempt", body)).get("replayed"))
        .isEqualTo(true);
    assertThat(db.count("SELECT COUNT(*) FROM delivery_attempt", Map.of())).isEqualTo(count + 1);
    assertThatThrownBy(
            () ->
                notices.attempt(
                    worker,
                    2,
                    "test-attempt",
                    new NoticeService.Attempt(
                        0,
                        "HOME_VISIT",
                        "UNREACHABLE",
                        "NO_ONE_HOME",
                        "不同原因",
                        null,
                        null,
                        null,
                        null,
                        Set.of())))
        .isInstanceOfSatisfying(Problem.class, e -> assertThat(e.status).isEqualTo(409));
  }

  @Test
  void homeConfirmationRequiresEvidenceAndDoesNotWrite() {
    long before = db.count("SELECT COUNT(*) FROM delivery_attempt", Map.of());
    assertThatThrownBy(
            () ->
                notices.attempt(
                    worker,
                    2,
                    "missing-evidence",
                    new NoticeService.Attempt(
                        0,
                        "HOME_VISIT",
                        "CONFIRMED",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Set.of())))
        .isInstanceOf(Problem.class);
    assertThat(db.count("SELECT COUNT(*) FROM delivery_attempt", Map.of())).isEqualTo(before);
    assertThat(notices.recipient(worker, 2, false).get("receipt_status")).isEqualTo("UNREACHABLE");
  }

  @Test
  void notificationNeverMarksVaccineCompleted() {
    notices.attempt(
        worker,
        2,
        "family-confirm",
        new NoticeService.Attempt(
            0, "FAMILY_RELAY", "CONFIRMED", null, null, "已经转告", "张某", "女儿", true, Set.of()));
    assertThat(
            db.one("SELECT vaccination_status FROM elder_vaccine_status WHERE id=2", Map.of())
                .get("vaccination_status"))
        .isEqualTo("NOT_VACCINATED");
    assertThat(notices.recipient(worker, 2, false).get("workflow_status")).isEqualTo("CLOSED");
  }

  @Test
  void transferMovesOpenWorkAndPreservesIssuedOwner() {
    long uid =
        db.insert(
            "sys_user",
            Db.p(
                "community_id",
                1,
                "username",
                "transfer_test",
                "password_hash",
                "disabled",
                "display_name",
                "测试网格员",
                "status",
                "ACTIVE"));
    db.insert("sys_user_role", Db.p("user_id", uid, "role_id", 2));
    db.update("INSERT INTO user_community(user_id,community_id) VALUES(:user,1)", Db.p("user",uid));
    elders.transfer(actor(1), 2, new ElderService.Transfer(0, 1L, uid, "测试移交"));
    assertThat(db.count("SELECT owner_user_id FROM elder_profile WHERE id=2", Map.of()))
        .isEqualTo(uid);
    assertThat(db.count("SELECT assignee_id FROM followup_task WHERE id=2", Map.of()))
        .isEqualTo(uid);
    assertThat(db.count("SELECT owner_at_issue FROM notice_recipient WHERE id=2", Map.of()))
        .isEqualTo(2);
    assertThatThrownBy(() -> elders.detail(worker, 2)).isInstanceOf(Problem.class);
  }

  @Test
  void monthlyRatiosUseEventsAndExcludeUnknownEligibility() {
    var result = reports.metrics(actor(3), new ReportService.Query("2026-10", null, null, null));
    @SuppressWarnings("unchecked")
    var rows = (List<Map<String, Object>>) result.get("metrics");
    var coverage =
        rows.stream()
            .filter(m -> "NOTICE_COVERAGE".equals(m.get("code")))
            .findFirst()
            .orElseThrow();
    assertThat(coverage.get("numerator")).isEqualTo(1L);
    assertThat(coverage.get("denominator")).isEqualTo(2L);
    var vaccinated =
        rows.stream()
            .filter(m -> "VACCINATION_RATE".equals(m.get("code")))
            .findFirst()
            .orElseThrow();
    assertThat(vaccinated.get("denominator")).isEqualTo(2L);
  }

  @Test
  void ruleGenerationDoesNotDuplicateSameMonth() {
    db.update("UPDATE notice_rule SET enabled=1 WHERE id=1", Map.of());
    rules.generate(worker, 1, "2026-10", "test-rule-1");
    long first =
        db.count(
            "SELECT COUNT(*) FROM notice_recipient WHERE automation_key IS NOT NULL", Map.of());
    rules.generate(worker, 1, "2026-10", "test-rule-2");
    assertThat(first).isEqualTo(1);
    assertThat(
            db.count(
                "SELECT COUNT(*) FROM notice_recipient WHERE automation_key IS NOT NULL", Map.of()))
        .isEqualTo(first);
  }

  @Test
  void feedbackIsAppendOnly() {
    notices.feedback(worker, 1, new NoticeService.Feedback(1, "NEUTRAL", "FAMILY", "更正评价"));
    assertThat(db.count("SELECT COUNT(*) FROM service_feedback WHERE recipient_id=1", Map.of()))
        .isEqualTo(2);
    assertThat(
            db.one(
                    "SELECT rating FROM service_feedback WHERE recipient_id=1 AND revision_no=1",
                    Map.of())
                .get("rating"))
        .isEqualTo("SATISFIED");
  }

  @Test
  void evidenceRequiresExplicitRedactionAndRejectsUnrelatedReader() throws Exception {
    var image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(image, "png", bytes);
    var result =
        (Map<?, ?>)
            files.upload(
                worker,
                new MockMultipartFile("file", "test.png", "image/png", bytes.toByteArray()));
    long id = ((Number) result.get("id")).longValue();
    assertThatThrownBy(() -> files.attachable(worker, id)).isInstanceOf(Problem.class);
    files.finalizeFile(worker, id, true);
    files.attachable(worker, id);
    assertThatThrownBy(() -> files.readable(actor(3), id)).isInstanceOf(Problem.class);
    db.insert(
        "delivery_attachment",
        Db.p(
            "delivery_attempt_id",
            3,
            "file_id",
            id,
            "evidence_type",
            "OTHER_ALLOWED",
            "created_by",
            2));
    db.update("UPDATE elder_profile SET owner_user_id=3 WHERE id=2", Map.of());
    assertThatThrownBy(() -> files.readable(worker, id)).isInstanceOf(Problem.class);
    var newOwner = new Actor(3, 1, "new-owner", Set.of("GRID_WORKER"), Set.of("file:read"));
    assertThat(files.readable(newOwner, id).get("id").toString()).isEqualTo(Long.toString(id));
  }

  @Test
  void jobReplayComparesJsonSemanticsNotMysqlObjectKeyOrder() {
    var first =
        (Map<?, ?>)
            jobs.enqueue(
                worker,
                "NOTICE_GENERATION",
                "test-job-order",
                Db.p("ruleId", 1, "period", "2026-10"));
    var second =
        (Map<?, ?>)
            jobs.enqueue(
                worker,
                "NOTICE_GENERATION",
                "test-job-order",
                Db.p("ruleId", 1, "period", "2026-10"));
    assertThat(second.get("id").toString()).isEqualTo(first.get("id").toString());
    assertThat(second.get("replayed")).isEqualTo(true);
  }

  @Test
  void initialVaccineConfirmationPreservesThreeDayReminder() {
    var batch =
        (Map<?, ?>)
            notices.create(
                worker,
                new NoticeService.Draft("VACCINE", "测试预约", "虚构服务", Set.of(2L), 1L, "2026-10"));
    long nid = ((Number) batch.get("id")).longValue();
    notices.activate(worker, nid, 0);
    long rid = db.count("SELECT id FROM notice_recipient WHERE notice_id=:id", Db.p("id", nid));
    notices.attempt(
        worker,
        rid,
        "test-keep-reminder",
        new NoticeService.Attempt(
            1, "FAMILY_RELAY", "CONFIRMED", null, null, "明确转告", "张某", "女儿", true, Set.of()));
    assertThat(notices.recipient(worker, rid, false).get("workflow_status")).isEqualTo("FOLLOWING");
    assertThat(
            db.count(
                "SELECT COUNT(*) FROM followup_task WHERE recipient_id=:id AND"
                    + " task_type='PRE_VACCINE_REMINDER' AND status='PENDING'",
                Db.p("id", rid)))
        .isEqualTo(1);
  }

  @Test
  void reportRetryReturnsSameJob() {
    var request =
        new ReportService.Export(new ReportService.Query("2026-10", null, null, null), "XLSX");
    var first = (Map<?, ?>) reports.enqueue(actor(3), "report-idempotency-test", request);
    var second = (Map<?, ?>) reports.enqueue(actor(3), "report-idempotency-test", request);
    assertThat(second.get("reportJobId").toString()).isEqualTo(first.get("reportJobId").toString());
  }

  @Test
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  void databaseFailureAfterWritesRollsBackAttemptAndRecipient() {
    long before = db.count("SELECT COUNT(*) FROM delivery_attempt", Map.of());
    long probe =
        db.insert(
            "notice_recipient_event",
            Db.p(
                "recipient_id",
                2,
                "event_type",
                "RECEIPT_CHANGED",
                "from_receipt_status",
                "UNREACHABLE",
                "to_receipt_status",
                "UNREACHABLE",
                "from_workflow_status",
                "FOLLOWING",
                "to_workflow_status",
                "FOLLOWING",
                "effective_at",
                ElderService.utc(),
                "owner_snapshot_id",
                2,
                "request_key",
                "attempt-rollback-probe"));
    try {
      assertThatThrownBy(
              () ->
                  notices.attempt(
                      worker,
                      2,
                      "rollback-probe",
                      new NoticeService.Attempt(
                          0,
                          "HOME_VISIT",
                          "UNREACHABLE",
                          "NO_ONE_HOME",
                          "回滚验证",
                          null,
                          null,
                          null,
                          null,
                          Set.of())))
          .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
      assertThat(db.count("SELECT COUNT(*) FROM delivery_attempt", Map.of())).isEqualTo(before);
      assertThat(db.count("SELECT version FROM notice_recipient WHERE id=2", Map.of())).isZero();
    } finally {
      db.update("DELETE FROM notice_recipient_event WHERE id=:id", Db.p("id", probe));
    }
  }
}
