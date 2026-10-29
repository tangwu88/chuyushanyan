package cn.chuyu.members;

import static cn.chuyu.shared.BusinessException.require;
import static cn.chuyu.shared.Json.*;
import static cn.chuyu.shared.Money.*;

import cn.chuyu.rewards.*;
import cn.chuyu.shared.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PerformanceService {
  public record Stats(
      BigDecimal own,
      BigDecimal team,
      BigDecimal small,
      BigDecimal repeat,
      Map<String, BigDecimal> branches) {}

  private final Store db;
  private final RuleService rules;

  public PerformanceService(Store db, RuleService rules) {
    this.db = db;
    this.rules = rules;
  }

  public List<Map<String, Object>> points() {
    return db.list(
        "SELECT * FROM member_points WHERE id NOT IN ('SYSTEM','RECOVERY') ORDER BY"
            + " created_at,sequence_no,id");
  }

  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> pointsAt(LocalDateTime end) {
    return db
        .list(
            "SELECT h.snapshot_json FROM member_history h WHERE h.sequence_id IN (SELECT"
                + " MAX(sequence_id) FROM member_history WHERE valid_at<#{p.end} GROUP BY point_id)"
                + " ORDER BY h.point_id",
            map("end", Timestamp.valueOf(end)))
        .stream()
        .map(h -> (Map<String, Object>) read(text(h, "snapshotJson"), Map.class))
        .toList();
  }

  public boolean below(String id, String ancestor, Map<String, Map<String, Object>> ix) {
    Set<String> seen = new HashSet<>();
    while (id != null && !id.isBlank() && ix.containsKey(id)) {
      if (id.equals(ancestor)) return true;
      require(seen.add(id), "推荐关系存在循环");
      id = text(ix.get(id), "parentId");
    }
    return false;
  }

  private BigDecimal subtree(
      String id,
      Map<String, List<String>> children,
      Map<String, BigDecimal> own,
      Map<String, BigDecimal> memo,
      Set<String> visiting) {
    if (memo.containsKey(id)) return memo.get(id);
    require(visiting.add(id) && visiting.size() < 1000, "推荐关系循环或层级过深");
    BigDecimal sum = own.getOrDefault(id, ZERO);
    for (String child : children.getOrDefault(id, List.of()))
      sum = sum.add(subtree(child, children, own, memo, visiting));
    visiting.remove(id);
    memo.put(id, sum);
    return sum;
  }

  public Map<String, Stats> metrics(List<Map<String, Object>> points, LocalDateTime end) {
    var aggregates =
        db.list(
            "SELECT point_id,SUM(CASE WHEN order_type<>'REPEAT' THEN total ELSE 0 END)"
                + " report_total,SUM(CASE WHEN order_type='REPEAT' THEN total ELSE 0 END)"
                + " repeat_total FROM shop_orders WHERE activation_status='ACTIVE' AND"
                + " activated_at<#{p.end} AND (cancelled=FALSE OR cancelled_at>=#{p.end}) GROUP BY"
                + " point_id",
            map("end", Timestamp.valueOf(end)));
    Map<String, BigDecimal> own = new HashMap<>(), repeat = new HashMap<>();
    for (var a : aggregates) {
      own.put(text(a, "pointId"), d(a.get("reportTotal")));
      repeat.put(text(a, "pointId"), d(a.get("repeatTotal")));
    }
    Map<String, Map<String, Object>> ix = new HashMap<>();
    Map<String, List<String>> children = new HashMap<>(), owners = new HashMap<>();
    for (var p : points) {
      String id = text(p, "id");
      ix.put(id, p);
      children.computeIfAbsent(text(p, "parentId"), k -> new ArrayList<>()).add(id);
      owners.computeIfAbsent(text(p, "ownerId"), k -> new ArrayList<>()).add(id);
    }
    Map<String, BigDecimal> memo = new HashMap<>(), repeatMemo = new HashMap<>();
    Map<String, Stats> out = new LinkedHashMap<>();
    for (var p : points) {
      String id = text(p, "id");
      BigDecimal team = subtree(id, children, own, memo, new HashSet<>()),
          rep = subtree(id, children, repeat, repeatMemo, new HashSet<>());
      for (String sibling : owners.get(text(p, "ownerId")))
        if (!below(sibling, id, ix)) {
          team = team.add(own.getOrDefault(sibling, ZERO));
          rep = rep.add(repeat.getOrDefault(sibling, ZERO));
        }
      Map<String, BigDecimal> branches = new LinkedHashMap<>();
      for (String child : children.getOrDefault(id, List.of()))
        branches.put(child, memo.getOrDefault(child, ZERO));
      BigDecimal max = branches.values().stream().max(BigDecimal::compareTo).orElse(ZERO);
      out.put(
          id,
          new Stats(
              own.getOrDefault(id, ZERO).add(d(p.get("manualOwn"))),
              team.add(d(p.get("manualTeam"))),
              team.subtract(max).add(d(p.get("manualSmall"))),
              rep,
              branches));
    }
    return out;
  }

  public Map<String, Stats> currentMetrics() {
    return metrics(points(), LocalDateTime.now(Store.ZONE).plusSeconds(1));
  }

  public List<Map<String, Object>> views() {
    var points = points();
    var metrics = metrics(points, LocalDateTime.now(Store.ZONE).plusSeconds(1));
    Map<String, Map<String, Object>> wallets = new HashMap<>();
    for (var w : db.list("SELECT * FROM wallets"))
      wallets.put(text(w, "pointId") + ":" + text(w, "kind"), w);
    List<Map<String, Object>> out = new ArrayList<>();
    var qualified = qualifiedOwners(points, LocalDateTime.now(Store.ZONE).plusSeconds(1));
    for (var p : points) {
      var s = metrics.get(text(p, "id"));
      Map<String, Object> m = new LinkedHashMap<>(p);
      m.put("own", s.own());
      m.put("team", s.team());
      m.put("small", s.small());
      m.put("repeat", s.repeat());
      m.put("reward", wallets.get(text(p, "id") + ":REWARD"));
      m.put("recharge", wallets.get(text(p, "id") + ":RECHARGE"));
      m.put(
          "childCount",
          points.stream().filter(c -> text(c, "parentId").equals(text(p, "id"))).count());
      m.put(
          "planQualified",
          switch (text(p, "planOverride")) {
            case "YES" -> true;
            case "NO" -> false;
            default ->
                qualified.getOrDefault(text(p, "ownerId"), false) && integer(p.get("grade")) >= 6;
          });
      out.add(m);
    }
    return out;
  }

  private Map<String, Boolean> qualifiedOwners(
      List<Map<String, Object>> points, LocalDateTime end) {
    Map<String, String> owners = new HashMap<>();
    points.forEach(p -> owners.put(text(p, "id"), text(p, "ownerId")));
    Map<String, LocalDateTime> first = new HashMap<>();
    Map<String, BigDecimal> amounts = new HashMap<>();
    for (var order :
        db.list(
            "SELECT point_id,total,activated_at FROM shop_orders WHERE order_type<>'REPEAT' AND"
                + " activation_status='ACTIVE' AND activated_at<#{p.end} AND (cancelled=FALSE OR"
                + " cancelled_at>=#{p.end}) ORDER BY activated_at,id",
            map("end", Timestamp.valueOf(end)))) {
      String owner = owners.get(text(order, "pointId"));
      if (owner == null) continue;
      var at = LocalDateTime.parse(text(order, "activatedAt"));
      var start = first.computeIfAbsent(owner, k -> at);
      if (!at.isAfter(start.plusHours(72)))
        amounts.merge(owner, d(order.get("total")), BigDecimal::add);
    }
    Map<String, Boolean> result = new HashMap<>();
    amounts.forEach((owner, amount) -> result.put(owner, amount.compareTo(d("24000")) >= 0));
    return result;
  }

  public List<RewardEngine.Node> nodes(LocalDateTime start, LocalDateTime end) {
    var points = start == null ? points() : pointsAt(end);
    var close = metrics(points, end);
    var open = start == null ? Map.<String, Stats>of() : metrics(points, start);
    Set<String> currentlyFrozen = new HashSet<>();
    for (var current : points())
      if (!bool(current.get("active"))) currentlyFrozen.add(text(current, "id"));
    Map<String, Map<String, Object>> ix = new HashMap<>();
    points.forEach(p -> ix.put(text(p, "id"), p));
    var offsetRows =
        db.list(
            "SELECT point_id,SUM(remaining) amount FROM performance_offsets WHERE remaining>0 AND"
                + " created_at<#{p.end} GROUP BY point_id",
            map("end", Timestamp.valueOf(end)));
    Map<String, BigDecimal> offsets = new HashMap<>();
    offsetRows.forEach(p -> offsets.put(text(p, "pointId"), d(p.get("amount"))));
    Map<String, Boolean> qualified = qualifiedOwners(points, end);
    List<RewardEngine.Node> out = new ArrayList<>();
    for (var p : points) {
      String id = text(p, "id");
      Stats s = close.get(id), o = open.get(id);
      BigDecimal weight =
          start == null
              ? s.small()
              : max0(s.small().subtract(o.small()).subtract(offsets.getOrDefault(id, ZERO)));
      var branches =
          s.branches().entrySet().stream()
              .sorted(
                  Map.Entry.<String, BigDecimal>comparingByValue()
                      .reversed()
                      .thenComparing(Map.Entry::getKey))
              .toList();
      BigDecimal second = ZERO;
      int directors = 0;
      if (branches.size() > 1) {
        var b = branches.get(1);
        second =
            start == null
                ? b.getValue()
                : max0(b.getValue().subtract(o.branches().getOrDefault(b.getKey(), ZERO)))
                    .min(weight);
        directors =
            (int)
                points.stream()
                    .filter(
                        q ->
                            integer(q.get("directorGrade")) == integer(p.get("directorGrade"))
                                && below(text(q, "id"), b.getKey(), ix))
                    .count();
      }
      String owner = text(p, "ownerId");
      boolean plan = qualified.getOrDefault(owner, false);
      plan =
          switch (text(p, "planOverride")) {
            case "YES" -> true;
            case "NO" -> false;
            default -> plan && integer(p.get("grade")) >= 6;
          };
      Set<String> identities = new HashSet<>(Arrays.asList(text(p, "identities").split(",")));
      out.add(
          new RewardEngine.Node(
              id,
              text(p, "parentId"),
              integer(p.get("grade")),
              integer(p.get("directorGrade")),
              text(p, "shopType"),
              bool(p.get("active")) && bool(p.get("activated")) && !currentlyFrozen.contains(id),
              s.own(),
              s.team(),
              weight,
              start == null ? s.repeat() : max0(s.repeat().subtract(o.repeat())),
              second,
              directors,
              plan,
              identities,
              p.get("fixedPlan1") == null ? null : d(p.get("fixedPlan1")),
              p.get("fixedPlan2") == null ? null : d(p.get("fixedPlan2")),
              p.get("salesRateOverride") == null ? null : d(p.get("salesRateOverride"))));
    }
    return out;
  }

  public List<RewardEngine.Node> currentNodes() {
    return nodes(null, LocalDateTime.now(Store.ZONE).plusSeconds(1));
  }

  public int eligibleGrade(Map<String, Object> point) {
    var points = points();
    var stats =
        metrics(points, LocalDateTime.now(Store.ZONE).plusSeconds(1)).get(text(point, "id"));
    return eligibleGrade(point, stats, points, rules.current());
  }

  private int eligibleGrade(
      Map<String, Object> point, Stats stats, List<Map<String, Object>> points, Rules rule) {
    int grade =
        stats.own().compareTo(d("5960")) >= 0 ? 2 : stats.own().compareTo(d("1490")) >= 0 ? 1 : 0;
    long refs =
        points.stream()
            .filter(
                c ->
                    text(c, "parentId").equals(text(point, "id"))
                        && bool(c.get("isMain"))
                        && bool(c.get("active"))
                        && bool(c.get("activated"))
                        && !text(c, "ownerId").equals(text(point, "ownerId")))
            .count();
    if (refs >= rule.directReferrals) grade = Math.max(grade, 1);
    for (var threshold :
        rule.gradeVersions.getOrDefault(
            text(point, "gradeVersion"), rule.gradeVersions.get(rule.newMemberGradeVersion)))
      if (stats.team().compareTo(threshold.team()) >= 0
          && stats.small().compareTo(threshold.small()) >= 0) grade = threshold.grade();
    return grade;
  }

  public void refreshGrades(boolean allowDowngrade) {
    Rules r = rules.current();
    var points = points();
    var metrics = metrics(points, LocalDateTime.now(Store.ZONE).plusSeconds(1));
    Set<String> changed = new HashSet<>();
    for (var p : points) {
      if (!bool(p.get("activated")) || bool(p.get("emptyPoint"))) continue;
      Stats s = metrics.get(text(p, "id"));
      int grade = eligibleGrade(p, s, points, r);
      if (!allowDowngrade) grade = Math.max(grade, integer(p.get("grade")));
      if (grade != integer(p.get("grade"))) {
        db.exec(
            "UPDATE member_points SET grade=#{p.grade} WHERE id=#{p.id}",
            map("id", p.get("id"), "grade", grade));
        db.audit(
            "GRADE_AUTO", text(p, "id"), p.get("grade"), grade, allowDowngrade ? "撤单重算" : "业绩升级");
        p.put("grade", grade);
        changed.add(text(p, "id"));
      }
      if (bool(p.get("isMain")) && !text(p, "shopType").equals("BIG")) {
        BigDecimal ownerTotal =
            points.stream()
                .filter(q -> text(q, "ownerId").equals(text(p, "ownerId")))
                .map(q -> metrics.get(text(q, "id")).own())
                .reduce(ZERO, BigDecimal::add);
        String shop =
            ownerTotal.compareTo(r.smallShopThreshold) >= 0
                ? "SMALL"
                : allowDowngrade ? "NONE" : text(p, "shopType");
        if (!shop.equals(text(p, "shopType"))) {
          db.exec(
              "UPDATE member_points SET shop_type=#{p.shop} WHERE id=#{p.id}",
              map("id", p.get("id"), "shop", shop));
          changed.add(text(p, "id"));
        }
      }
    }
    Map<String, Map<String, Object>> ix = new HashMap<>();
    points.forEach(p -> ix.put(text(p, "id"), p));
    for (int pass = 0; pass < 7; pass++)
      for (var p : points) {
        if (!bool(p.get("activated"))) continue;
        var branches =
            points.stream().filter(c -> text(c, "parentId").equals(text(p, "id"))).toList();
        int target = 0;
        for (int level = 1; level <= 6; level++) {
          final int l = level;
          long count =
              branches.stream()
                  .filter(
                      branch ->
                          points.stream()
                              .anyMatch(
                                  q ->
                                      bool(q.get("active"))
                                          && below(text(q, "id"), text(branch, "id"), ix)
                                          && (l == 1
                                              ? integer(q.get("grade")) == 7
                                              : integer(q.get("directorGrade")) >= l - 1)))
                  .count();
          if (count >= r.directorMarketCounts.get(level - 1)) target = level;
        }
        if (!allowDowngrade) target = Math.max(target, integer(p.get("directorGrade")));
        if (target != integer(p.get("directorGrade"))) {
          db.exec(
              "UPDATE member_points SET director_grade=#{p.grade} WHERE id=#{p.id}",
              map("id", p.get("id"), "grade", target));
          db.audit("DIRECTOR_AUTO", text(p, "id"), p.get("directorGrade"), target, "按独立市场资格计算");
          p.put("directorGrade", target);
          changed.add(text(p, "id"));
        }
      }
    for (String id : changed) db.recordPoint(id, LocalDateTime.now(Store.ZONE));
  }
}
