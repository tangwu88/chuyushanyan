package cn.chuyu.rewards;

import static cn.chuyu.shared.BusinessException.require;
import static cn.chuyu.shared.Json.*;

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
              m.put("config", read(text(row, "configJson"), Map.class));
              m.remove("configJson");
              return m;
            })
        .toList();
  }

  public Map<String, Object> draft(Map<String, Object> body) {
    Actors.require("rules:write");
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
    Actors.require("rules:write");
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
    Actors.require("rules:write");
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
    Actors.require("rules:write");
    var row = db.required("SELECT * FROM rule_versions WHERE id=#{p.id}", map("id", id));
    Rules r = get(id);
    r.validate();
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
}
