package cn.community.common;

import java.text.Normalizer;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 文本入口做最少化拦截；附件仍需人工确认脱敏，不宣称自动识别所有个人信息。 */
@Component
public class Privacy {
  private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
  private static final Pattern ID = Pattern.compile("(?i)(?<!\\d)\\d{17}[0-9x](?!\\d)");
  private static final Pattern OLD_ID =
      Pattern.compile("(?<!\\d)\\d{6}\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}(?!\\d)");
  private static final Pattern ADDRESS =
      Pattern.compile("(?:省|市|区|县).{0,20}(?:路|街|巷).{0,15}号|(?:路|街|巷)\\s*\\d+号");

  public String check(String text) {
    if (text != null) {
      String normalized =
          Normalizer.normalize(text, Normalizer.Form.NFKC)
              .replaceAll("[\\p{Cf}]", "")
              .replaceAll("(?<=\\d)[\\s\\p{Z}—-]+(?=\\d)", "");
      Problem.require(
          !PHONE.matcher(normalized).find()
              && !ID.matcher(normalized).find()
              && !OLD_ID.matcher(normalized).find()
              && !ADDRESS.matcher(normalized.replaceAll("[\\s\\p{Z}]", "")).find(),
          "身份证号禁止录入；姓名、电话和住址请填写在老人档案的专用加密字段，勿放入备注或政策问答");
    }
    return text;
  }

  public String checkIdentity(String text) {
    if (text != null) {
      String normalized =
          Normalizer.normalize(text, Normalizer.Form.NFKC)
              .replaceAll("[\\p{Cf}]", "")
              .replaceAll("(?<=\\d)[\\s\\p{Z}—-]+(?=\\d)", "");
      Problem.require(
          !ID.matcher(normalized).find() && !OLD_ID.matcher(normalized).find(), "禁止录入或保存身份证号");
    }
    return text;
  }
}
