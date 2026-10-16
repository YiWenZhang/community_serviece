package cn.community.common;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.slf4j.MDC;

/** BIGINT以字符串返回，SQL列名转camelCase，二进制敏感列由用例层提前移除。 */
public final class Api {
  private Api() {}

  public static Map<String, Object> ok(Object data) {
    return Db.p("data", clean(data), "traceId", MDC.get("traceId"));
  }

  public static Object clean(Object value) {
    if (value instanceof Long || value instanceof java.math.BigInteger) return value.toString();
    if (value instanceof Timestamp t) return t.toInstant().toString();
    if (value instanceof LocalDateTime t) return t.toInstant(ZoneOffset.UTC).toString();
    if (value instanceof java.sql.Date d) return d.toLocalDate().toString();
    if (value instanceof Map<?, ?> m) {
      var result = new LinkedHashMap<String, Object>();
      m.forEach(
          (k, v) ->
              result.put(
                  camel(k.toString()),
                  "version".equals(k.toString()) && v instanceof Number n
                      ? n.intValue()
                      : clean(v)));
      return result;
    }
    if (value instanceof Collection<?> c) return c.stream().map(Api::clean).toList();
    return value;
  }

  private static String camel(String value) {
    StringBuilder b = new StringBuilder();
    boolean upper = false;
    for (char c : value.toCharArray()) {
      if (c == '_') upper = true;
      else {
        b.append(upper ? Character.toUpperCase(c) : c);
        upper = false;
      }
    }
    return b.toString();
  }
}
