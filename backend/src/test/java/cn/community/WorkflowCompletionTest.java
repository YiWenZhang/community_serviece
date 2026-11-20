package cn.community;

import static org.assertj.core.api.Assertions.*;

import cn.community.common.Problem;
import cn.community.notice.SmsGateway;
import cn.community.reporting.ReportService;
import java.time.*;
import org.junit.jupiter.api.Test;

class WorkflowCompletionTest {
  @Test
  void reportingRangeIsInclusiveAndRequiresBothBoundaries() {
    assertThat(ReportService.period(new ReportService.Query("2026-02", null, null, null)))
        .containsExactly(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1));
    assertThat(
            ReportService.period(
                new ReportService.Query(
                    "2026-02",
                    null,
                    null,
                    null,
                    null,
                    LocalDate.of(2026, 1, 20),
                    LocalDate.of(2026, 2, 21))))
        .containsExactly(LocalDate.of(2026, 1, 20), LocalDate.of(2026, 2, 22));
    assertThatThrownBy(
            () ->
                ReportService.period(
                    new ReportService.Query(
                        "2026-02",
                        null,
                        null,
                        null,
                        null,
                        LocalDate.of(2026, 3, 1),
                        LocalDate.of(2026, 2, 1))))
        .isInstanceOf(Problem.class);
    assertThatThrownBy(
            () ->
                ReportService.period(
                    new ReportService.Query(
                        "2026-02", null, null, null, null, LocalDate.of(2026, 3, 1), null)))
        .isInstanceOf(Problem.class);
  }

  @Test
  void smsGatewayRejectsInsecureAndInternalDestinations() {
    for (String url :
        new String[] {
          "http://example.com/send",
          "https://127.0.0.1/send",
          "https://[::1]/send",
          "https://169.254.169.254/",
          "https://10.0.0.1/send",
          "https://user:pass@example.com/send",
          "https://example.com/send?key=value"
        }) assertThatThrownBy(() -> SmsGateway.endpoint(url)).as(url).isInstanceOf(Problem.class);
  }

  @Test
  void smsAcceptanceRequiresExplicitPlatformSuccess() {
    var gateway =
        new SmsGateway(
            new cn.community.common.Json(tools.jackson.databind.json.JsonMapper.builder().build()));
    assertThat(gateway.accepted(200, "{\"accepted\":true,\"messageId\":\"provider-001\"}"))
        .isEqualTo("provider-001");
    for (String body :
        new String[] {
          "{\"accepted\":false,\"messageId\":\"id\"}", "{\"accepted\":true}", "<html>error</html>"
        }) assertThatThrownBy(() -> gateway.accepted(200, body)).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> gateway.accepted(500, "{}")).isInstanceOf(Problem.class);
  }
}
