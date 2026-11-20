package cn.community.elder;

import cn.community.common.*;
import jakarta.validation.constraints.*;
import java.text.Normalizer;
import java.util.*;
import org.springframework.stereotype.Component;

/** Business identity and contacts are encrypted together; no identity-card field exists. */
@Component
public class ElderPersonalInfo {
  public record Info(
      @NotBlank @Size(max = 40) String name,
      @Size(max = 24) String phone,
      @Size(max = 200) String address,
      @Size(max = 40) String familyName,
      @Size(max = 24) String familyRelation,
      @Size(max = 24) String familyPhone,
      Boolean familySmsAllowed) {
    public Info(
        String name,
        String phone,
        String address,
        String familyName,
        String familyRelation,
        String familyPhone) {
      this(name, phone, address, familyName, familyRelation, familyPhone, false);
    }
  }

  private final Crypto crypto;
  private final Json json;
  private final Privacy privacy;

  public ElderPersonalInfo(Crypto crypto, Json json, Privacy privacy) {
    this.crypto = crypto;
    this.json = json;
    this.privacy = privacy;
  }

  public Info validate(Info input) {
    if (input == null) return null; // Old records remain empty until explicitly completed.
    String name = clean(input.name(), 40),
        address = clean(input.address(), 200),
        family = clean(input.familyName(), 40),
        relation = clean(input.familyRelation(), 24);
    Problem.require(!name.isBlank(), "请填写老人姓名");
    return new Info(
        name,
        phone(input.phone()),
        address,
        family,
        relation,
        phone(input.familyPhone()),
        Boolean.TRUE.equals(input.familySmsAllowed()));
  }

  private String clean(String value, int max) {
    String text = Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC).strip();
    Problem.require(
        text.length() <= max && text.codePoints().noneMatch(Character::isISOControl),
        "姓名或联系方式字段格式不正确");
    privacy.checkIdentity(text);
    return text;
  }

  public String phone(String value) {
    String text = clean(value, 24).replaceAll("[\\s-]", "");
    if (text.startsWith("+86")) text = text.substring(3);
    Problem.require(
        text.isEmpty() || text.matches("1[3-9][0-9]{9}|0[0-9]{2,3}[0-9]{7,8}"),
        "请填写有效的大陆手机号或区号加固话号码");
    return text;
  }

  public Map<String, Object> fields(Info input) {
    Info info = validate(input);
    return Db.p(
        "personal_info_cipher",
        info == null ? null : crypto.encrypt(json.write(info)),
        "name_hash",
        info == null ? null : crypto.hash("elder.name:" + info.name()),
        "phone_hash",
        info == null || info.phone().isBlank() ? null : crypto.hash("elder.phone:" + info.phone()));
  }

  public Map<String, Object> read(Object cipher) {
    var result =
        cipher instanceof byte[] bytes
            ? json.map(crypto.decrypt(bytes))
            : Db.p(
                "name",
                "",
                "phone",
                "",
                "address",
                "",
                "familyName",
                "",
                "familyRelation",
                "",
                "familyPhone",
                "");
    result.putIfAbsent("familySmsAllowed", false);
    return result;
  }

  public byte[] searchHash(String field, String value) {
    return crypto.hash(
        "elder." + field + ":" + (field.equals("phone") ? phone(value) : clean(value, 40)));
  }

  /** Called only after the business query has restricted role, community and current owner. */
  public Map<String, Object> reference(Map<String, Object> row) {
    var copy = new LinkedHashMap<>(row);
    var privateData = read(copy.remove("personal_info_cipher"));
    copy.put("elderName", privateData.get("name"));
    if (copy.remove("elder_no_cipher") instanceof byte[] number)
      copy.put("elderNo", crypto.decrypt(number));
    copy.remove("name_hash");
    copy.remove("phone_hash");
    return copy;
  }
}
