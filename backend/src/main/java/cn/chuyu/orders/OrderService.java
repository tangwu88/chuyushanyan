package cn.chuyu.orders;

import static cn.chuyu.shared.BusinessException.require;
import static cn.chuyu.shared.Json.*;
import static cn.chuyu.shared.Money.*;

import cn.chuyu.funds.*;
import cn.chuyu.members.*;
import cn.chuyu.rewards.*;
import cn.chuyu.shared.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class OrderService {
  private final Store db;
  private final MemberService members;
  private final PerformanceService performance;
  private final RuleService rules;
  private final Ledger ledger;
  private final PaymentGateway payment;

  public OrderService(
      Store db,
      MemberService members,
      PerformanceService performance,
      RuleService rules,
      Ledger ledger,
      PaymentGateway payment) {
    this.db = db;
    this.members = members;
    this.performance = performance;
    this.rules = rules;
    this.ledger = ledger;
    this.payment = payment;
  }

  public Map<String, Object> order(String id) {
    return db.required("SELECT * FROM shop_orders WHERE id=#{p.id}", map("id", id));
  }

  public void readAccess(Map<String, Object> order) {
    if (Actors.member())
      require(
          Actors.owns(members.point(text(order, "pointId")))
              || Actors.owns(members.point(text(order, "shopId")))
              || Actors.owns(members.point(text(order, "payerId"))),
          "无权查看此订单");
    else {
      Actors.require("orders:read");
      Actors.requireAnyPoint(
          members.point(text(order, "pointId")), members.point(text(order, "shopId")));
    }
  }

  public Map<String, Object> quote(Map<String, Object> b) {
    String point = text(b, "pointId"),
        shopId = text(b, "shopId"),
        type = text(b, "type"),
        productId = text(b, "productId"),
        payer = text(b, "payerId");
    if (payer.isBlank()) payer = point;
    var member = members.point(point);
    var shop = members.point(shopId);
    var product =
        db.required(
            "SELECT * FROM products WHERE id=#{p.id} AND active=TRUE", map("id", productId));
    require(bool(member.get("active")) && bool(shop.get("active")), "会员或店铺已冻结");
    require(!text(shop, "shopType").equals("NONE") && bool(shop.get("activated")), "请选择有效店铺");
    boolean assistingNew =
        type.equals("NEW")
            && !text(member, "parentId").isBlank()
            && Actors.owns(members.point(text(member, "parentId")));
    if (Actors.member())
      require(Actors.owns(member) || Actors.owns(shop) || assistingNew, "只能为本人、所管理店铺或本人新邀请会员创建订单");
    else {
      Actors.require("orders:create");
      Actors.requireAnyPoint(member, shop);
    }
    require(Set.of("NEW", "FIRST", "REPEAT").contains(type), "购买类型错误");
    int quantity = integer(b.get("quantity"));
    require(quantity > 0 && quantity <= 10000, "购买数量需为1至10000");
    if (type.equals("NEW")) require(!bool(member.get("activated")), "此点位已激活，请选择首购或复购");
    else require(bool(member.get("activated")), "请先完成新人订单激活");
    if (type.equals("NEW"))
      require(
          db.count(
                  "SELECT COUNT(*) FROM shop_orders WHERE point_id=#{p.id} AND order_type='NEW' AND"
                      + " cancelled=FALSE AND activation_status='PENDING' AND"
                      + " (payment_status='PAID' OR created_at>=#{p.since})",
                  map(
                      "id",
                      point,
                      "since",
                      Timestamp.valueOf(LocalDateTime.now(Store.ZONE).minusMinutes(15))))
              == 0,
          "此点位已有待处理的新人订单，请在订单列表继续处理");
    require(
        Arrays.asList(text(product, "purchaseTypes").split(",")).contains(type), "此商品不支持所选购买类型");
    int grade = integer(member.get("grade")), remaining = 10000;
    BigDecimal price = d(product.get("firstPrice"));
    if (type.equals("REPEAT")) {
      require(grade >= 1, "一星及以上会员可以复购");
      int limit = integer(product.get(grade == 1 ? "limitOne" : "limitOther"));
      LocalDateTime first = Store.today().withDayOfMonth(1).atStartOfDay();
      int used =
          (int)
              db.count(
                  "SELECT COALESCE(SUM(quantity),0) n FROM shop_orders WHERE point_id=#{p.point}"
                      + " AND product_id=#{p.product} AND order_type='REPEAT' AND cancelled=FALSE"
                      + " AND created_at>=#{p.start} AND (payment_status='PAID' OR"
                      + " created_at>=#{p.reserve}) AND id<>#{p.exclude}",
                  map(
                      "point",
                      point,
                      "product",
                      productId,
                      "start",
                      Timestamp.valueOf(first),
                      "reserve",
                      Timestamp.valueOf(LocalDateTime.now(Store.ZONE).minusMinutes(15)),
                      "exclude",
                      text(b, "excludeId")));
      remaining = Math.max(0, limit - used);
      require(quantity <= remaining, "本月复购剩余额度为" + remaining + "瓶");
      price = d(product.get(grade == 1 ? "repeatOne" : "repeatOther"));
    }
    BigDecimal total = money(price.multiply(d(quantity))),
        rawReward = d(b.get("rewardAmount")),
        reward = money(rawReward);
    require(rawReward.scale() <= 2 && rawReward.signum() >= 0, "抵扣积分需为非负金额，且最多两位小数");
    require(
        reward.compareTo(total.multiply(type.equals("REPEAT") ? ZERO : d(".70"))) <= 0,
        type.equals("REPEAT") ? "复购不支持奖励积分抵扣" : "奖励积分最高抵扣70%");
    if (reward.signum() > 0) {
      if (!Actors.has("funds:write")) members.own(payer);
      var wallet = ledger.wallet(payer, "REWARD");
      require(
          d(wallet.get("balance")).subtract(d(wallet.get("frozen"))).compareTo(reward) >= 0,
          "奖励积分不足");
    }
    String method = text(b, "paymentMethod");
    if (method.isBlank()) method = "DEMO";
    require(Set.of("DEMO", "STORE").contains(method), "支付方式错误");
    if (method.equals("STORE")) {
      require(Actors.owns(shop) || Actors.has("funds:write"), "只能使用本人店铺充值积分");
      var w = ledger.wallet(shopId, "RECHARGE");
      require(
          d(w.get("balance")).subtract(d(w.get("frozen"))).compareTo(total.subtract(reward)) >= 0,
          "店铺充值积分不足");
    }
    return map(
        "pointId",
        point,
        "shopId",
        shopId,
        "payerId",
        payer,
        "type",
        type,
        "productId",
        productId,
        "quantity",
        quantity,
        "unitPrice",
        price,
        "total",
        total,
        "rewardAmount",
        reward,
        "cashAmount",
        total.subtract(reward),
        "remainingQuota",
        remaining,
        "paymentMethod",
        method,
        "ruleId",
        rules.currentId(),
        "pickup",
        bool(b.get("pickup")),
        "product",
        product,
        "buyerName",
        member.get("nickname"),
        "parentId",
        member.get("parentId"),
        "parentName",
        text(member, "parentId").isBlank()
            ? "无"
            : members.point(text(member, "parentId")).get("nickname"),
        "shopName",
        shop.get("nickname"),
        "productName",
        product.get("name"));
  }

  public Map<String, Object> create(Map<String, Object> b) {
    var q = quote(b);
    String remark = text(b, "remark");
    require(remark.length() <= 1000, "订单备注最多1000字");
    q.put("remark", remark);
    String id = "OD" + id().substring(0, 16).toUpperCase(Locale.ROOT);
    q.put("id", id);
    q.put("snapshot", write(rules.current()));
    q.put("rule", rules.currentId());
    q.put("memberSnapshot", write(q.get("product")));
    db.exec(
        "INSERT INTO"
            + " shop_orders(id,point_id,shop_id,payer_id,product_id,order_type,quantity,unit_price,total,reward_paid,cash_paid,payment_status,activation_status,fulfilment_status,pickup,rule_id,rule_snapshot,member_snapshot,payment_method,remark)"
            + " VALUES(#{p.id},#{p.pointId},#{p.shopId},#{p.payerId},#{p.productId},#{p.type},#{p.quantity},#{p.unitPrice},#{p.total},#{p.rewardAmount},#{p.cashAmount},'UNPAID','PENDING','UNFULFILLED',#{p.pickup},#{p.rule},#{p.snapshot},#{p.memberSnapshot},#{p.paymentMethod},#{p.remark})",
        q);
    db.audit("ORDER_CREATE", id, null, q, "创建订单");
    return order(id);
  }

  public Map<String, Object> pay(String id) {
    var order = order(id);
    readAccess(order);
    if (!Actors.member()) Actors.require("orders:create");
    require(
        !bool(order.get("cancelled")) && text(order, "paymentStatus").equals("UNPAID"),
        "订单已支付或已撤销");
    require(
        LocalDateTime.parse(text(order, "createdAt"))
            .plusMinutes(15)
            .isAfter(LocalDateTime.now(Store.ZONE)),
        "订单已超过15分钟支付有效期，请撤销后重新下单");
    if (Actors.member() && !Actors.owns(members.point(text(order, "payerId"))))
      require(d(order.get("rewardPaid")).signum() == 0, "付款积分账户不属于本人");
    require(
        bool(members.point(text(order, "pointId")).get("active"))
            && bool(members.point(text(order, "shopId")).get("active")),
        "会员或店铺已冻结");
    if (text(order, "orderType").equals("REPEAT")) {
      var product =
          db.required("SELECT * FROM products WHERE id=#{p.id}", map("id", order.get("productId")));
      int grade = integer(members.point(text(order, "pointId")).get("grade"));
      require(grade >= 1, "当前等级不可复购");
      long paid =
          db.count(
              "SELECT COALESCE(SUM(quantity),0) n FROM shop_orders WHERE point_id=#{p.point} AND"
                  + " product_id=#{p.product} AND order_type='REPEAT' AND cancelled=FALSE AND"
                  + " payment_status='PAID' AND paid_at>=#{p.start}",
              map(
                  "point",
                  order.get("pointId"),
                  "product",
                  order.get("productId"),
                  "start",
                  Timestamp.valueOf(Store.today().withDayOfMonth(1).atStartOfDay())));
      require(
          paid + integer(order.get("quantity"))
              <= integer(product.get(grade == 1 ? "limitOne" : "limitOther")),
          "本月复购额度已使用，请重新下单");
    }
    payment.confirm(id);
    List<Ledger.Change> changes = new ArrayList<>();
    BigDecimal reward = d(order.get("rewardPaid")), total = d(order.get("total"));
    if (reward.signum() > 0)
      changes.add(
          Ledger.Change.money(
              text(order, "payerId"), "REWARD", reward.negate(), "订单奖励积分抵扣销毁", false));
    BigDecimal credit = text(order, "paymentMethod").equals("STORE") ? reward : total;
    changes.add(
        new Ledger.Change(
            text(order, "shopId"), "RECHARGE", credit, total, ZERO, "订单支付并预留待激活资金", false));
    if (!changes.isEmpty()) ledger.post("PAY:" + id, "ORDER_PAYMENT", id, "演示支付确认", changes);
    db.exec(
        "UPDATE shop_orders SET"
            + " payment_status='PAID',paid_at=CURRENT_TIMESTAMP,reserved_amount=total WHERE"
            + " id=#{p.id}",
        map("id", id));
    db.audit("ORDER_PAY_DEMO", id, null, null, "演示支付，不涉及实际收款");
    if (!text(order, "orderType").equals("NEW")) activateInternal(id);
    return order(id);
  }

  public Map<String, Object> activate(String id) {
    var o = order(id);
    if (Actors.member()) require(Actors.owns(members.point(text(o, "shopId"))), "仅代办店铺可激活新人订单");
    else {
      Actors.require("orders:activate");
      Actors.requireAnyPoint(members.point(text(o, "pointId")), members.point(text(o, "shopId")));
    }
    activateInternal(id);
    return order(id);
  }

  @SuppressWarnings("unchecked")
  private void activateInternal(String id) {
    var o = order(id);
    require(
        !bool(o.get("cancelled"))
            && text(o, "paymentStatus").equals("PAID")
            && text(o, "activationStatus").equals("PENDING"),
        "订单当前不能激活");
    var buyer = members.point(text(o, "pointId"));
    require(
        bool(buyer.get("active")) && bool(members.point(text(o, "shopId")).get("active")),
        "会员或店铺已冻结，不能激活订单");
    require(
        db.count(
                "SELECT COUNT(*) n FROM member_points WHERE owner_id=#{p.owner} AND"
                    + " sequence_no<#{p.sequence} AND activated=FALSE AND active=TRUE",
                map("owner", buyer.get("ownerId"), "sequence", buyer.get("sequenceNo")))
            == 0,
        "请按注册的先后顺序激活");
    require(!text(o, "orderType").equals("NEW") || !bool(buyer.get("activated")), "此点位已激活");
    var product = read(text(o, "memberSnapshot"), Map.class);
    Rules r = read(text(o, "ruleSnapshot"), Rules.class);
    var nodes = performance.currentNodes();
    var differential = (List<Object>) read(text(product, "differentialJson"), List.class);
    var sale =
        new RewardEngine.Sale(
            id,
            text(o, "pointId"),
            text(o, "shopId"),
            text(o, "orderType"),
            d(o.get("total")),
            integer(o.get("quantity")),
            differential.stream().map(Money::d).toList(),
            d(product.get("subsidyOne")),
            d(product.get("subsidyRate")));
    var result = new RewardEngine().immediate(r, nodes, sale);
    List<Ledger.Change> changes = new ArrayList<>();
    var w = ledger.wallet(text(o, "shopId"), "RECHARGE");
    BigDecimal used = d(w.get("restricted")).min(d(o.get("total")));
    changes.add(
        new Ledger.Change(
            text(o, "shopId"),
            "RECHARGE",
            d(o.get("total")).negate(),
            d(o.get("reservedAmount")).negate(),
            used.negate(),
            "订单激活消费并释放预留",
            false));
    for (var a : result.awards())
      changes.add(Ledger.Change.money(a.recipient(), "REWARD", a.net(), a.explanation(), false));
    ledger.post("ACTIVATE:" + id, "ORDER_ACTIVATE", id, "订单激活及实时奖金", changes);
    insertAwards(result, id, null, text(o, "ruleId"));
    db.exec(
        "UPDATE shop_orders SET"
            + " activation_status='ACTIVE',activated_at=CURRENT_TIMESTAMP,reserved_amount=0,member_snapshot=#{p.snapshot}"
            + " WHERE id=#{p.id}",
        map(
            "id",
            id,
            "snapshot",
            write(map("product", product, "nodes", nodes, "awards", result))));
    db.exec(
        "UPDATE member_points SET activated=TRUE,empty_point=FALSE WHERE id=#{p.id}",
        map("id", o.get("pointId")));
    performance.refreshGrades(false);
    db.audit("ORDER_ACTIVATE", id, o, result, "订单激活");
    db.recordPoint(text(o, "pointId"), LocalDateTime.now(Store.ZONE));
  }

  public void insertAwards(RewardEngine.Result result, String order, String batch, String rule) {
    for (var a : result.awards())
      db.exec(
          "INSERT INTO"
              + " reward_awards(id,batch_id,order_id,recipient_id,award_type,base_amount,rate,gross,fee,net,explanation,rule_id)"
              + " VALUES(#{p.id},#{p.batch},#{p.order},#{p.recipient},#{p.type},#{p.base},#{p.rate},#{p.gross},#{p.fee},#{p.net},#{p.why},#{p.rule})",
          map(
              "id",
              id(),
              "batch",
              batch,
              "order",
              order,
              "recipient",
              a.recipient(),
              "type",
              a.type(),
              "base",
              a.base(),
              "rate",
              a.rate(),
              "gross",
              a.gross(),
              "fee",
              a.fee(),
              "net",
              a.net(),
              "why",
              a.explanation(),
              "rule",
              rule));
  }

  public Map<String, Object> cancel(String id, Map<String, Object> b) {
    var o = order(id);
    if (Actors.member())
      require(
          Actors.owns(members.point(text(o, "shopId")))
              || (text(o, "paymentStatus").equals("UNPAID")
                  && (Actors.owns(members.point(text(o, "pointId")))
                      || Actors.owns(members.point(text(o, "payerId"))))),
          "已付款订单请由代办店铺处理撤销");
    else {
      Actors.require("orders:cancel");
      Actors.requireAnyPoint(members.point(text(o, "pointId")), members.point(text(o, "shopId")));
    }
    require(!bool(o.get("cancelled")), "订单已撤销");
    String why = text(b, "reason");
    require(!why.isBlank(), "请填写撤销原因");
    List<Ledger.Change> changes = new ArrayList<>();
    var awards =
        db.list(
            "SELECT * FROM reward_awards WHERE order_id=#{p.id} AND reversed=FALSE", map("id", id));
    for (var a : awards)
      changes.add(
          Ledger.Change.money(
              text(a, "recipientId"), "REWARD", d(a.get("net")).negate(), "撤销冲回奖金：" + why, true));
    if (text(o, "paymentStatus").equals("PAID")) {
      BigDecimal total = d(o.get("total"));
      boolean active = text(o, "activationStatus").equals("ACTIVE");
      var wallet = ledger.wallet(text(o, "shopId"), "RECHARGE");
      BigDecimal restrict =
          active
              ? total
              : total.min(d(wallet.get("balance")).subtract(d(wallet.get("restricted"))).max(ZERO));
      changes.add(
          new Ledger.Change(
              text(o, "shopId"),
              "RECHARGE",
              active ? total : ZERO,
              d(o.get("reservedAmount")).negate(),
              restrict,
              "撤销退款至受限充值积分并释放预留",
              false));
    }
    if (!changes.isEmpty()) ledger.post("CANCEL:" + id, "ORDER_CANCEL", id, why, changes);
    db.exec("UPDATE reward_awards SET reversed=TRUE WHERE order_id=#{p.id}", map("id", id));
    db.exec(
        "UPDATE shop_orders SET"
            + " cancelled=TRUE,cancelled_at=CURRENT_TIMESTAMP,reserved_amount=0,payment_status=CASE"
            + " WHEN payment_status='PAID' THEN 'REFUNDED' ELSE payment_status END WHERE"
            + " id=#{p.id}",
        map("id", id));
    db.exec("UPDATE fulfilment_lines SET cancelled=TRUE WHERE order_id=#{p.id}", map("id", id));
    for (var app :
        db.list(
            "SELECT DISTINCT application_id FROM fulfilment_lines WHERE order_id=#{p.id}",
            map("id", id)))
      if (db.count(
              "SELECT COUNT(*) FROM fulfilment_lines WHERE application_id=#{p.id} AND"
                  + " cancelled=FALSE",
              map("id", app.get("applicationId")))
          == 0)
        db.exec(
            "UPDATE fulfilment_applications SET status='VOID',reason='所含订单已全部撤销' WHERE id=#{p.id}"
                + " AND status IN ('PENDING','APPROVED')",
            map("id", app.get("applicationId")));
    if (text(o, "activationStatus").equals("ACTIVE")) {
      var points = performance.points();
      Map<String, Map<String, Object>> ix = new HashMap<>();
      points.forEach(p -> ix.put(text(p, "id"), p));
      boolean settled =
          db.count(
                  "SELECT COUNT(*) n FROM settlement_batches WHERE status='POSTED' AND"
                      + " period_type='DAILY' AND period=#{p.day}",
                  map("day", text(o, "activatedAt").substring(0, 10)))
              > 0;
      if (settled)
        for (var p : points)
          if (performance.below(text(o, "pointId"), text(p, "id"), ix))
            db.exec(
                "INSERT INTO performance_offsets(id,point_id,order_id,remaining)"
                    + " VALUES(#{p.id},#{p.point},#{p.order},#{p.amount})",
                map("id", id(), "point", p.get("id"), "order", id, "amount", o.get("total")));
      performance.refreshGrades(true);
    }
    db.audit("ORDER_CANCEL", id, o, order(id), why);
    return order(id);
  }
}
