package cn.chuyu.shared;

import static cn.chuyu.shared.Json.*;
import static cn.chuyu.shared.Money.*;

import cn.chuyu.funds.*;
import cn.chuyu.orders.OrderService;
import cn.chuyu.rewards.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "chuyu.demo", havingValue = "true")
public class DemoSeed implements ApplicationRunner {
  private final Store db;
  private final BCryptPasswordEncoder encoder;
  private final Ledger ledger;
  private final OrderService orders;

  public DemoSeed(Store db, BCryptPasswordEncoder encoder, Ledger ledger, OrderService orders) {
    this.db = db;
    this.encoder = encoder;
    this.ledger = ledger;
    this.orders = orders;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    var actor = new Actors.Actor("U_ADMIN", "admin", "演示管理员", "ADMIN", "", false);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
    try {
      if (db.count("SELECT COUNT(*) n FROM app_settings", Map.of()) > 0) {
        db.exec(
            "UPDATE products SET name='初语山言 · 会员专供' WHERE id='PR001' AND name='初语山言 · 日常养护精华'",
            Map.of());
        for (var o :
            db.list(
                "SELECT * FROM shop_orders WHERE payment_status='PAID' AND"
                    + " activation_status='PENDING' AND cancelled=FALSE AND reserved_amount=0")) {
          ledger.post(
              "RESERVATION:" + text(o, "id"),
              "ORDER_RESERVATION",
              text(o, "id"),
              "补齐独立演示订单预留",
              List.of(
                  new Ledger.Change(
                      text(o, "shopId"),
                      "RECHARGE",
                      ZERO,
                      d(o.get("total")),
                      ZERO,
                      "待激活订单资金预留",
                      false)));
          db.exec(
              "UPDATE shop_orders SET reserved_amount=total WHERE id=#{p.id}",
              map("id", o.get("id")));
        }
        return;
      }
      Rules rules = new Rules();
      rules.validate();
      db.exec(
          "INSERT INTO rule_versions(id,name,status,source,config_json,created_by,effective_at)"
              + " VALUES('DEMO-V2','V2 已确认演示规则','ACTIVE','需求表 +"
              + " 用户确认',#{p.json},'U_ADMIN',CURRENT_TIMESTAMP)",
          map("json", write(rules)));
      var reference =
          map(
              "label",
              "现网只读观察，未经算法验证",
              "repeatBandCount",
              14,
              "repeatRates",
              "2%–15%",
              "firstBands",
              "0–49999 / 50000–99999 / 100000–199999",
              "maxSubPoints",
              16,
              "directorCounts",
              List.of(3, 2, 2, 2, 2),
              "originalThresholdField",
              "平均分阈值百分比：100/100/40/40/40；不能直接等同新系统除权阈值",
              "repeatTopThreshold",
              "12000000",
              "sourceDate",
              "2026-10-29",
              "lastBand",
              "38000000–78000000 → 15%",
              "smallShopThreshold",
              "5960000000（可能用于停用；需业务解释）");
      db.exec(
          "INSERT INTO rule_versions(id,name,status,source,config_json,created_by)"
              + " VALUES('LIVE-REFERENCE','原后台配置观察','REFERENCE','2026-10-29"
              + " 页面只读核对',#{p.json},'U_ADMIN')",
          map("json", write(reference)));
      db.exec(
          "INSERT INTO app_settings(id,current_rule,transfer_enabled) VALUES(1,'DEMO-V2',TRUE)",
          Map.of());
      String[] names = {"admin", "manager", "operator", "finance", "shipping", "auditor", "viewer"},
          roles = {"ADMIN", "MANAGER", "OPERATOR", "FINANCE", "SHIPPING", "AUDITOR", "VIEWER"};
      for (int i = 0; i < names.length; i++)
        user(i == 0 ? "U_ADMIN" : "U_" + roles[i], names[i], roles[i], null);
      user("U_MEMBER", "member", "MEMBER", "OWNER_MEMBER");
      user("U_SHOP", "shop", "MEMBER", "OWNER_SHOP");
      point("SYSTEM", "SYSTEM", "000000", null, 0, 0, "NONE", true, true, 1, "", 0, 0, 0);
      point("RECOVERY", "RECOVERY", "000001", null, 0, 0, "NONE", true, true, 1, "", 0, 0, 0);
      point(
          "CY7001",
          "OWNER_A",
          "001700001",
          null,
          7,
          0,
          "NONE",
          true,
          true,
          1,
          "",
          0,
          1200000,
          400000);
      point(
          "CY5001",
          "OWNER_B",
          "001500001",
          "CY7001",
          5,
          0,
          "NONE",
          true,
          true,
          1,
          "",
          0,
          300000,
          100000);
      point(
          "CY2002", "OWNER_C", "001200002", "CY5001", 2, 0, "NONE", true, true, 1, "", 5960, 0, 0);
      point(
          "CY1001",
          "OWNER_MEMBER",
          "001000001",
          "CY2002",
          0,
          0,
          "NONE",
          true,
          true,
          1,
          "",
          298,
          0,
          0);
      point(
          "CY1002",
          "OWNER_MEMBER",
          "001000001",
          "CY1001",
          0,
          0,
          "NONE",
          false,
          false,
          2,
          "",
          0,
          0,
          0);
      point(
          "CY9001",
          "OWNER_SHOP",
          "001900001",
          null,
          6,
          0,
          "BIG",
          true,
          true,
          1,
          "A,B,C,H_FUNDS,H_REPEAT,MALI,H_COMPANY2",
          24000,
          600000,
          200000);
      point(
          "CY6002",
          "OWNER_BING",
          "001600002",
          null,
          6,
          0,
          "SMALL",
          true,
          true,
          1,
          "A,BING",
          24000,
          600000,
          200000);
      point(
          "CY7002",
          "OWNER_S2",
          "001700002",
          null,
          7,
          0,
          "NONE",
          true,
          true,
          1,
          "",
          0,
          1200000,
          400000);
      point(
          "CY7003",
          "OWNER_S3",
          "001700003",
          null,
          7,
          0,
          "NONE",
          true,
          true,
          1,
          "",
          0,
          1200000,
          400000);
      point(
          "CY3001", "OWNER_NEW", "001300001", "CY1001", 0, 0, "NONE", true, false, 1, "", 0, 0, 0);
      for (int i = 1; i <= 6; i++)
        point(
            "CYD00" + i,
            "OWNER_D" + i,
            "00180000" + i,
            null,
            6,
            i,
            "NONE",
            true,
            true,
            1,
            "",
            0,
            600000,
            200000);
      db.exec(
          "UPDATE member_points SET plan_override='YES' WHERE id IN ('CY9001','CY6002')", Map.of());
      db.exec(
          "INSERT INTO"
              + " products(id,name,first_price,repeat_one,repeat_other,limit_one,limit_other,subsidy_one,subsidy_rate,differential_json,weight_kg)"
              + " VALUES('PR001','初语山言 · 会员专供',298,150,99,2,4,51,0.05,'[1,2,3,4,5]',0.5)",
          Map.of());
      List<Ledger.Change> start = new ArrayList<>();
      start.add(Ledger.Change.money("CY1001", "REWARD", d("1200"), "合成演示期初积分", false));
      start.add(Ledger.Change.money("CY9001", "RECHARGE", d("20000"), "合成演示期初充值", false));
      start.add(Ledger.Change.money("CY9001", "REWARD", d("3000"), "合成演示期初积分", false));
      ledger.post("SEED-OPENING", "DEMO_OPENING", "DEMO", "合成演示期初资金", start);
      String sale =
          text(
              orders.create(
                  map(
                      "pointId",
                      "CY1001",
                      "shopId",
                      "CY9001",
                      "payerId",
                      "CY1001",
                      "type",
                      "FIRST",
                      "productId",
                      "PR001",
                      "quantity",
                      5,
                      "rewardAmount",
                      "0")),
              "id");
      orders.pay(sale);
      String repeat =
          text(
              orders.create(
                  map(
                      "pointId",
                      "CY2002",
                      "shopId",
                      "CY9001",
                      "type",
                      "REPEAT",
                      "productId",
                      "PR001",
                      "quantity",
                      4,
                      "rewardAmount",
                      "0")),
              "id");
      orders.pay(repeat);
      LocalDateTime lastMonth = Store.today().withDayOfMonth(1).minusDays(5).atTime(10, 0);
      backdate(sale, lastMonth);
      backdate(repeat, lastMonth.plusHours(1));
      String yesterday =
          text(
              orders.create(
                  map(
                      "pointId",
                      "CY1001",
                      "shopId",
                      "CY9001",
                      "type",
                      "FIRST",
                      "productId",
                      "PR001",
                      "quantity",
                      4,
                      "rewardAmount",
                      "0")),
              "id");
      orders.pay(yesterday);
      backdate(yesterday, Store.today().minusDays(1).atTime(10, 0));
      String pending =
          text(
              orders.create(
                  map(
                      "pointId",
                      "CY3001",
                      "shopId",
                      "CY9001",
                      "type",
                      "NEW",
                      "productId",
                      "PR001",
                      "quantity",
                      1,
                      "rewardAmount",
                      "0")),
              "id");
      orders.pay(pending);
      LocalDateTime initial = Store.today().withDayOfMonth(1).minusMonths(2).atStartOfDay();
      db.exec("DELETE FROM member_history", Map.of());
      db.exec(
          "UPDATE member_points SET created_at=#{p.date} WHERE id NOT IN ('SYSTEM','RECOVERY')",
          map("date", Timestamp.valueOf(initial)));
      for (var p : db.list("SELECT id FROM member_points WHERE id NOT IN ('SYSTEM','RECOVERY')"))
        db.recordPoint(text(p, "id"), initial);
      db.audit(
          "DEMO_SEED",
          "DEMO",
          null,
          map("data", "全部为合成演示数据", "source", "用户确认演示口径"),
          "新数据库初始化；未导入旧数据");
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private void user(String id, String username, String role, String owner) {
    db.exec(
        "INSERT INTO"
            + " app_users(id,username,display_name,phone,password_hash,payment_hash,role,owner_id)"
            + " VALUES(#{p.id},#{p.username},#{p.name},'000000',#{p.password},#{p.payment},#{p.role},#{p.owner})",
        map(
            "id",
            id,
            "username",
            username,
            "name",
            switch (username) {
              case "member" -> "林间 · 演示会员";
              case "shop" -> "山言 · 演示店铺";
              default -> username + " · 演示角色";
            },
            "password",
            encoder.encode("ChuyuDemo!2026"),
            "payment",
            encoder.encode("123456"),
            "role",
            role,
            "owner",
            owner));
  }

  private void point(
      String id,
      String owner,
      String phone,
      String parent,
      int grade,
      int director,
      String shop,
      boolean main,
      boolean activated,
      int sequence,
      String identities,
      int own,
      int team,
      int small) {
    db.exec(
        "INSERT INTO"
            + " member_points(id,owner_id,phone,nickname,parent_id,is_main,sequence_no,grade,director_grade,shop_type,activated,identities,manual_own,manual_team,manual_small)"
            + " VALUES(#{p.id},#{p.owner},#{p.phone},#{p.name},#{p.parent},#{p.main},#{p.sequence},#{p.grade},#{p.director},#{p.shop},#{p.activated},#{p.identities},#{p.own},#{p.team},#{p.small})",
        map(
            "id",
            id,
            "owner",
            owner,
            "phone",
            phone,
            "name",
            switch (id) {
              case "CY1001" -> "林间 · 主号";
              case "CY1002" -> "林间 · 小号01";
              case "CY9001" -> "山言 · 城市店";
              case "CY3001" -> "新芽 · 待激活";
              default -> "演示会员 " + id;
            },
            "parent",
            parent,
            "main",
            main,
            "sequence",
            sequence,
            "grade",
            grade,
            "director",
            director,
            "shop",
            shop,
            "activated",
            activated,
            "identities",
            identities,
            "own",
            own,
            "team",
            team,
            "small",
            small));
    for (String kind : List.of("REWARD", "RECHARGE"))
      db.exec(
          "INSERT INTO wallets(point_id,kind) VALUES(#{p.id},#{p.kind})",
          map("id", id, "kind", kind));
  }

  private void backdate(String id, LocalDateTime at) {
    db.exec(
        "UPDATE shop_orders SET created_at=#{p.at},paid_at=#{p.at},activated_at=#{p.at} WHERE"
            + " id=#{p.id}",
        map("id", id, "at", Timestamp.valueOf(at)));
    db.exec(
        "UPDATE reward_awards SET created_at=#{p.at} WHERE order_id=#{p.id}",
        map("id", id, "at", Timestamp.valueOf(at)));
  }
}
