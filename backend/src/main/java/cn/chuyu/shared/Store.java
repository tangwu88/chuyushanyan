package cn.chuyu.shared;

import static cn.chuyu.shared.Json.*;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Store {
  private final SqlMapper mapper;

  public Store(SqlMapper mapper) {
    this.mapper = mapper;
  }

  // SQL comes only from service constants. User values always use bound parameters.
  public List<Map<String, Object>> list(String sql, Map<String, Object> p) {
    return mapper.query(sql, p).stream()
        .filter(Objects::nonNull)
        .map(
            row -> {
              Map<String, Object> out = new LinkedHashMap<>();
              row.forEach(
                  (k, v) -> {
                    StringBuilder key = new StringBuilder();
                    boolean cap = false;
                    for (char c : k.toLowerCase(Locale.ROOT).toCharArray()) {
                      if (c == '_') cap = true;
                      else {
                        key.append(cap ? Character.toUpperCase(c) : c);
                        cap = false;
                      }
                    }
                    out.put(
                        key.toString(),
                        v instanceof Timestamp t ? t.toLocalDateTime().toString() : v);
                  });
              return out;
            })
        .toList();
  }

  public List<Map<String, Object>> list(String sql) {
    return list(sql, Map.of());
  }

  public Map<String, Object> one(String sql, Map<String, Object> p) {
    var rows = list(sql, p);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Map<String, Object> required(String sql, Map<String, Object> p) {
    var row = one(sql, p);
    if (row == null) throw new BusinessException(404, "记录不存在");
    return row;
  }

  public int exec(String sql, Map<String, Object> p) {
    return mapper.execute(sql, p);
  }

  public long count(String sql, Map<String, Object> p) {
    return ((Number) required(sql, p).values().iterator().next()).longValue();
  }

  public void audit(String action, String target, Object before, Object after, String reason) {
    exec(
        "INSERT INTO audit_logs(id,actor_id,action,target_id,before_json,after_json,reason)"
            + " VALUES(#{p.id},#{p.actor},#{p.action},#{p.target},#{p.before},#{p.after},#{p.reason})",
        map(
            "id",
            id(),
            "actor",
            Actors.current().id(),
            "action",
            action,
            "target",
            target,
            "before",
            write(before),
            "after",
            write(after),
            "reason",
            reason));
  }

  public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  static {
    TimeZone.setDefault(TimeZone.getTimeZone(ZONE));
  }

  public void recordPoint(String point, LocalDateTime at) {
    var row = required("SELECT * FROM member_points WHERE id=#{p.id}", map("id", point));
    exec(
        "INSERT INTO member_history(point_id,valid_at,snapshot_json)"
            + " VALUES(#{p.point},#{p.at},#{p.json})",
        map("point", point, "at", Timestamp.valueOf(at), "json", write(row)));
  }

  public static LocalDate today() {
    return LocalDate.now(ZONE);
  }

  public static Timestamp now() {
    return Timestamp.valueOf(LocalDateTime.now(ZONE));
  }
}
