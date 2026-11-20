package cn.community;

import static org.assertj.core.api.Assertions.*;

import cn.community.common.*;
import cn.community.elder.ElderPersonalInfo;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ElderPersonalInfoTest {
  final ElderPersonalInfo personal =
      new ElderPersonalInfo(
          new Crypto("11".repeat(32), "22".repeat(32)),
          new Json(JsonMapper.builder().build()),
          new Privacy());

  @Test
  void randomizedAuthenticatedStorageAndNormalizedExactSearch() {
    var info =
        new ElderPersonalInfo.Info(
            "宋江", "+86 199-0000-0001", "智园社区1号楼1单元101室", "宋某", "儿子", "18800000001");
    var one = personal.fields(info);
    byte[] cipher = (byte[]) one.get("personal_info_cipher");
    assertThat(cipher).isNotEqualTo(personal.fields(info).get("personal_info_cipher"));
    assertThat(new String(cipher, StandardCharsets.UTF_8))
        .doesNotContain("宋江", "19900000001", "智园社区", "18800000001");
    assertThat(personal.read(cipher))
        .containsEntry("name", "宋江")
        .containsEntry("phone", "19900000001")
        .containsEntry("address", info.address())
        .containsEntry("familyPhone", info.familyPhone());
    assertThat(one.get("phone_hash")).isEqualTo(personal.searchHash("phone", "19900000001"));
    assertThat(one.get("name_hash")).isEqualTo(personal.searchHash("name", " 宋江 "));
    cipher[cipher.length - 1] ^= 1;
    assertThatThrownBy(() -> personal.read(cipher)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void identityNumbersAreRejectedEvenInEncryptedFields() {
    for (String value :
        new String[] {
          "11010119490101001X", "１１０１０１１９４９０１０１００１Ｘ", "110101 19490101 001X", "110101490101001"
        }) {
      for (int field = 0; field < 6; field++) {
        String[] input = {"卢俊义", "19900000002", "智园社区2号楼", "卢某", "女儿", "18800000002"};
        input[field] = value;
        assertThatThrownBy(
                () ->
                    personal.fields(
                        new ElderPersonalInfo.Info(
                            input[0], input[1], input[2], input[3], input[4], input[5])))
            .isInstanceOf(Problem.class);
      }
    }
    assertThatThrownBy(() -> personal.phone("invalid")).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> personal.validate(new ElderPersonalInfo.Info(" ", "", "", "", "", "")))
        .isInstanceOf(Problem.class);
  }

  @Test
  void oldProfilesCanBeCompletedAndReferencesContainNoCiphertext() {
    assertThat(personal.read(null)).containsEntry("name", "").containsEntry("address", "");
    var fields = personal.fields(new ElderPersonalInfo.Info("吴用", "", "慧园社区3号楼", "吴某", "儿子", ""));
    var reference = personal.reference(fields);
    assertThat(reference)
        .containsEntry("elderName", "吴用")
        .doesNotContainKeys(
            "personal_info_cipher", "name_hash", "phone_hash", "address", "familyPhone");
  }
}
