package cn.chuyu.shared;

import static cn.chuyu.shared.Json.*;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Commands {
  private final Store store;

  public Commands(Store store) {
    this.store = store;
  }

  @Transactional
  @SuppressWarnings("unchecked")
  public Map<String, Object> run(
      String key,
      String operation,
      Map<String, Object> payload,
      Supplier<Map<String, Object>> work) {
    BusinessException.require(key != null && key.length() >= 8 && key.length() <= 100, "缺少有效的请求编号");
    // A database lock, not an in-process lock: serializes demo business mutations across instances.
    // Domain services retain independent wallet/order validation; replace this gate with scoped
    // locks for scale.
    store.required("SELECT * FROM app_settings WHERE id=1 FOR UPDATE", Map.of());
    String business = Actors.current().id() + ":" + operation + ":" + key;
    String fingerprint = hash(write(new TreeMap<>(payload)));
    var previous =
        store.one(
            "SELECT * FROM command_results WHERE business_key=#{p.key}", map("key", business));
    if (previous != null) {
      if (!fingerprint.equals(previous.get("requestHash")))
        throw new BusinessException(409, "请求编号已用于其他内容，请刷新后重试");
      return read(previous.get("responseJson").toString(), Map.class);
    }
    Map<String, Object> result = work.get();
    store.exec(
        "INSERT INTO command_results(business_key,request_hash,response_json)"
            + " VALUES(#{p.key},#{p.hash},#{p.response})",
        map("key", business, "hash", fingerprint, "response", write(api(result))));
    return result;
  }
}
