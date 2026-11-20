package cn.community;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.community.common.*;
import cn.community.elder.*;
import cn.community.identity.*;
import cn.community.notice.*;
import cn.community.reporting.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "app.storage=${APP_STORAGE}/workflow-completion")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
class WorkflowCompletionIntegrationTest {
  @Autowired Db db;
  @Autowired Auth auth;
  @Autowired ElderService elders;
  @Autowired NoticeService notices;
  @Autowired SmsService sms;
  @Autowired Crypto crypto;
  @Autowired ReportService reports;
  @Autowired RuleService rules;
  @Autowired Json json;

  @org.springframework.beans.factory.annotation.Value("${app.storage}")
  String storage;

  @MockitoBean SmsGateway gateway;
  Actor admin, worker;

  @BeforeEach
  void prepare() {
    assertThat(db.one("SELECT DATABASE() AS d", Map.of()).get("d").toString())
        .endsWith("_regression");
    admin = auth.worker(1, 1);
    worker = auth.worker(2, 1);
  }

  long elder(boolean consent) {
    return ((Number)
            ((Map<?, ?>)
                    elders.create(
                        worker,
                        new ElderService.Draft(
                            1L,
                            "MALE",
                            Year.now().minusYears(75)
                                + "-"
                                + String.format("%02d", LocalDate.now().getMonthValue()),
                            "日常关怀",
                            Set.of(1L),
                            null,
                            new ElderPersonalInfo.Info(
                                "宋江",
                                "19900000001",
                                "智园社区1号楼1单元101室",
                                "宋仁",
                                "女儿",
                                "18800000001",
                                consent))))
                .get("id"))
        .longValue();
  }

  long recipient(long elder) {
    long n =
        ((Number)
                ((Map<?, ?>)
                        notices.create(
                            worker,
                            new NoticeService.Draft(
                                "BIRTHDAY",
                                "生日月份关怀",
                                "请按社区安排参加活动。",
                                Set.of(elder),
                                null,
                                YearMonth.now().toString())))
                    .get("id"))
            .longValue();
    notices.activate(worker, n, 0);
    return db.count("SELECT id FROM notice_recipient WHERE notice_id=:n", Db.p("n", n));
  }

  void enableSms() {
    db.update(
        "UPDATE community_profile SET sms_config_cipher=:c WHERE community_id=1",
        Db.p(
            "c",
            crypto.encrypt(
                json.write(
                    Db.p(
                        "enabled",
                        true,
                        "endpoint",
                        "https://example.com/send",
                        "apiKey",
                        "unit-key",
                        "signature",
                        "社区服务",
                        "templateId",
                        "unit-template")))));
  }

  @Test
  void birthFiltersAndAdministratorCareListRespectOwnership() {
    long e = elder(false);
    String month = YearMonth.now().toString();
    var list = (Map<?, ?>) elders.birthdayCare(admin, month, 1);
    assertThat(list.get("items").toString())
        .contains("宋江", "elderNo")
        .doesNotContain("personal_info_cipher", "18800000001");
    assertThat(
            ((Map<?, ?>)
                    elders.list(
                        worker,
                        1,
                        20,
                        null,
                        null,
                        null,
                        null,
                        "宋江",
                        null,
                        Year.now().minusYears(75) + "-01",
                        Year.now().minusYears(75) + "-12"))
                .get("total")
                .toString())
        .isEqualTo("1");
    var stats = new Actor(3, 1, "stats", Set.of("STATISTICIAN"), admin.permissions());
    assertThatThrownBy(() -> elders.birthdayCare(stats, month, 1)).isInstanceOf(Problem.class);
    assertThat(elders.detail(worker, e)).containsEntry("familySmsAllowed", false);
  }

  @Test
  void workQueueClassifiesUrgencyAndKeepsBusinessAndOwnerContextScoped() {
    long rid = recipient(elder(false));
    long notice = db.count("SELECT notice_id FROM notice_recipient WHERE id=:id", Db.p("id", rid));
    var first = (Map<?, ?>) notices.work(worker, 1, 20, null, "BIRTHDAY", "FIRST", notice);
    assertThat(first.get("total").toString()).isEqualTo("1");
    assertThat(first.get("items").toString())
        .contains("notice_title=生日月份关怀", "owner_name")
        .doesNotContain("personal_info_cipher");
    notices.attempt(
        worker,
        rid,
        "work-ui-unreachable",
        new NoticeService.Attempt(
            1,
            "HOME_VISIT",
            "UNREACHABLE",
            "NO_ONE_HOME",
            "上门无人",
            null,
            null,
            null,
            null,
            Set.of()));
    assertThat(
            ((Map<?, ?>) notices.work(worker, 1, 20, null, "BIRTHDAY", "RETRY", notice))
                .get("total")
                .toString())
        .isEqualTo("1");
    assertThat(
            ((Map<?, ?>) notices.work(worker, 1, 20, null, "VACCINE", "TODO", notice))
                .get("total")
                .toString())
        .isEqualTo("0");
    db.update(
        "UPDATE followup_task SET due_at=UTC_TIMESTAMP(3)-INTERVAL 1 DAY WHERE recipient_id=:id",
        Db.p("id", rid));
    assertThat(
            ((Map<?, ?>) notices.work(worker, 1, 20, null, null, "OVERDUE", notice))
                .get("total")
                .toString())
        .isEqualTo("1");
    var other = new Actor(999999, 1, "other", Set.of("GRID_WORKER"), worker.permissions());
    assertThat(
            ((Map<?, ?>) notices.work(other, 1, 20, null, null, "TODO", notice))
                .get("total")
                .toString())
        .isEqualTo("0");
    var batch = (Map<?, ?>) notices.list(admin, 1, 100, "BIRTHDAY", "ACTIVE");
    assertThat(batch.get("items").toString()).contains("contact_total=1", "contact_open=1");
  }

  @Test
  void familyRelayEncryptsActualNameAndRequiresFeedback() {
    long r = recipient(elder(false));
    assertThatThrownBy(
            () ->
                notices.attempt(
                    worker,
                    r,
                    "missing-quote",
                    new NoticeService.Attempt(
                        1,
                        "FAMILY_RELAY",
                        "CONFIRMED",
                        null,
                        null,
                        "",
                        null,
                        "女儿",
                        true,
                        Set.of(),
                        "宋仁")))
        .isInstanceOf(Problem.class);
    notices.attempt(
        worker,
        r,
        "family-private",
        new NoticeService.Attempt(
            1, "FAMILY_RELAY", "CONFIRMED", null, null, "同意参加。", null, "女儿", true, Set.of(), "宋仁"));
    var raw = db.one("SELECT * FROM delivery_attempt WHERE recipient_id=:id", Db.p("id", r));
    assertThat(raw).containsEntry("family_name_masked", "宋某");
    assertThat(crypto.decrypt((byte[]) raw.get("family_name_cipher"))).isEqualTo("宋仁");
    assertThat(notices.displayAttempts(List.of(raw)).toString())
        .contains("familyName=宋仁")
        .doesNotContain("family_name_cipher");
  }

  @Test
  void smsUsesOnlyAuthorizedFamilyPhoneAndDoesNotConfirmReceipt() {
    long r = recipient(elder(true));
    enableSms();
    when(gateway.send(anyMap(), anyString(), anyString(), anyMap())).thenReturn("provider-001");
    long job =
        ((Number) ((Map<?, ?>) sms.enqueue(worker, r, "sms-test-request", 1)).get("id"))
            .longValue();
    assertThat(((Map<?, ?>) sms.enqueue(worker, r, "sms-test-request", 1)).get("replayed"))
        .isEqualTo(true);
    var payload =
        json.map(
            db.one("SELECT payload_json FROM job_run WHERE id=:id", Db.p("id", job))
                .get("payload_json"));
    assertThat(json.write(payload)).doesNotContain("18800000001", "19900000001");
    sms.dispatch(worker, job, payload);
    sms.dispatch(worker, job, payload);
    verify(gateway, times(1))
        .send(anyMap(), eq("community-sms-" + job), eq("18800000001"), anyMap());
    var state = notices.recipient(worker, r, false);
    assertThat(state).containsEntry("receipt_status", "PENDING");
    assertThat(
            db.count(
                "SELECT COUNT(*) FROM delivery_attempt WHERE recipient_id=:r AND channel='SMS' AND"
                    + " result='SENT'",
                Db.p("r", r)))
        .isEqualTo(1);
    assertThat(sms.settings(admin).toString()).doesNotContain("unit-key");
    assertThatThrownBy(() -> sms.settings(worker)).isInstanceOf(Problem.class);
  }

  @Test
  void missingConsentOrChangedFamilyContactNeverDispatches() {
    long e = elder(false), r = recipient(e);
    enableSms();
    assertThatThrownBy(() -> sms.enqueue(worker, r, "no-consent", 1)).isInstanceOf(Problem.class);
    var info =
        new ElderPersonalInfo.Info("宋江", "19900000001", "智园社区1号楼", "宋仁", "女儿", "18800000001", true);
    db.update(
        "UPDATE elder_profile SET personal_info_cipher=:c WHERE id=:id",
        Db.p("c", crypto.encrypt(json.write(info)), "id", e));
    long job =
        ((Number) ((Map<?, ?>) sms.enqueue(worker, r, "changed-contact", 1)).get("id")).longValue();
    var payload =
        json.map(
            db.one("SELECT payload_json FROM job_run WHERE id=:id", Db.p("id", job))
                .get("payload_json"));
    db.update(
        "UPDATE elder_profile SET personal_info_cipher=:c WHERE id=:id",
        Db.p(
            "c",
            crypto.encrypt(
                json.write(
                    new ElderPersonalInfo.Info(
                        "宋江", "19900000001", "智园社区1号楼", "宋仁", "女儿", "18800000002", true))),
            "id",
            e));
    assertThatThrownBy(() -> sms.dispatch(worker, job, payload)).isInstanceOf(Problem.class);
    verifyNoInteractions(gateway);
  }

  @Test
  void smsConfigurationEncryptsKeyAndPreservesItWhenNotReentered() {
    var initial = (Map<?, ?>) sms.settings(admin);
    int version = ((Number) initial.get("version")).intValue();
    sms.save(
        admin,
        new SmsService.Settings(
            version, false, "https://example.com/send", "private-unit-key", "社区", "template-1"));
    var config = sms.settings(admin);
    assertThat(config.toString()).contains("hasKey=true").doesNotContain("private-unit-key");
    var raw =
        db.one("SELECT sms_config_cipher FROM community_profile WHERE community_id=1", Map.of());
    assertThat(
            new String(
                (byte[]) raw.get("sms_config_cipher"), java.nio.charset.StandardCharsets.UTF_8))
        .doesNotContain("private-unit-key");
    sms.save(
        admin,
        new SmsService.Settings(
            version + 1, false, "https://example.com/send", null, "社区", "template-2"));
    assertThat(sms.settings(admin).toString()).contains("hasKey=true");
    assertThatThrownBy(
            () ->
                sms.save(
                    admin,
                    new SmsService.Settings(
                        version, false, "https://example.com/send", null, "社区", "template-3")))
        .isInstanceOf(Problem.class);
  }

  @Test
  void globalReportingFiltersAllowNoCommunitySelection() {
    var current = mock(Auth.class);
    when(current.current()).thenReturn(admin);
    var controller = new ReportController(reports, current, db);
    assertThat(controller.filters(null).toString()).contains("workers", "campaigns");
    when(current.current())
        .thenReturn(new Actor(3, 1, "stats", Set.of("STATISTICIAN"), admin.permissions()));
    assertThat(controller.filters(null).toString())
        .contains("workers", "campaigns")
        .doesNotContain("personal_info_cipher", "familyPhone");
  }

  @Test
  void reportsIncludeDateRangeStateCountsGapAnalysisAndBothFormats() throws Exception {
    long e = elder(false);
    recipient(e);
    elders.vaccine(
        worker,
        e,
        new ElderService.Vaccine(
            0,
            1L,
            "ELIGIBLE",
            "NOT_VACCINATED",
            LocalDate.now().plusDays(10),
            null,
            null,
            "已核验当前接种需求"));
    var query =
        new ReportService.Query(
            YearMonth.now().toString(),
            2L,
            null,
            null,
            1L,
            LocalDate.now().minusDays(2),
            LocalDate.now().plusDays(1));
    var data = reports.metrics(admin, query);
    assertThat(data.get("vaccineSummary").toString()).contains("not_vaccinated");
    assertThat(data.get("serviceGaps").toString()).contains("尚未完成接种", "登记状态");
    for (String format : List.of("XLSX", "DOCX")) {
      long report =
          ((Number)
                  ((Map<?, ?>)
                          reports.enqueue(
                              admin,
                              "workflow-report-" + format,
                              new ReportService.Export(query, format)))
                      .get("reportJobId"))
              .longValue();
      reports.export(admin, report);
      var file =
          db.one(
              "SELECT f.storage_key FROM file_object f JOIN report_job r ON r.output_file_id=f.id"
                  + " WHERE r.id=:id",
              Db.p("id", report));
      try (var in =
          Files.newInputStream(Path.of(storage).resolve(file.get("storage_key").toString()))) {
        if (format.equals("XLSX")) {
          try (var workbook = new XSSFWorkbook(in)) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetAt(1).getSheetName()).isEqualTo("服务缺口分析");
          }
        } else {
          try (var doc = new XWPFDocument(in)) {
            assertThat(doc.getParagraphs().toString()).isNotNull();
            assertThat(doc.getParagraphs().stream().map(p -> p.getText()).toList().toString())
                .contains("服务缺口分析");
          }
        }
      }
    }
  }
}
