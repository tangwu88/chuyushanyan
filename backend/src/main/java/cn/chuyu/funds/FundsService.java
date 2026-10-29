package cn.chuyu.funds;

import static cn.chuyu.shared.BusinessException.require;
import static cn.chuyu.shared.Json.*;
import static cn.chuyu.shared.Money.*;

import cn.chuyu.members.MemberService;
import cn.chuyu.rewards.RuleService;
import cn.chuyu.shared.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class FundsService {
  private final Store db;
  private final Ledger ledger;
  private final MemberService members;
  private final RuleService rules;
  private final BCryptPasswordEncoder encoder;

  public FundsService(
      Store db,
      Ledger ledger,
      MemberService members,
      RuleService rules,
      BCryptPasswordEncoder encoder) {
    this.db = db;
    this.ledger = ledger;
    this.members = members;
    this.rules = rules;
    this.encoder = encoder;
  }

  public void paymentPassword(Map<String, Object> b) {
    var user =
        db.required(
            "SELECT payment_hash FROM app_users WHERE id=#{p.id}",
            map("id", Actors.current().id()));
    require(encoder.matches(text(b, "paymentPassword"), text(user, "paymentHash")), "支付密码错误");
  }

  private void usable(String point) {
    members.own(point);
    var p = members.point(point);
    require(bool(p.get("active")) && bool(p.get("activated")), "当前点位已冻结或尚未激活");
  }

  public Map<String, Object> transfer(Map<String, Object> b) {
    require(bool(rules.settings().get("transferEnabled")), "奖励积分转账已关闭");
    String from = text(b, "fromId"), to = text(b, "toId");
    usable(from);
    paymentPassword(b);
    require(!from.equals(to), "不能转给同一个点位");
    var recipient = members.point(to);
    require(bool(recipient.get("active")) && bool(recipient.get("activated")), "收款账户不可用");
    BigDecimal amount = input(b.get("amount"));
    require(amount.compareTo(d(1)) >= 0, "最少转账金额1元");
    String transfer = id();
    ledger.post(
        "TRANSFER:" + transfer,
        "TRANSFER",
        transfer,
        "会员奖励积分转账",
        List.of(
            Ledger.Change.money(
                from,
                "REWARD",
                amount.negate(),
                "转账给 " + text(recipient, "nickname") + " / " + to,
                false),
            Ledger.Change.money(to, "REWARD", amount, "收到 " + from + " 的转账", false)));
    db.audit("TRANSFER", transfer, from, map("to", to, "amount", amount), "会员奖励积分转账");
    return map("id", transfer, "amount", amount);
  }

  public Map<String, Object> convert(Map<String, Object> b) {
    String point = text(b, "pointId");
    usable(point);
    paymentPassword(b);
    BigDecimal amount = input(b.get("amount"));
    String tx = id();
    ledger.post(
        "CONVERT:" + tx,
        "CONVERSION",
        tx,
        "奖励积分转换充值积分",
        List.of(
            Ledger.Change.money(point, "REWARD", amount.negate(), "奖励积分转换销毁", false),
            Ledger.Change.money(point, "RECHARGE", amount, "转换充值积分", false)));
    db.audit("CONVERSION", tx, null, map("pointId", point, "amount", amount), "本人转换");
    return map("id", tx);
  }

  public Map<String, Object> withdraw(Map<String, Object> b) {
    String point = text(b, "pointId");
    usable(point);
    paymentPassword(b);
    BigDecimal amount = input(b.get("amount"));
    var r = rules.current();
    String bank = text(b, "bankAccount");
    require(bank.matches("[0-9]{10,30}"), "请输入10至30位银行卡号");
    String mask = "**** **** **** " + bank.substring(bank.length() - 4), id = id();
    BigDecimal fee = money(amount.multiply(r.withdrawalFee)),
        tax = money(amount.multiply(r.tax)),
        net = amount.subtract(fee).subtract(tax);
    ledger.post(
        "WITHDRAW_HOLD:" + id,
        "WITHDRAW_HOLD",
        id,
        "提现申请冻结",
        List.of(new Ledger.Change(point, "REWARD", ZERO, amount, ZERO, "提现申请冻结", false)));
    db.exec(
        "INSERT INTO withdrawals(id,point_id,gross,fee,tax,net,bank_mask,status,request_by,rule_id)"
            + " VALUES(#{p.id},#{p.point},#{p.gross},#{p.fee},#{p.tax},#{p.net},#{p.bank},'PENDING',#{p.actor},#{p.rule})",
        map(
            "id",
            id,
            "point",
            point,
            "gross",
            amount,
            "fee",
            fee,
            "tax",
            tax,
            "net",
            net,
            "bank",
            mask,
            "actor",
            Actors.current().id(),
            "rule",
            rules.currentId()));
    db.audit(
        "WITHDRAW_REQUEST",
        id,
        null,
        map("pointId", point, "gross", amount, "bank", mask),
        "演示提现申请");
    return map("id", id, "gross", amount, "fee", fee, "tax", tax, "net", net);
  }

  public Map<String, Object> reviewWithdrawal(String id, Map<String, Object> b) {
    Actors.require("withdraw:review");
    var row = db.required("SELECT * FROM withdrawals WHERE id=#{p.id}", map("id", id));
    require(text(row, "status").equals("PENDING"), "提现已处理");
    require(!text(row, "requestBy").equals(Actors.current().id()), "提现需要另一名财务人员审核");
    boolean approved = bool(b.get("approved"));
    BigDecimal amount = d(row.get("gross"));
    String point = text(row, "pointId");
    String reason = text(b, "reason");
    require(!reason.isBlank(), "请填写审批说明");
    List<Ledger.Change> changes = new ArrayList<>();
    changes.add(
        new Ledger.Change(
            point,
            "REWARD",
            approved ? amount.negate() : ZERO,
            amount.negate(),
            ZERO,
            approved ? "提现审核通过" : "提现驳回解冻",
            !approved));
    if (approved) {
      var w = ledger.wallet(point, "REWARD");
      require(d(w.get("balance")).compareTo(d(w.get("frozen"))) >= 0, "奖励余额已因冲正减少，请先驳回或核对");
      changes.add(
          Ledger.Change.money(
              rules.get(text(row, "ruleId")).recoveryPoint, "REWARD", amount, "提现积分回收对账", false));
    }
    ledger.post("WITHDRAW_REVIEW:" + id, "WITHDRAW_REVIEW", id, reason, changes);
    db.exec(
        "UPDATE withdrawals SET"
            + " status=#{p.status},review_by=#{p.actor},reason=#{p.reason},reviewed_at=CURRENT_TIMESTAMP"
            + " WHERE id=#{p.id}",
        map(
            "id",
            id,
            "status",
            approved ? "PAID_DEMO" : "REJECTED",
            "actor",
            Actors.current().id(),
            "reason",
            reason));
    db.audit("WITHDRAW_REVIEW", id, row, map("approved", approved), "演示审核：" + reason);
    return map("id", id, "status", approved ? "PAID_DEMO" : "REJECTED");
  }

  public Map<String, Object> requestTopup(Map<String, Object> b) {
    String point = text(b, "shopId");
    var shop = members.point(point);
    if (Actors.member()) members.own(point);
    else Actors.require("funds:write");
    require(!text(shop, "shopType").equals("NONE"), "此账户不是店铺");
    BigDecimal amount = input(b.get("amount"));
    String id = id();
    db.exec(
        "INSERT INTO topups(id,shop_id,amount,status,request_by)"
            + " VALUES(#{p.id},#{p.shop},#{p.amount},'PENDING',#{p.actor})",
        map("id", id, "shop", point, "amount", amount, "actor", Actors.current().id()));
    db.audit("TOPUP_REQUEST", id, null, b, "店铺充值申请");
    return map("id", id);
  }

  public Map<String, Object> reviewTopup(String id, Map<String, Object> b) {
    Actors.require("funds:write");
    var row = db.required("SELECT * FROM topups WHERE id=#{p.id}", map("id", id));
    require(text(row, "status").equals("PENDING"), "充值申请已处理");
    boolean approved = bool(b.get("approved"));
    if (approved)
      ledger.post(
          "TOPUP:" + id,
          "TOPUP",
          id,
          "演示店铺充值审批到账",
          List.of(
              Ledger.Change.money(
                  text(row, "shopId"), "RECHARGE", d(row.get("amount")), "店铺充值审批到账", false)));
    db.exec(
        "UPDATE topups SET status=#{p.status},review_by=#{p.actor} WHERE id=#{p.id}",
        map(
            "id",
            id,
            "status",
            approved ? "APPROVED" : "REJECTED",
            "actor",
            Actors.current().id()));
    db.audit("TOPUP_REVIEW", id, row, b, text(b, "reason"));
    return map("id", id);
  }

  public Map<String, Object> adjust(Map<String, Object> b) {
    Actors.require("funds:write");
    String point = text(b, "pointId"), kind = text(b, "kind"), why = text(b, "reason");
    members.point(point);
    require(!why.isBlank(), "请填写调整原因");
    BigDecimal amount = money(b.get("amount"));
    require(amount.signum() != 0, "调整金额不能为零");
    String id = id();
    ledger.post(
        "ADJUST:" + id,
        "MANUAL_ADJUST",
        id,
        why,
        List.of(Ledger.Change.money(point, kind, amount, why, kind.equals("REWARD"))));
    db.audit("FUNDS_ADJUST", id, null, b, why);
    return map("id", id);
  }

  public Map<String, Object> stockTransfer(Map<String, Object> b) {
    String from = text(b, "fromId"), to = text(b, "toId");
    if (Actors.member()) {
      usable(from);
      paymentPassword(b);
    } else Actors.require("funds:write");
    require(!from.equals(to), "不能转给同一店铺");
    require(
        !text(members.point(from), "shopType").equals("NONE")
            && !text(members.point(to), "shopType").equals("NONE"),
        "双方必须为店铺");
    BigDecimal amount = input(b.get("amount"));
    var w = ledger.wallet(from, "RECHARGE");
    BigDecimal restricted = d(w.get("restricted"));
    if (!Actors.has("funds:write"))
      require(
          d(w.get("balance")).subtract(restricted).compareTo(amount) >= 0,
          "撤单退回的充值积分仅可重新报单，转出需财务操作");
    String id = id();
    BigDecimal used =
        Actors.has("funds:write")
            ? amount.subtract(d(w.get("balance")).subtract(restricted)).max(ZERO)
            : ZERO;
    ledger.post(
        "STOCK_TRANSFER:" + id,
        "STOCK_TRANSFER",
        id,
        "店铺转货",
        List.of(
            new Ledger.Change(
                from, "RECHARGE", amount.negate(), ZERO, used.negate(), "转货至 " + to, false),
            Ledger.Change.money(to, "RECHARGE", amount, "收到店铺转货 " + from, false)));
    db.audit("STOCK_TRANSFER", id, null, b, "店铺转货");
    return map("id", id);
  }
}
