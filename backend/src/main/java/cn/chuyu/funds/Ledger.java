package cn.chuyu.funds;

import static cn.chuyu.shared.BusinessException.require;
import static cn.chuyu.shared.Json.*;
import static cn.chuyu.shared.Money.*;

import cn.chuyu.shared.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class Ledger {
  public record Change(
      String point,
      String kind,
      BigDecimal delta,
      BigDecimal frozen,
      BigDecimal restricted,
      String description,
      boolean allowNegative) {
    public static Change money(
        String point, String kind, BigDecimal delta, String why, boolean negative) {
      return new Change(point, kind, delta, ZERO, ZERO, why, negative);
    }
  }

  private final Store db;

  public Ledger(Store db) {
    this.db = db;
  }

  public Map<String, Object> wallet(String point, String kind) {
    return db.required(
        "SELECT * FROM wallets WHERE point_id=#{p.point} AND kind=#{p.kind}",
        map("point", point, "kind", kind));
  }

  public String post(
      String key, String type, String origin, String description, List<Change> input) {
    var existing =
        db.one("SELECT * FROM ledger_transactions WHERE business_key=#{p.key}", map("key", key));
    if (existing != null) return text(existing, "id");
    List<Change> changes = new ArrayList<>(input);
    for (String kind : List.of("REWARD", "RECHARGE")) {
      BigDecimal sum =
          input.stream()
              .filter(c -> c.kind().equals(kind))
              .map(Change::delta)
              .reduce(ZERO, BigDecimal::add);
      if (sum.signum() != 0)
        changes.add(Change.money("SYSTEM", kind, sum.negate(), "外部资金及积分发行/销毁对照", true));
    }
    for (var keyPart :
        changes.stream().map(c -> c.point() + ":" + c.kind()).distinct().sorted().toList()) {
      String[] p = keyPart.split(":");
      db.required(
          "SELECT * FROM wallets WHERE point_id=#{p.point} AND kind=#{p.kind} FOR UPDATE",
          map("point", p[0], "kind", p[1]));
    }
    String tx = id();
    db.exec(
        "INSERT INTO ledger_transactions(id,business_key,kind,actor_id,origin_id,description)"
            + " VALUES(#{p.id},#{p.key},#{p.type},#{p.actor},#{p.origin},#{p.description})",
        map(
            "id",
            tx,
            "key",
            key,
            "type",
            type,
            "actor",
            Actors.current().id(),
            "origin",
            origin,
            "description",
            description));
    for (Change c : changes) {
      require(Set.of("REWARD", "RECHARGE").contains(c.kind()), "账户类型错误");
      var w = wallet(c.point(), c.kind());
      BigDecimal balance = money(d(w.get("balance")).add(c.delta())),
          frozen = money(d(w.get("frozen")).add(c.frozen())),
          restricted = money(d(w.get("restricted")).add(c.restricted()));
      require(frozen.signum() >= 0 && restricted.signum() >= 0, "冻结或受限金额不能为负");
      require(
          c.allowNegative()
              || (c.delta().signum() >= 0 && c.frozen().signum() <= 0)
              || balance.subtract(frozen).signum() >= 0,
          "可用积分不足");
      require(
          c.point().equals("SYSTEM")
              || !c.kind().equals("RECHARGE")
              || restricted.compareTo(balance) <= 0,
          "受限充值积分不能超过余额");
      var p =
          map(
              "point",
              c.point(),
              "kind",
              c.kind(),
              "balance",
              balance,
              "frozen",
              frozen,
              "restricted",
              restricted);
      db.exec(
          "UPDATE wallets SET"
              + " balance=#{p.balance},frozen=#{p.frozen},restricted=#{p.restricted},revision=revision+1"
              + " WHERE point_id=#{p.point} AND kind=#{p.kind}",
          p);
      db.exec(
          "INSERT INTO"
              + " ledger_entries(id,transaction_id,point_id,wallet_kind,delta,balance_after,frozen_after,description)"
              + " VALUES(#{p.id},#{p.tx},#{p.point},#{p.kind},#{p.delta},#{p.balance},#{p.frozen},#{p.why})",
          map(
              "id",
              id(),
              "tx",
              tx,
              "point",
              c.point(),
              "kind",
              c.kind(),
              "delta",
              c.delta(),
              "balance",
              balance,
              "frozen",
              frozen,
              "why",
              c.description()));
    }
    return tx;
  }
}
