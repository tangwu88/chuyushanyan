package cn.chuyu.reports;

import static cn.chuyu.shared.BusinessException.require;
import static cn.chuyu.shared.Json.*;
import static cn.chuyu.shared.Money.*;

import cn.chuyu.members.*;
import cn.chuyu.shared.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class QueryService {
  private final Store db;
  private final MemberService members;
  private final PerformanceService performance;

  public QueryService(Store db, MemberService members, PerformanceService performance) {
    this.db = db;
    this.members = members;
    this.performance = performance;
  }

  public Map<String, Object> page(String resource, Map<String, String> q) {
    return pageInternal(resource, q, false);
  }

  public Map<String, Object> exportPage(String resource, Map<String, String> q) {
    Actors.require("reports:export");
    return pageInternal(resource, q, true);
  }

  private Map<String, Object> pageInternal(
      String resource, Map<String, String> q, boolean exporting) {
    int page = parse(q.get("page"), 1),
        size = Math.min(exporting ? 5000 : 200, parse(q.get("size"), 20));
    require(page > 0 && page <= 100000 && size > 0, "分页参数错误");
    if (resource.equals("members")) {
      if (!Actors.member()) Actors.require("members:read");
      var rows =
          members.list().stream()
              .filter(
                  m ->
                      q.getOrDefault("search", "").isBlank()
                          || List.of(text(m, "id"), text(m, "phone"), text(m, "nickname"))
                              .contains(q.get("search")))
              .filter(
                  m ->
                      q.getOrDefault("start", "").isBlank()
                          || text(m, "createdAt").compareTo(q.get("start")) >= 0)
              .filter(
                  m ->
                      q.getOrDefault("end", "").isBlank()
                          || text(m, "createdAt").substring(0, 10).compareTo(q.get("end")) <= 0)
              .toList();
      int from = Math.min(rows.size(), (page - 1) * size);
      return map(
          "items",
          rows.subList(from, Math.min(rows.size(), from + size)),
          "total",
          rows.size(),
          "page",
          page,
          "size",
          size);
    }
    String table, scope = "", permission, select = "t.*";
    boolean pointScoped = false;
    switch (resource) {
      case "orders" -> {
        table = "shop_orders";
        permission = "orders:read";
        select =
            "t.id,t.point_id,t.shop_id,t.payer_id,t.product_id,t.order_type,t.quantity,t.unit_price,t.total,t.reward_paid,t.cash_paid,t.payment_status,t.activation_status,t.fulfilment_status,t.cancelled,t.pickup,t.rule_id,t.created_at,t.activated_at";
        if (Actors.member())
          scope =
              " AND (EXISTS(SELECT 1 FROM member_points m WHERE m.id=t.point_id AND"
                  + " m.owner_id=#{p.owner}) OR EXISTS(SELECT 1 FROM member_points m WHERE"
                  + " m.id=t.shop_id AND m.owner_id=#{p.owner}))";
      }
      case "ledger" -> {
        table = "ledger_entries";
        permission = "funds:read";
        pointScoped = true;
        select =
            "t.*,(SELECT x.origin_id FROM ledger_transactions x WHERE x.id=t.transaction_id)"
                + " origin_id,(SELECT x.kind FROM ledger_transactions x WHERE"
                + " x.id=t.transaction_id) business_kind";
      }
      case "awards" -> {
        table = "reward_awards";
        permission = "funds:read";
        if (Actors.member())
          scope =
              " AND EXISTS(SELECT 1 FROM member_points m WHERE m.id=t.recipient_id AND"
                  + " m.owner_id=#{p.owner})";
      }
      case "withdrawals" -> {
        table = "withdrawals";
        permission = "funds:read";
        pointScoped = true;
      }
      case "topups" -> {
        table = "topups";
        permission = "funds:read";
        if (Actors.member())
          scope =
              " AND EXISTS(SELECT 1 FROM member_points m WHERE m.id=t.shop_id AND"
                  + " m.owner_id=#{p.owner})";
      }
      case "shipments" -> {
        table = "fulfilment_applications";
        permission = "shipping:read";
        if (Actors.member())
          scope =
              " AND EXISTS(SELECT 1 FROM member_points m WHERE m.id=t.shop_id AND"
                  + " m.owner_id=#{p.owner})";
      }
      case "batches" -> {
        table = "settlement_batches";
        permission = "funds:read";
        select =
            "t.id,t.period_type,t.period,t.rule_id,t.status,t.gross,t.fee,t.net,t.created_at,t.posted_at";
      }
      case "audits" -> {
        table = "audit_logs";
        permission = "audit:read";
      }
      case "jobs" -> {
        table = "job_runs";
        permission = "audit:read";
      }
      case "users" -> {
        table = "app_users";
        permission = "system:write";
        select =
            "t.id,t.username,t.display_name,t.phone,t.role,t.department,t.active,t.must_change_password,t.created_at";
      }
      default -> throw new BusinessException(404, "未知查询");
    }
    if (!Actors.member()) Actors.require(permission);
    else require(!Set.of("batches", "audits", "jobs", "users").contains(resource), "没有此查询权限");
    if (Actors.member() && pointScoped)
      scope =
          " AND EXISTS(SELECT 1 FROM member_points m WHERE m.id=t.point_id AND"
              + " m.owner_id=#{p.owner})";
    Map<String, Object> args =
        map("owner", Actors.current().ownerId(), "limit", size, "offset", (page - 1) * size);
    String where = " WHERE 1=1" + scope;
    if (resource.equals("ledger") && !q.getOrDefault("kind", "").isBlank()) {
      where += " AND t.wallet_kind=#{p.kind}";
      args.put("kind", q.get("kind"));
    }
    if (resource.equals("ledger") && Set.of("IN", "OUT").contains(q.getOrDefault("direction", "")))
      where += q.get("direction").equals("IN") ? " AND t.delta>0" : " AND t.delta<0";
    if (!q.getOrDefault("pointId", "").isBlank()
        && Set.of("orders", "ledger", "withdrawals", "awards").contains(resource)) {
      where +=
          " AND t." + (resource.equals("awards") ? "recipient_id" : "point_id") + "=#{p.point}";
      args.put("point", q.get("pointId"));
    }
    if (!q.getOrDefault("shopId", "").isBlank()
        && Set.of("orders", "shipments", "topups").contains(resource)) {
      where += " AND t.shop_id=#{p.shop}";
      args.put("shop", q.get("shopId"));
    }
    if (!q.getOrDefault("status", "").isBlank()) {
      if (resource.equals("orders")) {
        String status = q.get("status");
        where +=
            switch (status) {
              case "CANCELLED" -> " AND t.cancelled=TRUE";
              case "UNPAID" -> " AND t.payment_status='UNPAID' AND t.cancelled=FALSE";
              case "PENDING" ->
                  " AND t.payment_status='PAID' AND t.activation_status='PENDING' AND"
                      + " t.cancelled=FALSE";
              case "ACTIVE" -> " AND t.activation_status='ACTIVE' AND t.cancelled=FALSE";
              default -> " AND 1=0";
            };
      } else if (Set.of("shipments", "topups", "withdrawals", "batches", "jobs")
          .contains(resource)) {
        where += " AND t.status=#{p.status}";
        args.put("status", q.get("status"));
      }
    }
    if (!q.getOrDefault("type", "").isBlank() && resource.equals("orders")) {
      if (q.get("type").equals("REPORT")) where += " AND t.order_type<>'REPEAT'";
      else {
        where += " AND t.order_type=#{p.type}";
        args.put("type", q.get("type"));
      }
    }
    if (!q.getOrDefault("search", "").isBlank()) {
      where += " AND t.id=#{p.search}";
      args.put("search", q.get("search"));
    }
    String dateColumn =
        resource.equals("orders") && q.getOrDefault("dateField", "").equals("activatedAt")
            ? "activated_at"
            : "created_at";
    if (!q.getOrDefault("start", "").isBlank()) {
      where += " AND t." + dateColumn + ">=#{p.start}";
      args.put("start", date(q.get("start")).atStartOfDay().toString());
    }
    if (!q.getOrDefault("end", "").isBlank()) {
      where += " AND t." + dateColumn + "<#{p.end}";
      args.put("end", date(q.get("end")).plusDays(1).atStartOfDay().toString());
    }
    long count = exporting ? -1 : db.count("SELECT COUNT(*) n FROM " + table + " t" + where, args);
    var rows =
        db.list(
            "SELECT "
                + select
                + " FROM "
                + table
                + " t"
                + where
                + " ORDER BY t.created_at DESC,t.id DESC LIMIT #{p.limit} OFFSET #{p.offset}",
            args);
    return map("items", rows, "total", count, "page", page, "size", size);
  }

  private int parse(String s, int fallback) {
    try {
      return s == null ? fallback : Integer.parseInt(s);
    } catch (Exception e) {
      throw new BusinessException("分页参数错误");
    }
  }

  private LocalDate date(String s) {
    try {
      return LocalDate.parse(s);
    } catch (Exception e) {
      throw new BusinessException("日期格式错误");
    }
  }

  public Map<String, Object> dashboard(Map<String, String> q) {
    Actors.require("reports:read");
    LocalDate start = date(q.getOrDefault("start", Store.today().withDayOfMonth(1).toString())),
        end = date(q.getOrDefault("end", Store.today().toString()));
    require(!start.isAfter(end) && start.plusYears(3).isAfter(end), "请选择不超过三年的有效日期区间");
    var p =
        map(
            "start",
            Timestamp.valueOf(start.atStartOfDay()),
            "end",
            Timestamp.valueOf(end.plusDays(1).atStartOfDay()));
    var totals =
        db.required(
            "SELECT COUNT(*) order_count,COALESCE(SUM(CASE WHEN order_type='REPEAT' THEN 0 ELSE"
                + " total END),0) report_total,COALESCE(SUM(CASE WHEN order_type='REPEAT' THEN"
                + " total ELSE 0 END),0) repeat_total FROM shop_orders WHERE"
                + " activation_status='ACTIVE' AND cancelled=FALSE AND activated_at>=#{p.start} AND"
                + " activated_at<#{p.end}",
            p);
    var awards =
        db.required(
            "SELECT COALESCE(SUM(gross),0) gross,COALESCE(SUM(fee),0) fee,COALESCE(SUM(net),0) net"
                + " FROM reward_awards WHERE reversed=FALSE AND created_at>=#{p.start} AND"
                + " created_at<#{p.end}",
            p);
    BigDecimal all = d(totals.get("reportTotal")).add(d(totals.get("repeatTotal")));
    totals.putAll(awards);
    totals.put(
        "payoutRatio",
        all.signum() == 0
            ? ZERO
            : d(awards.get("net")).divide(all, 6, java.math.RoundingMode.HALF_UP));
    totals.put(
        "memberCount",
        db.count(
            "SELECT COUNT(*) n FROM member_points WHERE id NOT IN ('SYSTEM','RECOVERY')",
            Map.of()));
    totals.put(
        "newMembers",
        db.count(
            "SELECT COUNT(*) n FROM member_points WHERE id NOT IN ('SYSTEM','RECOVERY') AND"
                + " created_at>=#{p.start} AND created_at<#{p.end}",
            p));
    totals.put(
        "pendingTopups",
        db.count("SELECT COUNT(*) n FROM topups WHERE status='PENDING'", Map.of()));
    totals.put(
        "pendingWithdrawals",
        db.count("SELECT COUNT(*) n FROM withdrawals WHERE status='PENDING'", Map.of()));
    totals.put(
        "pendingShipments",
        db.count(
            "SELECT COUNT(*) n FROM fulfilment_applications WHERE status='PENDING'", Map.of()));
    totals.put(
        "pendingActivations",
        db.count(
            "SELECT COUNT(*) n FROM shop_orders WHERE cancelled=FALSE AND payment_status='PAID' AND"
                + " activation_status='PENDING'",
            Map.of()));
    totals.put(
        "rewardBalance",
        db.required(
                "SELECT COALESCE(SUM(balance),0) amount FROM wallets WHERE kind='REWARD' AND"
                    + " point_id NOT IN ('SYSTEM','RECOVERY')",
                Map.of())
            .get("amount"));
    var byType =
        db.list(
            "SELECT award_type,COALESCE(SUM(gross),0) gross,COALESCE(SUM(fee),0)"
                + " fee,COALESCE(SUM(net),0) net FROM reward_awards WHERE reversed=FALSE AND"
                + " created_at>=#{p.start} AND created_at<#{p.end} GROUP BY award_type ORDER BY net"
                + " DESC",
            p);
    var daily =
        db.list(
            "SELECT CAST(activated_at AS DATE) day,COUNT(*) order_count,SUM(CASE WHEN"
                + " order_type='REPEAT' THEN 0 ELSE total END) report_total,SUM(CASE WHEN"
                + " order_type='REPEAT' THEN total ELSE 0 END) repeat_total FROM shop_orders WHERE"
                + " activation_status='ACTIVE' AND cancelled=FALSE AND activated_at>=#{p.start} AND"
                + " activated_at<#{p.end} GROUP BY CAST(activated_at AS DATE) ORDER BY day",
            p);
    return map(
        "totals",
        totals,
        "byType",
        byType,
        "daily",
        daily,
        "start",
        start.toString(),
        "end",
        end.toString(),
        "asOf",
        Store.now().toString(),
        "timezone",
        "Asia/Shanghai",
        "basis",
        "业绩按激活日期；奖金按入账日期；撤销及已冲回奖金排除，拨比=实发奖金/同期业绩");
  }

  public Map<String, Object> reconciliation() {
    Actors.require("funds:read");
    var unbalanced =
        db.list(
            "SELECT transaction_id,wallet_kind,SUM(delta) difference FROM ledger_entries GROUP BY"
                + " transaction_id,wallet_kind HAVING SUM(delta)<>0");
    var balances =
        db.list(
            "SELECT w.point_id,w.kind,w.balance,COALESCE(e.total,0) ledger_total FROM wallets w"
                + " LEFT JOIN (SELECT point_id,wallet_kind,SUM(delta) total FROM ledger_entries"
                + " GROUP BY point_id,wallet_kind) e ON e.point_id=w.point_id AND"
                + " e.wallet_kind=w.kind WHERE w.balance<>COALESCE(e.total,0)");
    return map(
        "balanced",
        unbalanced.isEmpty() && balances.isEmpty(),
        "transactionDifferences",
        unbalanced,
        "walletDifferences",
        balances);
  }
}
