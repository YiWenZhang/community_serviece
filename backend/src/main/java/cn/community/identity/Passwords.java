package cn.community.identity;

import cn.community.common.Problem;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 密码长度按BCrypt的UTF-8字节上限校验；强度提示不强制12位或复杂字符。 */
public final class Passwords {
  public static final String INITIAL = "123456";

  private Passwords() {}

  public static void validate(String value) {
    Problem.require(value != null && value.codePointCount(0, value.length()) >= 6, "新密码至少6个字符");
    Problem.require(
        value.getBytes(StandardCharsets.UTF_8).length <= 72, "密码最多72个UTF-8字节，中文和表情会占多个字节");
    Problem.require(value.codePoints().noneMatch(Character::isISOControl), "密码不能包含换行等控制字符");
  }

  public static String strength(String value) {
    if (value == null || value.length() < 6) return "TOO_SHORT";
    String lower = value.toLowerCase(Locale.ROOT);
    if (Set.of(
                "123456",
                "12345678",
                "123456789",
                "654321",
                "password",
                "qwerty",
                "111111",
                "000000")
            .contains(lower)
        || value.matches("(.)\\1{5,}")
        || value.matches("\\d+")) return "WEAK";
    int categories = 0;
    for (String p : List.of(".*[a-z].*", ".*[A-Z].*", ".*[0-9].*", ".*[^a-zA-Z0-9].*"))
      if (value.matches(p)) categories++;
    if (value.codePointCount(0, value.length()) >= 10 && categories >= 3) return "STRONG";
    if (value.codePointCount(0, value.length()) >= 8 && categories >= 2) return "MEDIUM";
    return "WEAK";
  }
}
