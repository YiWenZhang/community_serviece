package cn.community.common;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 文本入口做最少化拦截；附件仍需人工确认脱敏，不宣称自动识别所有个人信息。 */
@Component
public class Privacy {
  private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
  private static final Pattern ID = Pattern.compile("(?i)(?<!\\d)\\d{17}[0-9x](?!\\d)");
  private static final Pattern ADDRESS =
      Pattern.compile("(?:省|市|区|县).{0,20}(?:路|街|巷).{0,15}号|(?:路|街|巷)\\s*\\d+号");

  public String check(String text) {
    if (text != null)
      Problem.require(
          !PHONE.matcher(text).find() && !ID.matcher(text).find() && !ADDRESS.matcher(text).find(),
          "不得录入手机号、身份证号或详细地址，请先脱敏");
    return text;
  }
}
