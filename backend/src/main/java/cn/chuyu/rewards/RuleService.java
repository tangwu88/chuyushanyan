package cn.chuyu.rewards;

import static cn.chuyu.shared.BusinessException.require;
import static cn.chuyu.shared.Json.*;
import static cn.chuyu.shared.Money.*;

import cn.chuyu.shared.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class RuleService {
  private final Store db;

  public RuleService(Store db) {
    this.db = db;
  }

  public Map<String, Object> settings() {
    return db.required("SELECT * FROM app_settings WHERE id=1", Map.of());
  }

  public String currentId() {
    return text(settings(), "currentRule");
  }

  public Rules current() {
    return get(currentId());
  }

  public Rules get(String id) {
    return read(
        text(
            db.required("SELECT * FROM rule_versions WHERE id=#{p.id}", map("id", id)),
            "configJson"),
        Rules.class);
  }

  public List<Map<String, Object>> list() {
    var rows = db.list("SELECT * FROM rule_versions ORDER BY created_at DESC,id");
    return rows.stream()
        .map(
            row -> {
              Map<String, Object> m = new LinkedHashMap<>(row);
              m.put(
                  "config",
                  text(row, "status").equals("REFERENCE")
                      ? read(text(row, "configJson"), Map.class)
                      : read(write(read(text(row, "configJson"), Rules.class)), Map.class));
              m.remove("configJson");
              return m;
            })
        .toList();
  }

  public Map<String, Object> draft(Map<String, Object> body) {
    Actors.requireGlobal("rules:write");
    String name = text(body, "name");
    require(!name.isBlank(), "请输入版本名称");
    Rules r = body.containsKey("config") ? read(write(body.get("config")), Rules.class) : current();
    r.validate();
    String id = "R" + id().substring(0, 10).toUpperCase(Locale.ROOT);
    db.exec(
        "INSERT INTO rule_versions(id,name,status,source,config_json,created_by)"
            + " VALUES(#{p.id},#{p.name},'DRAFT',#{p.source},#{p.config},#{p.actor})",
        map(
            "id",
            id,
            "name",
            name,
            "source",
            "演示规则编辑",
            "config",
            write(r),
            "actor",
            Actors.current().id()));
    db.audit("RULE_DRAFT", id, null, r, name);
    return map("id", id);
  }

  public Map<String, Object> save(String id, Map<String, Object> body) {
    Actors.requireGlobal("rules:write");
    var before = db.required("SELECT * FROM rule_versions WHERE id=#{p.id}", map("id", id));
    require(text(before, "status").equals("DRAFT"), "已发布和参考版本不能覆盖修改");
    Rules r = read(write(body.get("config")), Rules.class);
    r.validate();
    db.exec(
        "UPDATE rule_versions SET config_json=#{p.config},tested_hash=NULL WHERE id=#{p.id}",
        map("id", id, "config", write(r)));
    db.audit("RULE_EDIT", id, before, r, text(body, "reason"));
    return map("id", id);
  }

  public Map<String, Object> test(String id) {
    Actors.requireGlobal("rules:write");
    var row = db.required("SELECT * FROM rule_versions WHERE id=#{p.id}", map("id", id));
    require(text(row, "status").equals("DRAFT"), "只能对草稿进行发布试算");
    Rules r = get(id);
    r.validate();
    var results = ScenarioCatalog.run(r);
    for (var result : results)
      require(
          result.result().gross().subtract(result.result().fee()).compareTo(result.result().net())
              == 0,
          "试算金额不平");
    db.exec(
        "UPDATE rule_versions SET tested_hash=#{p.hash} WHERE id=#{p.id}",
        map("id", id, "hash", hash(write(r))));
    db.audit("RULE_TEST", id, null, results, "演示案例试算");
    return map("id", id, "scenarios", results, "configHash", hash(write(r)));
  }

  public Map<String, Object> publish(String id) {
    Actors.requireGlobal("rules:write");
    var row = db.required("SELECT * FROM rule_versions WHERE id=#{p.id}", map("id", id));
    Rules r = get(id);
    r.validate();
    require(
        db.count(
                "SELECT COUNT(*) FROM wallets w JOIN member_points p ON p.id=w.point_id WHERE"
                    + " w.point_id=#{p.id} AND w.kind='REWARD' AND p.active=TRUE AND"
                    + " p.activated=TRUE",
                map("id", r.recoveryPoint))
            == 1,
        "积分回收账户不存在或不可用");
    for (var v :
        db.list(
            "SELECT DISTINCT grade_version FROM member_points WHERE id NOT IN"
                + " ('SYSTEM','RECOVERY')"))
      require(r.gradeVersions.containsKey(text(v, "gradeVersion")), "不能移除仍有会员使用的等级版本");
    for (var member : db.list("SELECT identities FROM member_points WHERE identities<>''"))
      for (String code : text(member, "identities").split(","))
        require(code.isBlank() || r.identities.containsKey(code), "不能移除仍有会员使用的特殊身份，请先取消该身份或改为停用");
    require(
        text(row, "status").equals("DRAFT") && hash(write(r)).equals(text(row, "testedHash")),
        "规则修改后必须重新完成试算，才能发布");
    String previous = currentId();
    db.exec("UPDATE rule_versions SET status='ARCHIVED' WHERE status='ACTIVE'", Map.of());
    db.exec(
        "UPDATE rule_versions SET status='ACTIVE',effective_at=CURRENT_TIMESTAMP WHERE id=#{p.id}",
        map("id", id));
    db.exec(
        "UPDATE app_settings SET current_rule=#{p.id},revision=revision+1 WHERE id=1",
        map("id", id));
    db.audit("RULE_PUBLISH", id, previous, id, "试算通过后发布");
    return map("id", id, "status", "ACTIVE");
  }

  public Map<String, Object> compare(String left, String right) {
    Actors.require("rules:read");
    var a = db.required("SELECT * FROM rule_versions WHERE id=#{p.id}", map("id", left));
    var b = db.required("SELECT * FROM rule_versions WHERE id=#{p.id}", map("id", right));
    var changes = new ArrayList<Map<String, Object>>();
    diff(
        "",
        text(a, "status").equals("REFERENCE")
            ? read(text(a, "configJson"), Map.class)
            : comparisonValue(get(left)),
        text(b, "status").equals("REFERENCE")
            ? read(text(b, "configJson"), Map.class)
            : comparisonValue(get(right)),
        changes);
    List<Map<String, Object>> scenarios = new ArrayList<>();
    if (!text(a, "status").equals("REFERENCE") && !text(b, "status").equals("REFERENCE")) {
      var first = ScenarioCatalog.run(get(left));
      var second = ScenarioCatalog.run(get(right));
      for (int i = 0; i < first.size(); i++)
        scenarios.add(
            map(
                "title",
                first.get(i).title(),
                "before",
                first.get(i).result().net(),
                "after",
                second.get(i).result().net(),
                "difference",
                second.get(i).result().net().subtract(first.get(i).result().net())));
    }
    return map(
        "left", a.get("name"), "right", b.get("name"), "changes", changes, "scenarios", scenarios);
  }

  // Compare typed decimals by value. Text identifiers such as recovery accounts retain leading
  // zeros.
  private Object comparisonValue(Object value) {
    if (value instanceof java.math.BigDecimal n) return n.stripTrailingZeros();
    if (value instanceof Map<?, ?> values) {
      Map<String, Object> out = new LinkedHashMap<>();
      values.forEach((k, v) -> out.put(k.toString(), comparisonValue(v)));
      return out;
    }
    if (value instanceof Collection<?> values)
      return values.stream().map(this::comparisonValue).toList();
    try {
      if (value instanceof Rules r) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var field : Rules.class.getFields())
          if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()))
            out.put(field.getName(), comparisonValue(field.get(r)));
        return out;
      }
      if (value != null && value.getClass().isRecord()) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var field : value.getClass().getRecordComponents())
          out.put(field.getName(), comparisonValue(field.getAccessor().invoke(value)));
        return out;
      }
      return value;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("无法比较规则结构", e);
    }
  }

  private void diff(String path, Object before, Object after, List<Map<String, Object>> out) {
    if (before instanceof Map<?, ?> a && after instanceof Map<?, ?> b) {
      Set<String> keys = new TreeSet<>();
      a.keySet().forEach(k -> keys.add(k.toString()));
      b.keySet().forEach(k -> keys.add(k.toString()));
      for (String k : keys) diff(path.isBlank() ? k : path + "." + k, a.get(k), b.get(k), out);
    } else if (!Objects.equals(write(before), write(after)))
      out.add(map("field", path, "before", before, "after", after));
  }
}
