package cn.community.common;

import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

/** SQL参数全部绑定；表名及列名只能由服务端常量提供。 */
@Component
public class Db {
  private final NamedParameterJdbcTemplate jdbc;

  public Db(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public static Map<String, Object> p(Object... pairs) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
    return map;
  }

  public List<Map<String, Object>> list(String sql, Map<String, ?> params) {
    return jdbc.queryForList(sql, params);
  }

  public Map<String, Object> one(String sql, Map<String, ?> params) {
    var rows = list(sql, params);
    if (rows.isEmpty()) throw Problem.missing();
    return rows.getFirst();
  }

  public Optional<Map<String, Object>> optional(String sql, Map<String, ?> params) {
    return list(sql, params).stream().findFirst();
  }

  public long count(String sql, Map<String, ?> params) {
    Long value = jdbc.queryForObject(sql, params, Long.class);
    return value == null ? 0 : value;
  }

  public int update(String sql, Map<String, ?> params) {
    return jdbc.update(sql, params);
  }

  public long insert(String table, Map<String, Object> fields) {
    String columns = String.join(",", fields.keySet().stream().map(k -> "`" + k + "`").toList());
    String values = String.join(",", fields.keySet().stream().map(k -> ":" + k).toList());
    var keys = new GeneratedKeyHolder();
    jdbc.update(
        "INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ")",
        new MapSqlParameterSource(fields),
        keys,
        new String[] {"id"});
    return Objects.requireNonNull(keys.getKey()).longValue();
  }

  public static long id(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  public Map<String, Object> page(
      String select, String countSql, Map<String, Object> params, int page, int size) {
    Problem.require(page >= 1 && size >= 1 && size <= 100, "page必须大于0，pageSize为1至100");
    params.put("limit", size);
    params.put("offset", (page - 1L) * size);
    return p(
        "items",
        list(select + " LIMIT :limit OFFSET :offset", params),
        "total",
        count(countSql, params),
        "page",
        page,
        "pageSize",
        size);
  }
}
