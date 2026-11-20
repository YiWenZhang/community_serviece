package cn.community;

import static org.assertj.core.api.Assertions.*;

import cn.community.common.*;
import cn.community.config.SecureTransportFilter;
import cn.community.identity.Passwords;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class PasswordSecurityTest {
  @Test
  void sixCharactersAllowedButBcryptByteLimitEnforced() {
    assertThatCode(() -> Passwords.validate("123456")).doesNotThrowAnyException();
    assertThatCode(() -> Passwords.validate("老".repeat(24))).doesNotThrowAnyException();
    assertThatThrownBy(() -> Passwords.validate("老".repeat(25))).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> Passwords.validate("12345")).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> Passwords.validate("hello\nworld")).isInstanceOf(Problem.class);
    assertThat(Passwords.strength("123456")).isEqualTo("WEAK");
    assertThat(Passwords.strength("abcdefgh1")).isEqualTo("MEDIUM");
    assertThat(Passwords.strength("Example!123")).isEqualTo("STRONG");
  }

  @Test
  void privacyRejectsSpacedFullWidthAndInvisibleSensitiveText() {
    var p = new Privacy();
    for (String text :
        new String[] {
          "１３８００１３８０００",
          "138 0013 8000",
          "138\u200b00138000",
          "11010119490101001X",
          "110101491231001",
          "北京 市 朝阳 路 18 号"
        }) assertThatThrownBy(() -> p.check(text)).isInstanceOf(Problem.class);
    assertThat(p.check("张某 女儿，同意参加")).isNotBlank();
  }

  @Test
  void forwardedHeaderCannotBypassHttpsEnforcement() throws Exception {
    var filter = new SecureTransportFilter();
    var request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
    request.addHeader("X-Forwarded-Proto", "https");
    var response = new MockHttpServletResponse();
    filter.doFilter(
        request,
        response,
        (req, res) -> {
          throw new AssertionError("HTTP must stop");
        });
    assertThat(response.getStatus()).isEqualTo(426);
    request.setSecure(true);
    response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> res.getWriter().write("passed"));
    assertThat(response.getContentAsString()).isEqualTo("passed");
  }
}
