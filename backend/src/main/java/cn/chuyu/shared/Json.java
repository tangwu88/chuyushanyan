package cn.chuyu.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class Json {
  public static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  private Json() {}

  public static String write(Object o) {
    try {
      return MAPPER.writeValueAsString(o);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static <T> T read(String s, Class<T> type) {
    try {
      return MAPPER.readValue(s, type);
    } catch (Exception e) {
      throw new BusinessException("数据格式错误：" + e.getMessage());
    }
  }

  public static String hash(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String id() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  public static Map<String, Object> map(Object... pairs) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i].toString(), pairs[i + 1]);
    return m;
  }

  public static Object api(Object o) {
    if (o instanceof BigDecimal n) return n.toPlainString();
    if (o instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      m.forEach((k, v) -> out.put(k.toString(), api(v)));
      return out;
    }
    if (o instanceof Collection<?> c) return c.stream().map(Json::api).toList();
    if (o != null && o.getClass().isRecord()) return api(MAPPER.convertValue(o, Map.class));
    return o;
  }

  public static String text(Map<String, ?> m, String key) {
    Object v = m.get(key);
    return v == null ? "" : v.toString().trim();
  }

  public static boolean bool(Object o) {
    return o != null
        && (o.equals(true) || o.toString().equals("1") || o.toString().equalsIgnoreCase("true"));
  }

  public static int integer(Object o) {
    try {
      return new BigDecimal(o.toString()).intValueExact();
    } catch (Exception e) {
      throw new BusinessException("请输入正确整数");
    }
  }
}
