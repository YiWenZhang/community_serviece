package cn.community;

import static org.assertj.core.api.Assertions.*;

import cn.community.ai.PolicyParser;
import cn.community.common.*;
import cn.community.elder.ElderPersonalInfo;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class InitialDatasetTest {
  private final Path root = Path.of("..").toAbsolutePath().normalize();
  private final Json json = new Json(JsonMapper.builder().build());

  @Test
  void packageContainsCompleteScopedProfilesAndVerifiablePolicies() throws Exception {
    var data = json.map(Files.readString(root.resolve("database/initial-data/communities.json")));
    var communities = (List<?>) data.get("communities");
    var users = (List<?>) data.get("users");
    var elders = (List<?>) data.get("elders");
    assertThat(communities).hasSize(4);
    assertThat(users).hasSize(7);
    assertThat(elders).hasSize(20);
    var names = new HashSet<String>();
    var personal =
        new ElderPersonalInfo(new Crypto("11".repeat(32), "22".repeat(32)), json, new Privacy());
    for (Object raw : elders) {
      var e = json.map(raw);
      var info = json.map(e.get("personalInfo"));
      names.add(info.get("name").toString());
      personal.validate(
          new ElderPersonalInfo.Info(
              info.get("name").toString(),
              info.get("phone").toString(),
              info.get("address").toString(),
              info.get("familyName").toString(),
              info.get("familyRelation").toString(),
              info.get("familyPhone").toString()));
      assertThat(info.get("address").toString()).contains("社区", "号楼", "室");
      new Privacy().check(e.get("remark").toString());
      assertThat(
              users.stream()
                  .map(json::map)
                  .filter(
                      u ->
                          u.get("id").equals(e.get("owner")) && "GRID_WORKER".equals(u.get("role")))
                  .anyMatch(u -> ((List<?>) u.get("communities")).contains(e.get("community"))))
          .isTrue();
    }
    assertThat(names).hasSize(20).contains("宋江", "卢俊义", "孙二娘", "扈三娘", "顾大嫂");
    var crypto = new Crypto("11".repeat(32), "22".repeat(32));
    int shared = 0, local = 0;
    for (Object raw : (List<?>) data.get("policies")) {
      var p = json.map(raw);
      String text = Files.readString(root.resolve(p.get("path").toString()));
      assertThat(text)
          .as("Policy checksums must survive Git checkout on all platforms")
          .doesNotContain("\r");
      assertThat(crypto.digest(text)).isEqualTo(p.get("sha256"));
      new Privacy().check(text);
      assertThat(new PolicyParser().split(text)).isNotEmpty();
      if (p.get("community") == null) shared++;
      else local++;
      if ("PUBLIC".equals(p.get("nature")))
        assertThat(p.get("sourceUrl").toString()).startsWith("https://").contains(".gov.cn/");
      if (p.get("title").equals("中华人民共和国疫苗管理法")) assertThat(text).contains("第一百条", "2019年12月1日");
    }
    assertThat(shared).isEqualTo(7);
    assertThat(local).isEqualTo(4);
  }
}
