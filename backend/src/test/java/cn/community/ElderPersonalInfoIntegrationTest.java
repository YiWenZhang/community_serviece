package cn.community;

import static org.assertj.core.api.Assertions.*;

import cn.community.common.*;
import cn.community.elder.*;
import cn.community.identity.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
class ElderPersonalInfoIntegrationTest {
  @Autowired Db db;
  @Autowired Auth auth;
  @Autowired ElderService elders;

  ElderService.Draft draft(String name, String phone) {
    return new ElderService.Draft(
        1L,
        "MALE",
        "1950-05",
        "常态联系",
        Set.of(1L),
        2L,
        new ElderPersonalInfo.Info(name, phone, "智园社区1号楼1单元101室", "宋某", "儿子", "18800000001"));
  }

  @Test
  void privateProfileIsEncryptedSearchableAndScoped() {
    var admin = auth.worker(1, 1);
    var worker = auth.worker(2, 1);
    long id =
        ((Number) ((Map<?, ?>) elders.create(worker, draft("宋江", "19900000001"))).get("id"))
            .longValue();
    var raw = db.one("SELECT * FROM elder_profile WHERE id=:id", Db.p("id", id));
    assertThat(raw.get("personal_info_cipher")).isInstanceOf(byte[].class);
    assertThat((byte[]) raw.get("name_hash")).hasSize(32);
    assertThat((byte[]) raw.get("phone_hash")).hasSize(32);
    assertThat(raw).doesNotContainKeys("name", "phone", "address", "familyPhone", "id_card");
    assertThat(elders.detail(worker, id))
        .containsEntry("name", "宋江")
        .containsEntry("familyPhone", "18800000001")
        .doesNotContainKeys("personal_info_cipher", "name_hash", "phone_hash", "elder_no_cipher");
    assertThat(
            ((Map<?, ?>)
                    elders.list(worker, 1, 20, null, null, null, null, "宋江", "+86 199-0000-0001"))
                .get("total")
                .toString())
        .isEqualTo("1");
    var stranger = new Actor(999999, 1, "stranger", Set.of("GRID_WORKER"), worker.permissions());
    assertThatThrownBy(() -> elders.detail(stranger, id))
        .isInstanceOfSatisfying(Problem.class, e -> assertThat(e.status).isEqualTo(404));
    var stats = new Actor(3, 1, "stats", Set.of("STATISTICIAN"), admin.permissions());
    assertThatThrownBy(() -> elders.detail(stats, id))
        .isInstanceOfSatisfying(Problem.class, e -> assertThat(e.status).isEqualTo(403));
    elders.update(worker, id, new ElderService.Edit(0, draft("卢俊义", "19900000002"), "ACTIVE"));
    assertThat(elders.detail(worker, id))
        .containsEntry("name", "卢俊义")
        .containsEntry("phone", "19900000002");
    assertThatThrownBy(
            () ->
                elders.update(
                    worker, id, new ElderService.Edit(0, draft("吴用", "19900000003"), "ACTIVE")))
        .isInstanceOfSatisfying(Problem.class, e -> assertThat(e.status).isEqualTo(409));
    assertThat(elders.detail(worker, id)).containsEntry("name", "卢俊义");
    assertThat(
            db.list(
                    "SELECT change_summary_json FROM audit_log WHERE target_type='ELDER' AND"
                        + " target_id=:id",
                    Db.p("id", id))
                .toString())
        .doesNotContain("宋江", "卢俊义", "199000000", "188000000", "智园社区");
  }
}
