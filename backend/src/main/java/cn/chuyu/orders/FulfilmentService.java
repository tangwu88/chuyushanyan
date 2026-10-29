package cn.chuyu.orders;

import static cn.chuyu.shared.BusinessException.require;
import static cn.chuyu.shared.Json.*;
import static cn.chuyu.shared.Money.*;

import cn.chuyu.funds.Ledger;
import cn.chuyu.members.MemberService;
import cn.chuyu.rewards.RuleService;
import cn.chuyu.shared.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class FulfilmentService {
  private final Store db;
  private final MemberService members;
  private final Ledger ledger;
  private final RuleService rules;

  public FulfilmentService(Store db, MemberService members, Ledger ledger, RuleService rules) {
    this.db = db;
    this.members = members;
    this.ledger = ledger;
    this.rules = rules;
  }

  public List<Map<String, Object>> lines(String id) {
    return db.list(
        "SELECT l.*,o.order_type,o.cancelled order_cancelled FROM fulfilment_lines l JOIN"
            + " shop_orders o ON o.id=l.order_id WHERE l.application_id=#{p.id}",
        map("id", id));
  }

  public int allocated(String order) {
    return (int)
        db.count(
            "SELECT COALESCE(SUM(l.quantity),0) n FROM fulfilment_lines l JOIN"
                + " fulfilment_applications a ON a.id=l.application_id WHERE l.order_id=#{p.id} AND"
                + " l.cancelled=FALSE AND a.status NOT IN ('REJECTED','VOID')",
            map("id", order));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> apply(Map<String, Object> b) {
    String shop = text(b, "shopId");
    if (Actors.member()) members.own(shop);
    else Actors.require("shipping:review");
    require(!text(members.point(shop), "shopType").equals("NONE"), "请选择店铺");
    require(
        d(ledger.wallet(shop, "RECHARGE").get("balance"))
                .compareTo(rules.current().shippingThreshold)
            >= 0,
        "店铺充值积分未达到发货门槛 " + rules.current().shippingThreshold);
    List<Map<String, Object>> lines = (List<Map<String, Object>>) b.get("lines");
    require(lines != null && !lines.isEmpty(), "请至少选择一条订单明细");
    String id = "SH" + id().substring(0, 16).toUpperCase(Locale.ROOT);
    boolean pickup = bool(b.get("pickup"));
    Set<String> seen = new HashSet<>();
    String recipient = text(b, "recipient"), phone = text(b, "phone"), address = text(b, "address");
    require(!recipient.isBlank() && phone.matches("[0-9]{6,24}"), "请填写收货人和有效电话");
    require(pickup || !address.isBlank(), "请填写收货地址");
    db.exec(
        "INSERT INTO"
            + " fulfilment_applications(id,shop_id,status,pickup,recipient,phone,address,request_by)"
            + " VALUES(#{p.id},#{p.shop},'PENDING',#{p.pickup},#{p.recipient},#{p.phone},#{p.address},#{p.actor})",
        map(
            "id",
            id,
            "shop",
            shop,
            "pickup",
            pickup,
            "recipient",
            recipient,
            "phone",
            phone,
            "address",
            address,
            "actor",
            Actors.current().id()));
    for (var line : lines) {
      String order = text(line, "orderId");
      require(seen.add(order), "同一订单明细不能重复选择");
      var o = db.required("SELECT * FROM shop_orders WHERE id=#{p.id}", map("id", order));
      int qty = integer(line.get("quantity"));
      require(
          text(o, "shopId").equals(shop)
              && !bool(o.get("cancelled"))
              && text(o, "activationStatus").equals("ACTIVE"),
          "所选订单不可申请发货");
      require(qty > 0 && qty <= integer(o.get("quantity")) - allocated(order), "发货数量超过此订单可申请数量");
      require(bool(o.get("pickup")) == pickup, "自提订单和配送订单请分别申请");
      db.exec(
          "INSERT INTO fulfilment_lines(id,application_id,order_id,quantity)"
              + " VALUES(#{p.id},#{p.app},#{p.order},#{p.qty})",
          map("id", id(), "app", id, "order", order, "qty", qty));
      db.exec(
          "UPDATE shop_orders SET fulfilment_status='APPLIED' WHERE id=#{p.id}", map("id", order));
    }
    db.audit("SHIP_APPLY", id, null, b, "提交发货申请");
    return map("id", id);
  }

  public Map<String, Object> review(String id, Map<String, Object> b) {
    Actors.require("shipping:review");
    var a = db.required("SELECT * FROM fulfilment_applications WHERE id=#{p.id}", map("id", id));
    require(text(a, "status").equals("PENDING"), "此发货申请已审批");
    require(!text(b, "reason").isBlank(), "请填写审批说明");
    boolean approved = bool(b.get("approved"));
    require(!approved || lines(id).stream().anyMatch(l -> !bool(l.get("cancelled"))), "所有明细均已撤销");
    db.exec(
        "UPDATE fulfilment_applications SET"
            + " status=#{p.status},reviewed_by=#{p.actor},reason=#{p.reason} WHERE id=#{p.id}",
        map(
            "id",
            id,
            "status",
            approved ? "APPROVED" : "REJECTED",
            "actor",
            Actors.current().id(),
            "reason",
            text(b, "reason")));
    if (!approved) for (var l : lines(id)) refreshOrder(text(l, "orderId"));
    db.audit("SHIP_REVIEW", id, a, b, text(b, "reason"));
    return map("id", id);
  }

  public Map<String, Object> ship(String id, Map<String, Object> b) {
    Actors.require("shipping:ship");
    var a = db.required("SELECT * FROM fulfilment_applications WHERE id=#{p.id}", map("id", id));
    require(text(a, "status").equals("APPROVED"), "需先审批再发货");
    boolean pickup = bool(a.get("pickup"));
    String carrier = text(b, "carrier"), tracking = text(b, "tracking");
    require(pickup || (!carrier.isBlank() && !tracking.isBlank()), "请填写快递公司和运单号");
    require(lines(id).stream().anyMatch(l -> !bool(l.get("cancelled"))), "没有可发货明细");
    db.exec(
        "UPDATE fulfilment_applications SET"
            + " status=#{p.status},carrier=#{p.carrier},tracking=#{p.tracking},shipped_by=#{p.actor},shipped_at=CURRENT_TIMESTAMP"
            + " WHERE id=#{p.id}",
        map(
            "id",
            id,
            "status",
            pickup ? "PICKED_UP" : "SHIPPED",
            "carrier",
            pickup ? "自提" : carrier,
            "tracking",
            pickup ? "" : tracking,
            "actor",
            Actors.current().id()));
    for (var l : lines(id)) refreshOrder(text(l, "orderId"));
    db.audit("SHIP_DISPATCH", id, a, b, pickup ? "自提完成" : "登记物流发货");
    return map("id", id);
  }

  private void refreshOrder(String id) {
    int shipped =
        (int)
            db.count(
                "SELECT COALESCE(SUM(l.quantity),0) n FROM fulfilment_lines l JOIN"
                    + " fulfilment_applications a ON a.id=l.application_id WHERE l.order_id=#{p.id}"
                    + " AND l.cancelled=FALSE AND a.status IN ('SHIPPED','PICKED_UP','DELIVERED')",
                map("id", id));
    var o = db.required("SELECT * FROM shop_orders WHERE id=#{p.id}", map("id", id));
    String status =
        shipped >= integer(o.get("quantity"))
            ? "FULFILLED"
            : shipped > 0 ? "PARTIAL" : allocated(id) > 0 ? "APPLIED" : "UNFULFILLED";
    db.exec(
        "UPDATE shop_orders SET fulfilment_status=#{p.status} WHERE id=#{p.id}",
        map("id", id, "status", status));
  }
}
