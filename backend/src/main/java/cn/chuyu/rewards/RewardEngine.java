package cn.chuyu.rewards;

import static cn.chuyu.shared.BusinessException.require;
import static cn.chuyu.shared.Money.*;

import java.math.*;
import java.util.*;

/** Pure calculations. No SQL, clocks, wallet changes or network calls. */
public class RewardEngine {
  public record Node(
      String id,
      String parentId,
      int grade,
      int director,
      String shop,
      boolean active,
      BigDecimal own,
      BigDecimal team,
      BigDecimal small,
      BigDecimal repeat,
      BigDecimal secondMarket,
      int secondDirectors,
      boolean planEligible,
      Set<String> identities,
      BigDecimal fixedPlan1,
      BigDecimal fixedPlan2,
      BigDecimal salesRateOverride) {
    public Node(
        String id,
        String parentId,
        int grade,
        int director,
        String shop,
        boolean active,
        BigDecimal own,
        BigDecimal team,
        BigDecimal small,
        BigDecimal repeat,
        BigDecimal secondMarket,
        int secondDirectors,
        boolean planEligible,
        Set<String> identities,
        BigDecimal fixedPlan1,
        BigDecimal fixedPlan2) {
      this(
          id,
          parentId,
          grade,
          director,
          shop,
          active,
          own,
          team,
          small,
          repeat,
          secondMarket,
          secondDirectors,
          planEligible,
          identities,
          fixedPlan1,
          fixedPlan2,
          null);
    }
  }

  public record Sale(
      String id,
      String buyerId,
      String shopId,
      String type,
      BigDecimal amount,
      int quantity,
      List<BigDecimal> differential,
      BigDecimal subsidyOne,
      BigDecimal subsidyRate) {}

  public record Award(
      String type,
      String recipient,
      String origin,
      BigDecimal base,
      BigDecimal rate,
      BigDecimal gross,
      BigDecimal fee,
      BigDecimal net,
      String explanation) {}

  public record Result(List<Award> awards, BigDecimal gross, BigDecimal fee, BigDecimal net) {
    public static Result of(List<Award> awards) {
      return new Result(
          List.copyOf(awards),
          money(awards.stream().map(Award::gross).reduce(ZERO, BigDecimal::add)),
          money(awards.stream().map(Award::fee).reduce(ZERO, BigDecimal::add)),
          money(awards.stream().map(Award::net).reduce(ZERO, BigDecimal::add)));
    }
  }

  private static Map<String, Node> index(List<Node> nodes) {
    Map<String, Node> m = new LinkedHashMap<>();
    for (Node n : nodes) m.put(n.id(), n);
    return m;
  }

  private static List<Node> ancestors(String parent, Map<String, Node> nodes) {
    List<Node> result = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    while (parent != null && !parent.isBlank() && nodes.containsKey(parent)) {
      require(visited.add(parent), "推荐关系存在循环");
      Node n = nodes.get(parent);
      result.add(n);
      parent = n.parentId();
    }
    return result;
  }

  private static Award award(
      String type,
      Node n,
      String origin,
      BigDecimal base,
      BigDecimal rate,
      BigDecimal gross,
      Rules r,
      String why) {
    BigDecimal g = money(gross), fee = money(g.multiply(r.managementFee));
    return new Award(type, n.id(), origin, money(base), rate, g, fee, g.subtract(fee), why);
  }

  public Result immediate(Rules r, List<Node> nodes, Sale sale) {
    r.validate();
    Map<String, Node> ix = index(nodes);
    Node buyer = ix.get(sale.buyerId());
    require(buyer != null, "购买账户不存在");
    List<Award> out = new ArrayList<>();
    if (!sale.type().equals("REPEAT")) {
      BigDecimal best = ZERO;
      for (Node n : ancestors(buyer.parentId(), ix)) {
        if (!n.active()) continue;
        BigDecimal rate =
            n.salesRateOverride() == null ? r.salesRates.get(n.grade()) : n.salesRateOverride();
        require(rate.signum() >= 0 && rate.compareTo(BigDecimal.ONE) <= 0, "会员销售比例错误");
        if (rate.compareTo(best) > 0) {
          BigDecimal diff = rate.subtract(best), gross = sale.amount().multiply(diff);
          Award a =
              award(
                  "SALES",
                  n,
                  sale.id(),
                  sale.amount(),
                  diff,
                  gross,
                  r,
                  "销售级差：" + rate + " - " + best);
          out.add(a);
          best = rate;
          int generations = 0;
          for (Node up : ancestors(n.parentId(), ix)) {
            if (up.active()
                && up.grade() == n.grade()
                && up.grade() > 0
                && generations < r.peerGenerations) {
              out.add(
                  award(
                      "PEER",
                      up,
                      sale.id(),
                      a.gross(),
                      r.peerRate,
                      a.gross().multiply(r.peerRate),
                      r,
                      "紧缩穿透，第" + (++generations) + "代同级：" + n.id()));
            }
          }
        }
      }
    } else {
      BigDecimal best = ZERO;
      List<Node> chain = new ArrayList<>();
      chain.add(buyer);
      chain.addAll(ancestors(buyer.parentId(), ix));
      for (Node n : chain) {
        if (!n.active() || n.grade() < 2) continue;
        int level = Math.min(n.grade(), 6);
        BigDecimal fixed = sale.differential().get(level - 2);
        if (fixed.compareTo(best) > 0) {
          out.add(
              award(
                  "REPEAT_FIXED",
                  n,
                  sale.id(),
                  d(sale.quantity()),
                  fixed.subtract(best),
                  fixed.subtract(best).multiply(d(sale.quantity())),
                  r,
                  "复购固定级差，按瓶计算，七星按六星处理"));
          best = fixed;
        }
        if (level == 6) break;
      }
    }
    Node shop = ix.get(sale.shopId());
    if (shop != null && shop.active() && !shop.shop().equals("NONE")) {
      BigDecimal base = sale.amount(),
          rate = shop.shop().equals("BIG") ? r.bigShopSubsidy : r.smallShopSubsidy,
          gross = base.multiply(rate);
      if (sale.type().equals("REPEAT")) {
        if (buyer.grade() == 1) {
          base = d(sale.quantity());
          rate = sale.subsidyOne();
          gross = base.multiply(rate);
        } else {
          rate = sale.subsidyRate();
          gross = base.multiply(rate);
        }
      }
      out.add(award("SHOP_SUBSIDY", shop, sale.id(), base, rate, gross, r, "代办店铺补贴，可跨团队"));
    }
    return Result.of(out.stream().filter(a -> a.gross().signum() > 0).toList());
  }

  /** Deterministic largest-remainder allocation; the resulting cents always equal the pool. */
  public static Map<String, BigDecimal> allocate(
      BigDecimal total, Map<String, BigDecimal> weights) {
    Map<String, BigDecimal> out = new TreeMap<>();
    require(total.signum() >= 0, "奖池不能为负");
    if (weights.isEmpty()) return out;
    BigDecimal sum =
        weights.values().stream().map(cn.chuyu.shared.Money::max0).reduce(ZERO, BigDecimal::add);
    if (sum.signum() <= 0) {
      Map<String, BigDecimal> equal = new TreeMap<>();
      weights.keySet().forEach(k -> equal.put(k, BigDecimal.ONE));
      return allocate(total, equal);
    }
    long cents = money(total).movePointRight(2).longValueExact(), assigned = 0;
    Map<String, BigDecimal> remainders = new HashMap<>();
    for (String id : new TreeSet<>(weights.keySet())) {
      BigDecimal raw = d(cents).multiply(max0(weights.get(id))).divide(sum, 18, RoundingMode.DOWN);
      long floor = raw.longValue();
      out.put(id, BigDecimal.valueOf(floor, 2));
      assigned += floor;
      remainders.put(id, raw.subtract(d(floor)));
    }
    List<String> order = new ArrayList<>(out.keySet());
    order.sort(
        Comparator.<String, BigDecimal>comparing(remainders::get)
            .reversed()
            .thenComparing(String::compareTo));
    for (long i = 0; i < cents - assigned; i++) {
      String id = order.get((int) (i % order.size()));
      out.put(id, out.get(id).add(d(".01")));
    }
    return out;
  }

  private void pool(
      List<Award> out,
      String type,
      String period,
      BigDecimal basis,
      BigDecimal rate,
      List<Node> people,
      BigDecimal average,
      Map<String, BigDecimal> adjusted,
      Rules r) {
    if (people.isEmpty() || basis.signum() <= 0 || rate.signum() <= 0) return;
    BigDecimal total = money(basis.multiply(rate));
    Map<String, BigDecimal> weights = new TreeMap<>();
    BigDecimal sum =
        people.stream()
            .map(n -> max0(adjusted.getOrDefault(n.id(), n.small())))
            .reduce(ZERO, BigDecimal::add);
    for (Node n : people) {
      BigDecimal equal = BigDecimal.ONE.divide(d(people.size()), 18, RoundingMode.HALF_UP);
      BigDecimal weighted =
          sum.signum() == 0
              ? equal
              : max0(adjusted.getOrDefault(n.id(), n.small()))
                  .divide(sum, 18, RoundingMode.HALF_UP);
      weights.put(
          n.id(), equal.multiply(average).add(weighted.multiply(BigDecimal.ONE.subtract(average))));
    }
    var gross = allocate(total, weights);
    var fees = allocate(money(total.multiply(r.managementFee)), gross);
    for (Node n : people)
      out.add(
          new Award(
              type,
              n.id(),
              period,
              money(basis),
              rate,
              gross.get(n.id()),
              fees.get(n.id()),
              gross.get(n.id()).subtract(fees.get(n.id())),
              "奖池"
                  + total
                  + "；平均占比"
                  + average
                  + "；加权基数"
                  + adjusted.getOrDefault(n.id(), n.small())
                  + "；人数"
                  + people.size()));
  }

  private void plan(
      List<Award> out, Rules r, List<Node> nodes, BigDecimal basis, String period, boolean first) {
    var people = nodes.stream().filter(n -> n.active() && n.planEligible()).toList();
    if (people.isEmpty()) return;
    String type = first ? "PLAN1" : "PLAN2";
    BigDecimal rate = first ? r.plan1Rate : r.plan2Rate;
    BigDecimal total = money(basis.multiply(rate)), fixed = ZERO;
    List<Node> automatic = new ArrayList<>();
    for (Node n : people) {
      BigDecimal f = first ? n.fixedPlan1() : n.fixedPlan2();
      if (f == null) automatic.add(n);
      else {
        require(f.signum() >= 0, "固定分红不能为负");
        fixed = fixed.add(f);
        out.add(award(type, n, period, basis, rate, f, r, "固定毛分红覆盖自动计算"));
      }
    }
    require(fixed.compareTo(total) <= 0, "固定分红总额超过" + type + "奖池，请调整规则或固定金额");
    if (basis.signum() > 0)
      pool(
          out,
          type,
          period,
          basis,
          total.subtract(fixed).divide(basis, 18, RoundingMode.HALF_UP),
          automatic,
          BigDecimal.ONE,
          Map.of(),
          r);
  }

  public Result periodic(
      Rules r,
      List<Node> nodes,
      BigDecimal report,
      BigDecimal repeat,
      String cycle,
      String period) {
    r.validate();
    List<Award> out = new ArrayList<>();
    Map<String, Node> ix = index(nodes);
    if (cycle.equals("DAILY")) {
      pool(
          out,
          "SEVEN_DAILY",
          period,
          report,
          r.sevenDailyRate,
          nodes.stream().filter(n -> n.active() && n.grade() == 7).toList(),
          r.averageShare,
          Map.of(),
          r);
      for (int grade = 1; grade <= 6; grade++) {
        final int level = grade;
        var people = nodes.stream().filter(n -> n.active() && n.director() == level).toList();
        Map<String, BigDecimal> weights = new HashMap<>();
        BigDecimal originalSum = people.stream().map(Node::small).reduce(ZERO, BigDecimal::add);
        for (Node n : people) {
          BigDecimal weight = max0(n.small());
          if (level < 6
              && originalSum.signum() > 0
              && n.secondDirectors() >= r.directorCounts.get(level - 1)
              && n.secondMarket()
                      .divide(originalSum, 18, RoundingMode.HALF_UP)
                      .compareTo(r.directorThresholds.get(level - 1))
                  > 0)
            weight =
                max0(
                    weight
                        .subtract(n.secondMarket())
                        .add(
                            n.secondMarket()
                                .divide(d(n.secondDirectors()), 18, RoundingMode.HALF_UP)));
          weights.put(n.id(), weight);
        }
        pool(
            out,
            "DIRECTOR_" + level,
            period,
            report,
            r.directorRates.get(level - 1),
            people,
            level == 6 ? BigDecimal.ONE : r.averageShare,
            weights,
            r);
      }
    }
    if (cycle.equals(r.smallShopCycle))
      pool(
          out,
          "SMALL_SHOP",
          period,
          report,
          r.smallShopRate,
          nodes.stream()
              .filter(
                  n ->
                      n.active()
                          && n.shop().equals("SMALL")
                          && (!r.smallShopGradeLimit || n.grade() >= r.smallShopMinimumGrade))
              .toList(),
          BigDecimal.ONE,
          Map.of(),
          r);
    if (cycle.equals("MONTHLY")) {
      plan(out, r, nodes, report, period, true);
      if (r.identities.get("A").enabled()) plan(out, r, nodes, repeat, period, false);
    }
    if (cycle.equals("MONTHLY_REPEAT")) {
      var seven = nodes.stream().filter(n -> n.active() && n.grade() == 7).toList();
      Map<String, Award> raw = new HashMap<>();
      for (Node n : seven) {
        BigDecimal rate = r.repeatRate(n.repeat()), gross = n.repeat().multiply(rate);
        for (Node child : seven) {
          if (child.id().equals(n.id())) continue;
          var ups =
              ancestors(child.parentId(), ix).stream()
                  .filter(u -> u.active() && u.grade() == 7)
                  .toList();
          if (!ups.isEmpty() && ups.getFirst().id().equals(n.id()))
            gross = gross.subtract(child.repeat().multiply(rate.min(r.repeatRate(child.repeat()))));
        }
        var a =
            award(
                "SEVEN_REPEAT",
                n,
                period,
                n.repeat(),
                rate,
                max0(gross),
                r,
                "月度伞下复购级差，已扣除最近下级七星对应份额");
        out.add(a);
        raw.put(n.id(), a);
      }
      for (Node n : seven) {
        Award a = raw.get(n.id());
        int gen = 0;
        for (Node up : ancestors(n.parentId(), ix)) {
          if (up.active()
              && up.grade() == 7
              && r.repeatRate(up.repeat()).compareTo(r.repeatRate(n.repeat())) == 0
              && gen < r.peerGenerations)
            out.add(
                award(
                    "SEVEN_REPEAT_PEER",
                    up,
                    period,
                    a.gross(),
                    r.peerRate,
                    a.gross().multiply(r.peerRate),
                    r,
                    "复购分红同档平级，第" + (++gen) + "代：" + n.id()));
        }
      }
      var top =
          seven.stream()
              .filter(
                  n ->
                      n.repeat().compareTo(r.repeatTopThreshold) >= 0
                          || (r.repeatTopLineBonus
                              && ancestors(n.parentId(), ix).stream()
                                  .noneMatch(
                                      u ->
                                          u.active()
                                              && u.grade() == 7
                                              && r.repeatRate(u.repeat())
                                                      .compareTo(r.repeatRate(n.repeat()))
                                                  >= 0)))
              .toList();
      pool(out, "REPEAT_TOP", period, repeat, r.repeatTopRate, top, BigDecimal.ONE, Map.of(), r);
    }
    for (var e : r.identities.entrySet()) {
      if (e.getKey().equals("A")) continue;
      Rules.Identity rule = e.getValue();
      if (!rule.enabled() || !rule.cycle().equals(cycle)) continue;
      BigDecimal basis =
          switch (rule.basis()) {
            case "REPORT" -> report;
            case "REPEAT" -> repeat;
            default -> report.add(repeat);
          };
      pool(
          out,
          "IDENTITY_" + e.getKey(),
          period,
          basis,
          rule.effectiveRate(r, basis),
          nodes.stream()
              .filter(
                  n ->
                      n.active()
                          && n.grade() >= rule.minimumGrade()
                          && n.identities().contains(e.getKey()))
              .toList(),
          BigDecimal.ONE,
          Map.of(),
          r);
    }
    return Result.of(out.stream().filter(a -> a.gross().signum() > 0).toList());
  }

  public static BigDecimal smallIncrement(
      BigDecimal ownStart,
      List<BigDecimal> branchesStart,
      BigDecimal ownEnd,
      List<BigDecimal> branchesEnd) {
    return ownEnd
        .add(branchesEnd.stream().reduce(ZERO, BigDecimal::add))
        .subtract(branchesEnd.stream().max(BigDecimal::compareTo).orElse(ZERO))
        .subtract(
            ownStart
                .add(branchesStart.stream().reduce(ZERO, BigDecimal::add))
                .subtract(branchesStart.stream().max(BigDecimal::compareTo).orElse(ZERO)));
  }
}
