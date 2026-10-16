package cn.community;

import static org.assertj.core.api.Assertions.*;

import cn.community.common.*;
import org.junit.jupiter.api.Test;

class CryptoTest {
  private final Crypto crypto = new Crypto("11".repeat(32), "22".repeat(32));

  @Test
  void randomAuthenticatedCipherAndStableDigest() {
    byte[] one = crypto.encrypt("SH-L-2026-001"), two = crypto.encrypt("SH-L-2026-001");
    assertThat(one).isNotEqualTo(two);
    assertThat(crypto.decrypt(one)).isEqualTo("SH-L-2026-001");
    assertThat(crypto.hash("sh-l-2026-001")).isEqualTo(crypto.hash("SH-L-2026-001"));
    one[15] ^= 1;
    assertThatThrownBy(() -> crypto.decrypt(one)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void privacyRejectsPhoneAndIdentity() {
    var privacy = new Privacy();
    assertThatThrownBy(() -> privacy.check("联系13800138000")).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> privacy.check("证件11010119490101001X")).isInstanceOf(Problem.class);
    assertThat(privacy.check("张某 女儿，同意参加")).isEqualTo("张某 女儿，同意参加");
  }

  @Test
  void longsAreStringsAndColumnsCamelCase() {
    var data = Api.clean(Db.p("owner_user_id", 9007199254740993L, "version", 1L));
    assertThat(data.toString()).contains("ownerUserId=9007199254740993");
    assertThat(((java.util.Map<?, ?>) data).get("version")).isEqualTo(1);
  }
}
