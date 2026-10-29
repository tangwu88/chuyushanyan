package cn.chuyu.rewards;

import static cn.chuyu.shared.BusinessException.require;
import static cn.chuyu.shared.Json.*;
import static cn.chuyu.shared.Money.*;

import cn.chuyu.funds.*;
import cn.chuyu.members.PerformanceService;
import cn.chuyu.orders.OrderService;
import cn.chuyu.shared.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class SettlementService {
  private final Store db;
  private final RuleService rules;
  private final PerformanceService performance;
  private final Ledger ledger;
  private final OrderService orders;

  public SettlementService(
      Store db,
      RuleService rules,
      PerformanceService performance,
      Ledger ledger,
      OrderService orders) {
    this.db = db;
    this.rules = rules;
    this.performance = performance;
    this.ledger = ledger;
    this.orders = orders;
  }

  public record Context(
      String ruleId,
      Rules rules,
      String cycle,
      String period,
      String start,
      String end,
      List<RewardEngine.Node> nodes,
      BigDecimal report,
      BigDecimal repeat) {}

  public Context context(String cycle, String period, String rule) {
    require(Set.of("DAILY", "MONTHLY", "MONTHLY_REPEAT").contains(cycle), "结算周期错误");
    LocalDateTime start, end;
    try {
      if (cycle.equals("DAILY")) {
        start = LocalDate.parse(period).atStartOfDay();
        end = start.plusDays(1);
      } else {
        start = YearMonth.parse(period).atDay(1).atStartOfDay();
        end = start.plusMonths(1);
      }
    } catch (Exception e) {
      throw new BusinessException("账期格式应为日 YYYY-MM-DD 或月 YYYY-MM");
    }
    var totals =
        db.required(
            "SELECT COALESCE(SUM(CASE WHEN order_type='REPEAT' THEN 0 ELSE total END),0)"
                + " report_total,COALESCE(SUM(CASE WHEN order_type='REPEAT' THEN total ELSE 0"
                + " END),0) repeat_total FROM shop_orders WHERE activation_status='ACTIVE' AND"
                + " activated_at>=#{p.start} AND activated_at<#{p.end} AND (cancelled=FALSE OR"
                + " cancelled_at>=#{p.end})",
            map("start", Timestamp.valueOf(start), "end", Timestamp.valueOf(end)));
    return new Context(
        rule,
        rules.get(rule),
        cycle,
        period,
        start.toString(),
        end.toString(),
        performance.nodes(start, end),
        d(totals.get("reportTotal")),
        d(totals.get("repeatTotal")));
  }

  public Map<String, Object> preview(Map<String, Object> b) {
    Actors.require("funds:write");
    String cycle = text(b, "cycle"), period = text(b, "period"), rule = text(b, "ruleId");
    if (rule.isBlank()) rule = rules.currentId();
    var c = context(cycle, period, rule);
    var result =
        new RewardEngine().periodic(c.rules(), c.nodes(), c.report(), c.repeat(), cycle, period);
    String key = cycle + ":" + period;
    var existing =
        db.one("SELECT * FROM settlement_batches WHERE business_key=#{p.key}", map("key", key));
    if (existing != null && text(existing, "status").equals("POSTED"))
      return detail(text(existing, "id"));
    String id = existing == null ? id() : text(existing, "id");
    if (existing == null)
      db.exec(
          "INSERT INTO"
              + " settlement_batches(id,business_key,period_type,period,rule_id,status,snapshot_json,awards_json,context_hash,gross,fee,net,created_by)"
              + " VALUES(#{p.id},#{p.key},#{p.cycle},#{p.period},#{p.rule},'PREVIEW',#{p.context},#{p.awards},#{p.hash},#{p.gross},#{p.fee},#{p.net},#{p.actor})",
          map(
              "id",
              id,
              "key",
              key,
              "cycle",
              cycle,
              "period",
              period,
              "rule",
              rule,
              "context",
              write(c),
              "awards",
              write(result),
              "hash",
              hash(write(c)),
              "gross",
              result.gross(),
              "fee",
              result.fee(),
              "net",
              result.net(),
              "actor",
              Actors.current().id()));
    else
      db.exec(
          "UPDATE settlement_batches SET"
              + " rule_id=#{p.rule},snapshot_json=#{p.context},awards_json=#{p.awards},context_hash=#{p.hash},gross=#{p.gross},fee=#{p.fee},net=#{p.net}"
              + " WHERE id=#{p.id}",
          map(
              "id",
              id,
              "rule",
              rule,
              "context",
              write(c),
              "awards",
              write(result),
              "hash",
              hash(write(c)),
              "gross",
              result.gross(),
              "fee",
              result.fee(),
              "net",
              result.net()));
    db.audit(
        "SETTLEMENT_PREVIEW",
        id,
        null,
        map("cycle", cycle, "period", period, "net", result.net()),
        "结算预览");
    return detail(id);
  }

  public Map<String, Object> detail(String id) {
    var row = db.required("SELECT * FROM settlement_batches WHERE id=#{p.id}", map("id", id));
    Map<String, Object> out = new LinkedHashMap<>(row);
    out.put("result", read(text(row, "awardsJson"), Map.class));
    out.remove("snapshotJson");
    out.remove("awardsJson");
    return out;
  }

  public Map<String, Object> post(String id) {
    Actors.require("funds:write");
    var batch = db.required("SELECT * FROM settlement_batches WHERE id=#{p.id}", map("id", id));
    require(text(batch, "status").equals("PREVIEW"), "此账期已经入账");
    Context c = read(text(batch, "snapshotJson"), Context.class);
    require(
        !LocalDateTime.parse(c.end()).isAfter(LocalDateTime.now(Store.ZONE)), "进行中的账期只能预览，结束后才能入账");
    require(
        hash(write(context(c.cycle(), c.period(), c.ruleId()))).equals(text(batch, "contextHash")),
        "计算依据已变化，请重新预览");
    var result =
        new RewardEngine()
            .periodic(c.rules(), c.nodes(), c.report(), c.repeat(), c.cycle(), c.period());
    require(write(result).equals(text(batch, "awardsJson")), "预览和入账结果不一致");
    List<Ledger.Change> changes =
        result.awards().stream()
            .map(
                a ->
                    Ledger.Change.money(
                        a.recipient(), "REWARD", a.net(), a.type() + " " + c.period(), false))
            .toList();
    if (!changes.isEmpty())
      ledger.post("SETTLE:" + text(batch, "businessKey"), "SETTLEMENT", id, "账期分红入账", changes);
    orders.insertAwards(result, null, id, c.ruleId());
    db.exec(
        "UPDATE settlement_batches SET status='POSTED',posted_at=CURRENT_TIMESTAMP WHERE"
            + " id=#{p.id}",
        map("id", id));
    if (c.cycle().equals("DAILY")) {
      var points = performance.points();
      var end = performance.metrics(points, LocalDateTime.parse(c.end()));
      var start = performance.metrics(points, LocalDateTime.parse(c.start()));
      for (var point : points) {
        String p = text(point, "id");
        BigDecimal increment = max0(end.get(p).small().subtract(start.get(p).small()));
        for (var offset :
            db.list(
                "SELECT * FROM performance_offsets WHERE point_id=#{p.id} AND remaining>0 ORDER BY"
                    + " created_at,id",
                map("id", p))) {
          BigDecimal used = increment.min(d(offset.get("remaining")));
          if (used.signum() <= 0) break;
          db.exec(
              "UPDATE performance_offsets SET remaining=remaining-#{p.used} WHERE id=#{p.id}",
              map("id", offset.get("id"), "used", used));
          increment = increment.subtract(used);
        }
        var s = end.get(p);
        var args =
            map(
                "point",
                p,
                "date",
                LocalDate.parse(c.period()),
                "team",
                s.team(),
                "small",
                s.small(),
                "own",
                s.own());
        if (db.count(
                "SELECT COUNT(*) n FROM performance_snapshots WHERE point_id=#{p.point} AND"
                    + " snapshot_date=#{p.date}",
                args)
            == 0)
          db.exec(
              "INSERT INTO performance_snapshots(point_id,snapshot_date,team,small,own)"
                  + " VALUES(#{p.point},#{p.date},#{p.team},#{p.small},#{p.own})",
              args);
      }
    }
    db.audit("SETTLEMENT_POST", id, batch, result, "按已保存快照入账");
    return detail(id);
  }
}
