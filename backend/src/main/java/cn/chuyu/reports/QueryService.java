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
  private final AdvancedReports reports;

  public QueryService(
      Store db, MemberService members, PerformanceService performance, AdvancedReports reports) {
    this.db = db;
    this.members = members;
    this.performance = performance;
    this.reports = reports;
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
    if (AdvancedReports.TYPES.contains(resource)) {
      var rows = reports.rows(resource, q);
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
    if (Set.of("members", "empty-points", "invited-members").contains(resource)) {
      if (!Actors.member()) Actors.require("members:read");
      var ownedParents =
          resource.equals("invited-members")
              ? performance.points().stream()
                  .filter(Actors::owns)
                  .map(p -> text(p, "id"))
                  .collect(java.util.stream.Collectors.toSet())
              : Set.<String>of();
      var source =
          resource.equals("invited-members")
              ? performance.views().stream()
                  .filter(p -> ownedParents.contains(text(p, "parentId")))
                  .toList()
              : members.list();
      var rows =
          source.stream()
              .filter(m -> !resource.equals("empty-points") || bool(m.get("emptyPoint")))
              .filter(m -> !Actors.member() || bool(m.get("active")))
              .filter(
                  m ->
                      q.getOrDefault("status", "").isBlank()
                          || (q.get("status").equals("ACTIVE")
                              ? bool(m.get("active"))
                              : !bool(m.get("active"))))
              .filter(
                  m ->
                      q.getOrDefault("grade", "").isBlank()
                          || text(m, "grade").equals(q.get("grade")))
              .filter(
                  m ->
                      q.getOrDefault("shopType", "").isBlank()
                          || text(m, "shopType").equals(q.get("shopType")))
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
    String table, scope = "", permission, select = "t.*", scopeColumn = "", base = "";
    boolean pointScoped = false;
    switch (resource) {
      case "orders" -> {
        table = "shop_orders";
        permission = "orders:read";
        scopeColumn = "point_id";
        select =
            "t.id,t.point_id,t.shop_id,t.payer_id,t.product_id,t.order_type,t.quantity,t.unit_price,t.total,t.reward_paid,t.cash_paid,t.payment_status,t.activation_status,t.fulfilment_status,t.cancelled,t.pickup,t.rule_id,t.created_at,t.activated_at,(SELECT"
                + " m.nickname FROM member_points m WHERE m.id=t.point_id) nickname,(SELECT m.phone"
                + " FROM member_points m WHERE m.id=t.point_id) phone,(SELECT p.name FROM products"
                + " p WHERE p.id=t.product_id) product_name,CASE WHEN t.cancelled=TRUE THEN 0 ELSE"
                + " t.quantity-(SELECT COALESCE(SUM(l.quantity),0) FROM fulfilment_lines l JOIN"
                + " fulfilment_applications a ON a.id=l.application_id WHERE l.order_id=t.id AND"
                + " l.cancelled=FALSE AND a.status NOT IN ('REJECTED','VOID')) END"
                + " available_quantity";
        if (Actors.member())
          scope =
              " AND (EXISTS(SELECT 1 FROM member_points m WHERE m.id=t.point_id AND"
                  + " m.owner_id=#{p.owner}) OR EXISTS(SELECT 1 FROM member_points m WHERE"
                  + " m.id=t.shop_id AND m.owner_id=#{p.owner}) OR EXISTS(SELECT 1 FROM"
                  + " member_points m WHERE m.id=t.payer_id AND m.owner_id=#{p.owner}))";
      }
      case "ledger" -> {
        table = "ledger_entries";
        permission = "funds:read";
        pointScoped = true;
        scopeColumn = "point_id";
        select =
            "t.*,(SELECT x.origin_id FROM ledger_transactions x WHERE x.id=t.transaction_id)"
                + " origin_id,(SELECT x.kind FROM ledger_transactions x WHERE"
                + " x.id=t.transaction_id) business_kind";
      }
      case "awards" -> {
        table = "reward_awards";
        permission = "funds:read";
        scopeColumn = "recipient_id";
        if (Actors.member())
          scope =
              " AND EXISTS(SELECT 1 FROM member_points m WHERE m.id=t.recipient_id AND"
                  + " m.owner_id=#{p.owner})";
      }
      case "withdrawals" -> {
        table = "withdrawals";
        permission = "funds:read";
        select =
            "t.*,(SELECT nickname FROM member_points m WHERE m.id=t.point_id) nickname,(SELECT"
                + " phone FROM member_points m WHERE m.id=t.point_id) phone,(SELECT display_name"
                + " FROM app_users u WHERE u.id=t.review_by) reviewer_name";
        pointScoped = true;
        scopeColumn = "point_id";
      }
      case "topups" -> {
        table = "topups";
        permission = "funds:read";
        select =
            "t.*,(SELECT balance FROM wallets w WHERE w.point_id=t.shop_id AND w.kind='RECHARGE')"
                + " balance";
        scopeColumn = "shop_id";
        base = bool(q.get("deleted")) ? " AND t.deleted=TRUE" : " AND t.deleted=FALSE";
        if (Actors.member())
          scope =
              " AND EXISTS(SELECT 1 FROM member_points m WHERE m.id=t.shop_id AND"
                  + " m.owner_id=#{p.owner})";
      }
      case "shipments" -> {
        table = "fulfilment_applications";
        permission = "shipping:read";
        select =
            "t.*,(SELECT COUNT(*) FROM fulfilment_lines l WHERE l.application_id=t.id AND"
                + " l.cancelled=TRUE) cancelled_lines";
        scopeColumn = "shop_id";
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
        select =
            "t.*,CASE WHEN t.actor_id='SCHEDULER' THEN '定时任务' ELSE COALESCE((SELECT u.display_name"
                + " FROM app_users u WHERE u.id=t.actor_id),t.actor_id) END actor_name";
        base = " AND t.deleted=FALSE";
      }
      case "jobs" -> {
        table = "job_runs";
        permission = "audit:read";
      }
      case "users" -> {
        table = "app_users";
        permission = "system:write";
        select =
            "t.id,t.username,t.display_name,t.phone,t.role,t.department,t.email,t.gender,t.post_code,t.active,t.must_change_password,t.locked_until,t.created_at";
        base = " AND t.role<>'MEMBER'";
      }
      case "products" -> {
        table = "products";
        permission = "orders:read";
        base = " AND t.deleted=FALSE";
      }
      case "referrals", "promotions", "director-promotions" -> {
        table = "audit_logs";
        permission = "members:read";
        scopeColumn = "target_id";
        base =
            switch (resource) {
              case "referrals" -> " AND t.action='REFERRAL_CHANGE'";
              case "promotions" -> " AND t.action IN ('GRADE_AUTO','GRADE_MANUAL','GRADE_SYNC')";
              default -> " AND t.action IN ('DIRECTOR_AUTO','DIRECTOR_MANUAL')";
            };
        select =
            "t.*,(SELECT nickname FROM member_points m WHERE m.id=t.target_id) nickname,(SELECT"
                + " phone FROM member_points m WHERE m.id=t.target_id) phone";
      }
      case "cancellations" -> {
        table = "member_cancellations";
        permission = "members:read";
        scopeColumn = "point_id";
        select =
            "t.*,(SELECT nickname FROM member_points m WHERE m.id=t.point_id) nickname,(SELECT"
                + " phone FROM member_points m WHERE m.id=t.point_id) phone";
      }
      case "sync-upgrades" -> {
        table = "sync_upgrades";
        permission = "members:read";
        scopeColumn = "target_point";
        if (Actors.member())
          scope =
              " AND EXISTS(SELECT 1 FROM member_points m WHERE m.id=t.from_point AND"
                  + " m.owner_id=#{p.owner})";
      }
      case "stock-transfers" -> {
        table = "ledger_transactions";
        permission = "funds:read";
        base = " AND t.kind='STOCK_TRANSFER'";
        select =
            "t.*,(SELECT e.point_id FROM ledger_entries e WHERE e.transaction_id=t.id AND"
                + " e.wallet_kind='RECHARGE' AND e.delta<0 LIMIT 1) from_id,(SELECT e.point_id FROM"
                + " ledger_entries e WHERE e.transaction_id=t.id AND e.wallet_kind='RECHARGE' AND"
                + " e.delta>0 LIMIT 1) to_id,(SELECT e.delta FROM ledger_entries e WHERE"
                + " e.transaction_id=t.id AND e.wallet_kind='RECHARGE' AND e.delta>0 LIMIT 1)"
                + " amount";
        if (Actors.member())
          scope =
              " AND EXISTS(SELECT 1 FROM ledger_entries e JOIN member_points m ON m.id=e.point_id"
                  + " WHERE e.transaction_id=t.id AND m.owner_id=#{p.owner})";
      }
      case "unused-recharge" -> {
        table = "member_points";
        permission = "funds:read";
        scopeColumn = "id";
        base = " AND t.shop_type<>'NONE'";
        select =
            "t.id,t.nickname,t.phone,t.shop_type,t.created_at,(SELECT balance FROM wallets w WHERE"
                + " w.point_id=t.id AND w.kind='RECHARGE') balance,(SELECT frozen FROM wallets w"
                + " WHERE w.point_id=t.id AND w.kind='RECHARGE') frozen,(SELECT restricted FROM"
                + " wallets w WHERE w.point_id=t.id AND w.kind='RECHARGE') restricted";
      }
      case "login-logs" -> {
        table = "login_logs";
        permission = "audit:read";
        base = " AND t.deleted=FALSE";
      }
      case "report-snapshots" -> {
        table = "report_snapshots";
        permission = "reports:read";
        select = "t.id,t.report_type,t.snapshot_date,t.created_at";
      }
      case "recharge-records" -> {
        table = "ledger_entries";
        permission = "funds:read";
        scopeColumn = "point_id";
        base = " AND t.wallet_kind='RECHARGE'";
        select =
            "t.*,(SELECT x.origin_id FROM ledger_transactions x WHERE x.id=t.transaction_id)"
                + " origin_id,(SELECT x.kind FROM ledger_transactions x WHERE"
                + " x.id=t.transaction_id) business_kind,(SELECT CASE WHEN o.cancelled=TRUE THEN"
                + " 'CANCELLED' ELSE o.fulfilment_status END FROM ledger_transactions tx JOIN"
                + " shop_orders o ON o.id=tx.origin_id WHERE tx.id=t.transaction_id)"
                + " order_fulfilment";
      }
      default -> throw new BusinessException(404, "未知查询");
    }
    if (!Actors.member()) Actors.require(permission);
    else
      require(
          Set.of(
                  "orders",
                  "ledger",
                  "awards",
                  "withdrawals",
                  "topups",
                  "shipments",
                  "sync-upgrades",
                  "stock-transfers")
              .contains(resource),
          "没有此查询权限");
    if (Actors.member() && pointScoped)
      scope =
          " AND EXISTS(SELECT 1 FROM member_points m WHERE m.id=t.point_id AND"
              + " m.owner_id=#{p.owner})";
    Map<String, Object> args =
        map("owner", Actors.current().ownerId(), "limit", size, "offset", (page - 1) * size);
    String where = " WHERE 1=1" + scope + base;
    if (resource.equals("orders") && bool(q.get("available")))
      where +=
          " AND ("
              + (bool(q.get("includeCancelled")) ? "t.cancelled=TRUE OR " : "")
              + "(t.activation_status='ACTIVE' AND t.cancelled=FALSE AND t.quantity>(SELECT"
              + " COALESCE(SUM(l.quantity),0) FROM fulfilment_lines l JOIN fulfilment_applications"
              + " a ON a.id=l.application_id WHERE l.order_id=t.id AND l.cancelled=FALSE AND"
              + " a.status NOT IN ('REJECTED','VOID'))))";
    if (!scopeColumn.isBlank())
      where +=
          resource.equals("orders")
              ? Actors.orderScope("t", args)
              : Actors.sqlScope("t", scopeColumn, args);
    if (!Actors.member() && !Actors.current().dataScope().equals("ALL") && scopeColumn.isBlank()) {
      if (resource.equals("stock-transfers"))
        where +=
            " AND EXISTS(SELECT 1 FROM ledger_entries scoped_entry WHERE"
                + " scoped_entry.transaction_id=t.id"
                + Actors.sqlScope("scoped_entry", "point_id", args)
                + ")";
      else
        require(
            !Set.of("batches", "audits", "jobs", "users", "login-logs", "report-snapshots")
                .contains(resource),
            "此查询需要全部数据权限");
    }
    if (Set.of("orders", "ledger", "topups", "recharge-records").contains(resource)
        && !bool(q.get("includeArchived"))) where += " AND t.archived=FALSE";
    if (Set.of("ledger", "recharge-records").contains(resource)
        && !q.getOrDefault("kind", "").isBlank()) {
      where += " AND t.wallet_kind=#{p.kind}";
      args.put("kind", q.get("kind"));
    }
    if (Set.of("ledger", "recharge-records").contains(resource)
        && Set.of("IN", "OUT").contains(q.getOrDefault("direction", "")))
      where += q.get("direction").equals("IN") ? " AND t.delta>0" : " AND t.delta<0";
    if (Set.of("ledger", "recharge-records").contains(resource)
        && !q.getOrDefault("businessKind", "").isBlank()) {
      where +=
          " AND EXISTS(SELECT 1 FROM ledger_transactions tx WHERE tx.id=t.transaction_id AND"
              + " tx.kind=#{p.businessKind})";
      args.put("businessKind", q.get("businessKind"));
    }
    if (!q.getOrDefault("pointId", "").isBlank()
        && Set.of("orders", "ledger", "withdrawals", "awards", "recharge-records")
            .contains(resource)) {
      where +=
          " AND t." + (resource.equals("awards") ? "recipient_id" : "point_id") + "=#{p.point}";
      args.put("point", q.get("pointId"));
    }
    if (resource.equals("orders") && !q.getOrDefault("payerId", "").isBlank()) {
      where += " AND t.payer_id=#{p.payer}";
      args.put("payer", q.get("payerId"));
    }
    if (!q.getOrDefault("shopId", "").isBlank()
        && Set.of("orders", "shipments", "topups").contains(resource)) {
      where += " AND t.shop_id=#{p.shop}";
      args.put("shop", q.get("shopId"));
    }
    if (!q.getOrDefault("status", "").isBlank()) {
      if (resource.equals("recharge-records")) {
        require(Set.of("WAITING", "SHIPPED").contains(q.get("status")), "发货状态错误");
        where +=
            " AND EXISTS(SELECT 1 FROM ledger_transactions tx JOIN shop_orders o ON"
                + " o.id=tx.origin_id WHERE tx.id=t.transaction_id AND o.cancelled=FALSE AND"
                + " o.activation_status='ACTIVE' AND "
                + (q.get("status").equals("SHIPPED")
                    ? "o.fulfilment_status='FULFILLED'"
                    : "o.fulfilment_status IN ('UNFULFILLED','APPLIED','PARTIAL')")
                + ")";
      } else if (resource.equals("orders")) {
        String status = q.get("status");
        where +=
            switch (status) {
              case "CANCELLED" -> " AND t.cancelled=TRUE";
              case "UNPAID" -> " AND t.payment_status='UNPAID' AND t.cancelled=FALSE";
              case "PENDING" ->
                  " AND t.payment_status='PAID' AND t.activation_status='PENDING' AND"
                      + " t.cancelled=FALSE";
              case "ACTIVE" ->
                  " AND t.activation_status='ACTIVE'"
                      + (bool(q.get("includeCancelled")) ? "" : " AND t.cancelled=FALSE");
              case "PARTIAL", "FULFILLED", "APPLIED", "UNFULFILLED" ->
                  " AND t.fulfilment_status=#{p.status} AND t.cancelled=FALSE";
              default -> " AND 1=0";
            };
        args.put("status", status);
      } else if (Set.of("shipments", "topups", "withdrawals", "batches", "jobs")
          .contains(resource)) {
        where += " AND t.status=#{p.status}";
        args.put("status", q.get("status"));
      } else if (resource.equals("sync-upgrades")) {
        where += " AND t.status=#{p.status}";
        args.put("status", q.get("status"));
      } else if (resource.equals("products")) {
        where += " AND t.active=#{p.active}";
        args.put("active", q.get("status").equals("ACTIVE"));
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
      if (resource.equals("orders")
          && db.one("SELECT id FROM shop_orders WHERE id=#{p.id}", map("id", q.get("search")))
              != null) {
        where += " AND t.id=#{p.search}";
      } else if (Set.of(
              "orders",
              "ledger",
              "recharge-records",
              "awards",
              "withdrawals",
              "topups",
              "shipments",
              "referrals",
              "promotions",
              "director-promotions",
              "cancellations")
          .contains(resource)) {
        String col = resource.equals("orders") ? "point_id" : scopeColumn;
        where +=
            " AND (t.id=#{p.search} OR EXISTS(SELECT 1 FROM member_points search_member WHERE"
                + " search_member.id=t."
                + col
                + " AND (search_member.id=#{p.search} OR search_member.nickname=#{p.search} OR"
                + " search_member.phone=#{p.search})))";
        if (Set.of("ledger", "recharge-records").contains(resource))
          where =
              where.substring(0, where.length() - 1)
                  + " OR t.transaction_id=#{p.search} OR EXISTS(SELECT 1 FROM ledger_transactions"
                  + " search_tx WHERE search_tx.id=t.transaction_id AND"
                  + " search_tx.origin_id=#{p.search}))";
      } else if (resource.equals("users"))
        where +=
            " AND (t.id=#{p.search} OR t.username=#{p.search} OR t.phone=#{p.search} OR"
                + " t.display_name=#{p.search})";
      else if (resource.equals("login-logs"))
        where += " AND (t.id=#{p.search} OR t.username=#{p.search} OR t.ip=#{p.search})";
      else if (resource.equals("products"))
        where += " AND (t.id=#{p.search} OR t.name=#{p.search})";
      else if (resource.equals("unused-recharge"))
        where += " AND (t.id=#{p.search} OR t.nickname=#{p.search} OR t.phone=#{p.search})";
      else where += " AND t.id=#{p.search}";
      args.put("search", q.get("search"));
    }
    String dateColumn =
        resource.equals("orders") && q.getOrDefault("dateField", "").equals("activatedAt")
            ? "activated_at"
            : resource.equals("jobs") ? "started_at" : "created_at";
    if (!q.getOrDefault("type", "").isBlank() && resource.equals("awards")) {
      where += " AND t.award_type=#{p.awardType}";
      args.put("awardType", q.get("type"));
    }
    if (!q.getOrDefault("type", "").isBlank() && resource.equals("audits")) {
      where += " AND t.action=#{p.action}";
      args.put("action", q.get("type"));
    }
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
                + " ORDER BY t."
                + dateColumn
                + (table.equals("ledger_entries") ? " DESC,t.entry_no DESC" : " DESC,t.id DESC")
                + " LIMIT #{p.limit} OFFSET #{p.offset}",
            args);
    if (Set.of("referrals", "promotions", "director-promotions").contains(resource))
      for (var row : rows) {
        row.put("before", read(text(row, "beforeJson"), Object.class));
        row.put("after", read(text(row, "afterJson"), Object.class));
      }
    if (resource.equals("cancellations"))
      for (var row : rows) {
        row.put("snapshot", read(text(row, "snapshotJson"), Map.class));
        row.remove("snapshotJson");
      }
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
    if (!Actors.current().dataScope().equals("ALL")) return scopedDashboard(q);
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
            "SELECT CAST(activated_at AS DATE) AS `day`,COUNT(*) order_count,SUM(CASE WHEN"
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

  private Map<String, Object> scopedDashboard(Map<String, String> q) {
    var p = reports.arguments(q);
    var daily = reports.rows("daily-stats", q);
    var points = members.list();
    BigDecimal report = ZERO, repeat = ZERO, net = ZERO;
    long orders = 0;
    for (var row : daily) {
      report = report.add(d(row.get("reportTotal")));
      repeat = repeat.add(d(row.get("repeatTotal")));
      net = net.add(d(row.get("net")));
      orders += ((Number) row.getOrDefault("orderCount", 0)).longValue();
    }
    String scope = Actors.sqlScope("a", "recipient_id", p);
    var byType =
        db.list(
            "SELECT a.award_type,SUM(a.gross) gross,SUM(a.fee) fee,SUM(a.net) net FROM"
                + " reward_awards a WHERE a.reversed=FALSE AND a.created_at>=#{p.start} AND"
                + " a.created_at<#{p.end}"
                + scope
                + " GROUP BY a.award_type ORDER BY net DESC",
            p);
    BigDecimal gross = ZERO, fee = ZERO, rewardBalance = ZERO;
    for (var row : byType) {
      gross = gross.add(d(row.get("gross")));
      fee = fee.add(d(row.get("fee")));
    }
    for (var point : points)
      rewardBalance = rewardBalance.add(d(((Map<?, ?>) point.get("reward")).get("balance")));
    LocalDateTime from = ((Timestamp) p.get("start")).toLocalDateTime(),
        until = ((Timestamp) p.get("end")).toLocalDateTime();
    var totals =
        map(
            "reportTotal",
            report,
            "repeatTotal",
            repeat,
            "net",
            net,
            "gross",
            gross,
            "fee",
            fee,
            "payoutRatio",
            AdvancedReports.ratio(net, report.add(repeat)),
            "orderCount",
            orders,
            "memberCount",
            points.size(),
            "newMembers",
            points.stream()
                .filter(
                    m -> {
                      LocalDateTime at =
                          LocalDateTime.parse(text(m, "createdAt").replace(' ', 'T'));
                      return !at.isBefore(from) && at.isBefore(until);
                    })
                .count(),
            "rewardBalance",
            rewardBalance);
    totals.put(
        "pendingTopups",
        db.count(
            "SELECT COUNT(*) FROM topups t WHERE t.status='PENDING' AND t.deleted=FALSE"
                + Actors.sqlScope("t", "shop_id", p),
            p));
    totals.put(
        "pendingWithdrawals",
        db.count(
            "SELECT COUNT(*) FROM withdrawals t WHERE t.status='PENDING'"
                + Actors.sqlScope("t", "point_id", p),
            p));
    totals.put(
        "pendingShipments",
        db.count(
            "SELECT COUNT(*) FROM fulfilment_applications t WHERE t.status='PENDING'"
                + Actors.sqlScope("t", "shop_id", p),
            p));
    totals.put(
        "pendingActivations",
        db.count(
            "SELECT COUNT(*) FROM shop_orders t WHERE t.activation_status='PENDING' AND"
                + " t.payment_status='PAID' AND t.cancelled=FALSE"
                + Actors.orderScope("t", p),
            p));
    return map(
        "totals",
        totals,
        "byType",
        byType,
        "daily",
        daily,
        "start",
        p.get("startDate").toString(),
        "end",
        p.get("endDate").toString(),
        "timezone",
        "Asia/Shanghai",
        "asOf",
        Store.now().toString());
  }

  public Map<String, Object> reconciliation() {
    Actors.requireGlobal("funds:read");
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
