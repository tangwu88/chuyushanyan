package cn.chuyu.reports;

import static cn.chuyu.shared.BusinessException.require;
import static cn.chuyu.shared.Json.*;
import static cn.chuyu.shared.Money.*;

import cn.chuyu.members.PerformanceService;
import cn.chuyu.rewards.RuleService;
import cn.chuyu.shared.*;
import java.math.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AdvancedReports {
  public static final Set<String> TYPES =
      Set.of("rankings", "shop-stats", "shop-cash", "product-sales", "daily-stats");
  private final Store db;
  private final PerformanceService performance;
  private final RuleService rules;

  public AdvancedReports(Store db, PerformanceService performance, RuleService rules) {
    this.db = db;
    this.performance = performance;
    this.rules = rules;
  }

  public Map<String, Object> arguments(Map<String, String> q) {
    try {
      LocalDate
          start =
              LocalDate.parse(q.getOrDefault("start", Store.today().withDayOfMonth(1).toString())),
          end = LocalDate.parse(q.getOrDefault("end", Store.today().toString()));
      require(!start.isAfter(end) && start.plusYears(3).isAfter(end), "请选择不超过三年的有效日期区间");
      return map(
          "start",
          Timestamp.valueOf(start.atStartOfDay()),
          "end",
          Timestamp.valueOf(end.plusDays(1).atStartOfDay()),
          "startDate",
          start,
          "endDate",
          end);
    } catch (java.time.format.DateTimeParseException e) {
      throw new BusinessException("日期格式错误");
    }
  }

  public static BigDecimal ratio(Object numerator, Object denominator) {
    return d(denominator).signum() == 0
        ? ZERO
        : d(numerator).divide(d(denominator), 8, RoundingMode.HALF_UP);
  }

  public List<Map<String, Object>> rows(String type, Map<String, String> q) {
    Actors.require("reports:read");
    require(TYPES.contains(type), "统计类型不存在");
    Map<String, Object> p = arguments(q);
    String scope = Actors.orderScope("o", p);
    String filter =
        " WHERE o.activation_status='ACTIVE' AND o.cancelled=FALSE AND o.activated_at>=#{p.start}"
            + " AND o.activated_at<#{p.end}"
            + scope;
    List<Map<String, Object>> result;
    switch (type) {
      case "product-sales" ->
          result =
              db.list(
                  "SELECT CONCAT(o.product_id,':',o.order_type) id,o.product_id,p.name"
                      + " product_name,o.order_type,SUM(o.quantity) quantity,SUM(o.total)"
                      + " total,COUNT(*) order_count FROM shop_orders o JOIN products p ON"
                      + " p.id=o.product_id"
                      + filter
                      + " GROUP BY o.product_id,p.name,o.order_type ORDER BY total"
                      + " DESC,o.product_id",
                  p);
      case "shop-stats" -> {
        var sales =
            db.list(
                "SELECT o.shop_id,COALESCE(SUM(CASE WHEN o.order_type='REPEAT' THEN 0 ELSE o.total"
                    + " END),0) report_total,COALESCE(SUM(CASE WHEN o.order_type='REPEAT' THEN"
                    + " o.total ELSE 0 END),0) repeat_total,COUNT(*) order_count FROM shop_orders o"
                    + filter
                    + " GROUP BY o.shop_id",
                p);
        Map<String, Map<String, Object>> ix = new HashMap<>();
        sales.forEach(s -> ix.put(text(s, "shopId"), s));
        result = new ArrayList<>();
        for (var shop : performance.views())
          if (!text(shop, "shopType").equals("NONE") && Actors.canPoint(shop)) {
            var row =
                map(
                    "id",
                    shop.get("id"),
                    "nickname",
                    shop.get("nickname"),
                    "phone",
                    shop.get("phone"),
                    "shopType",
                    shop.get("shopType"),
                    "reportTotal",
                    ZERO,
                    "repeatTotal",
                    ZERO,
                    "orderCount",
                    0,
                    "rechargeBalance",
                    ((Map<?, ?>) shop.get("recharge")).get("balance"),
                    "rewardBalance",
                    ((Map<?, ?>) shop.get("reward")).get("balance"));
            row.putAll(ix.getOrDefault(text(shop, "id"), Map.of()));
            row.put("total", d(row.get("reportTotal")).add(d(row.get("repeatTotal"))));
            result.add(row);
          }
        result.sort(
            Comparator.comparing((Map<String, Object> a) -> d(a.get("total")))
                .reversed()
                .thenComparing(a -> text(a, "id")));
        for (int i = 0; i < result.size(); i++) result.get(i).put("rank", i + 1);
      }
      case "shop-cash" -> {
        var income =
            db.list(
                "SELECT shop_id,SUM(amount) amount FROM topups WHERE status='APPROVED' AND"
                    + " COALESCE(reviewed_at,created_at)>=#{p.start} AND"
                    + " COALESCE(reviewed_at,created_at)<#{p.end} GROUP BY shop_id",
                p);
        Map<String, BigDecimal> amounts = new HashMap<>();
        income.forEach(row -> amounts.put(text(row, "shopId"), d(row.get("amount"))));
        for (var row :
            db.list(
                "SELECT shop_id,SUM(cash_paid) amount FROM shop_orders WHERE payment_method='DEMO'"
                    + " AND payment_status='PAID' AND cancelled=FALSE AND paid_at>=#{p.start} AND"
                    + " paid_at<#{p.end} GROUP BY shop_id",
                p)) amounts.merge(text(row, "shopId"), d(row.get("amount")), BigDecimal::add);
        p.put("recovery", rules.current().recoveryPoint);
        var returned =
            db.list(
                "SELECT outgoing.point_id,SUM(incoming.delta) amount FROM ledger_transactions t"
                    + " JOIN ledger_entries incoming ON incoming.transaction_id=t.id AND"
                    + " incoming.point_id=#{p.recovery} AND incoming.wallet_kind='REWARD' AND"
                    + " incoming.delta>0 JOIN ledger_entries outgoing ON"
                    + " outgoing.transaction_id=t.id AND outgoing.wallet_kind='REWARD' AND"
                    + " outgoing.delta<0 WHERE t.kind='TRANSFER' AND t.created_at>=#{p.start} AND"
                    + " t.created_at<#{p.end} GROUP BY outgoing.point_id",
                p);
        Map<String, BigDecimal> deductions = new HashMap<>();
        returned.forEach(row -> deductions.put(text(row, "pointId"), d(row.get("amount"))));
        result = new ArrayList<>();
        for (var shop : performance.points())
          if (!text(shop, "shopType").equals("NONE") && Actors.canPoint(shop)) {
            BigDecimal topup = amounts.getOrDefault(text(shop, "id"), ZERO),
                back = deductions.getOrDefault(text(shop, "id"), ZERO);
            result.add(
                map(
                    "id",
                    shop.get("id"),
                    "nickname",
                    shop.get("nickname"),
                    "phone",
                    shop.get("phone"),
                    "rechargeTotal",
                    topup,
                    "companyReward",
                    back,
                    "cashReceivable",
                    topup.subtract(back),
                    "start",
                    q.getOrDefault("start", Store.today().withDayOfMonth(1).toString()),
                    "end",
                    q.getOrDefault("end", Store.today().toString())));
          }
      }
      case "daily-stats" -> {
        result = new ArrayList<>();
        Map<String, Map<String, Object>> days = new TreeMap<>();
        var sales =
            db.list(
                "SELECT CAST(o.activated_at AS DATE) AS `day`,COUNT(*) order_count,SUM(CASE WHEN"
                    + " o.order_type='REPEAT' THEN 0 ELSE o.total END) report_total,SUM(CASE WHEN"
                    + " o.order_type='REPEAT' THEN o.total ELSE 0 END) repeat_total FROM"
                    + " shop_orders o"
                    + filter
                    + " GROUP BY CAST(o.activated_at AS DATE)",
                p);
        sales.forEach(row -> days.put(text(row, "day"), row));
        String awardScope = Actors.sqlScope("a", "recipient_id", p);
        var awards =
            db.list(
                "SELECT CAST(a.created_at AS DATE) AS"
                    + " `day`,a.award_type,a.rule_id,o.order_type,SUM(a.net) amount FROM"
                    + " reward_awards a LEFT JOIN shop_orders o ON o.id=a.order_id WHERE"
                    + " a.reversed=FALSE AND a.created_at>=#{p.start} AND a.created_at<#{p.end}"
                    + awardScope
                    + " GROUP BY CAST(a.created_at AS DATE),a.award_type,a.rule_id,o.order_type",
                p);
        for (var award : awards) {
          String day = text(award, "day");
          var row =
              days.computeIfAbsent(
                  day,
                  k -> map("day", day, "reportTotal", ZERO, "repeatTotal", ZERO, "orderCount", 0));
          String kind =
              awardBasis(text(award, "awardType"), text(award, "orderType"), text(award, "ruleId"));
          row.put(kind, d(row.get(kind)).add(d(award.get("amount"))));
        }
        for (var row : days.values()) {
          row.put("id", row.get("day"));
          BigDecimal report = d(row.get("reportTotal")),
              repeat = d(row.get("repeatTotal")),
              reportAward = d(row.get("reportAward")),
              repeatAward = d(row.get("repeatAward")),
              totalAward = d(row.get("totalAward"));
          row.put("reportAward", reportAward);
          row.put("repeatAward", repeatAward);
          row.put("totalAward", totalAward);
          row.put("net", reportAward.add(repeatAward).add(totalAward));
          row.put("reportRatio", ratio(reportAward, report));
          row.put("repeatRatio", ratio(repeatAward, repeat));
          row.put("payoutRatio", ratio(row.get("net"), report.add(repeat)));
          result.add(row);
        }
      }
      default -> result = rankings(p);
    }
    String search = q.getOrDefault("search", "");
    if (!search.isBlank())
      result =
          result.stream()
              .filter(
                  row ->
                      List.of(
                              text(row, "id"),
                              text(row, "nickname"),
                              text(row, "phone"),
                              text(row, "productName"))
                          .contains(search))
              .toList();
    return result;
  }

  private String awardBasis(String type, String orderType, String ruleId) {
    if (type.startsWith("IDENTITY_")) {
      var rule = rules.get(ruleId).identities.get(type.substring(9));
      return rule == null
          ? "totalAward"
          : rule.basis().equals("REPORT")
              ? "reportAward"
              : rule.basis().equals("REPEAT") ? "repeatAward" : "totalAward";
    }
    if (type.equals("SHOP_SUBSIDY"))
      return orderType.equals("REPEAT") ? "repeatAward" : "reportAward";
    if (Set.of("REPEAT_FIXED", "SEVEN_REPEAT", "SEVEN_REPEAT_PEER", "REPEAT_TOP", "PLAN2")
        .contains(type)) return "repeatAward";
    return "reportAward";
  }

  private List<Map<String, Object>> rankings(Map<String, Object> p) {
    LocalDate day = (LocalDate) p.get("endDate");
    var end = day.plusDays(1).atStartOfDay();
    var points = performance.pointsAt(end);
    var close = performance.metrics(points, end);
    var dayOpen = performance.metrics(points, day.atStartOfDay());
    var weekOpen =
        performance.metrics(
            points, day.minusDays(day.getDayOfWeek().getValue() - 1).atStartOfDay());
    var monthOpen = performance.metrics(points, day.withDayOfMonth(1).atStartOfDay());
    var lastOpen = performance.metrics(points, day.minusMonths(1).withDayOfMonth(1).atStartOfDay());
    Map<String, Map<String, Object>> wallets = new HashMap<>();
    db.list("SELECT * FROM wallets WHERE kind='REWARD'")
        .forEach(w -> wallets.put(text(w, "pointId"), w));
    Map<String, BigDecimal> ownTodayReport = new HashMap<>(), ownTodayRepeat = new HashMap<>();
    for (var row :
        db.list(
            "SELECT point_id,order_type,SUM(total) amount FROM shop_orders WHERE"
                + " activation_status='ACTIVE' AND cancelled=FALSE AND activated_at>=#{p.start} AND"
                + " activated_at<#{p.end} GROUP BY point_id,order_type",
            map("start", Timestamp.valueOf(day.atStartOfDay()), "end", Timestamp.valueOf(end))))
      (text(row, "orderType").equals("REPEAT") ? ownTodayRepeat : ownTodayReport)
          .merge(text(row, "pointId"), d(row.get("amount")), BigDecimal::add);
    List<Map<String, Object>> out = new ArrayList<>();
    for (var point : points)
      if (Actors.canPoint(point)) {
        String id = text(point, "id");
        var c = close.get(id);
        BigDecimal smallDay = c.small().subtract(dayOpen.get(id).small()),
            smallWeek = c.small().subtract(weekOpen.get(id).small()),
            smallMonth = c.small().subtract(monthOpen.get(id).small());
        out.add(
            map(
                "id",
                id,
                "nickname",
                point.get("nickname"),
                "phone",
                point.get("phone"),
                "parentId",
                point.get("parentId"),
                "grade",
                point.get("grade"),
                "directorGrade",
                point.get("directorGrade"),
                "rewardBalance",
                wallets.getOrDefault(id, Map.of()).getOrDefault("balance", ZERO),
                "dayReport",
                ownTodayReport.getOrDefault(id, ZERO),
                "own",
                c.own(),
                "team",
                c.team(),
                "dayRepeat",
                ownTodayRepeat.getOrDefault(id, ZERO),
                "monthRepeat",
                c.repeat().subtract(monthOpen.get(id).repeat()),
                "lastMonthRepeat",
                monthOpen.get(id).repeat().subtract(lastOpen.get(id).repeat()),
                "smallWeek",
                smallWeek,
                "smallMonth",
                smallMonth,
                "smallDay",
                smallDay,
                "average",
                money(smallWeek.divide(d(day.getDayOfWeek().getValue()), 8, RoundingMode.HALF_UP)),
                "snapshotDate",
                day.toString()));
      }
    out.sort(
        Comparator.comparing((Map<String, Object> row) -> d(row.get("smallMonth")))
            .reversed()
            .thenComparing(row -> text(row, "id")));
    for (int i = 0; i < out.size(); i++) out.get(i).put("rank", i + 1);
    return out;
  }
}
