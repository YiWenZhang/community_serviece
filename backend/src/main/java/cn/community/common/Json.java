package cn.community.common;

import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class Json {
  private final ObjectMapper mapper;

  public Json(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public String write(Object object) {
    return mapper.writeValueAsString(object);
  }

  public boolean equal(Object left, Object right) {
    return mapper.readTree(write(left)).equals(mapper.readTree(write(right)));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> map(Object value) {
    if (value instanceof Map<?, ?> m) return (Map<String, Object>) m;
    return mapper.readValue(value.toString(), Map.class);
  }
}
