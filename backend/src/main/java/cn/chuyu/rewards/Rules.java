package cn.chuyu.rewards;

import static cn.chuyu.shared.BusinessException.require;
import static cn.chuyu.shared.Money.*;

import java.math.BigDecimal;
import java.util.*;

public class Rules {
  public String label = "已确认演示规则";
  public BigDecimal managementFee = d("0.10"), withdrawalFee = d("0.06"), tax = d("0");
  public int maxPoints = 13, directReferrals = 10, peerGenerations = 1;
  public BigDecimal peerRate = d("0.10"),
      shippingThreshold = d("10000"),
      smallShopThreshold = d("24000");
  public BigDecimal smallShopRate = d("0.01"),
      bigShopSubsidy = d("0.05"),
      smallShopSubsidy = d("0.01");
  public int smallShopMinimumGrade = 6;
  public boolean smallShopGradeLimit = true;
  public String smallShopCycle = "DAILY";
  public BigDecimal sevenDailyRate = d("0.04"),
      averageShare = d("0.5"),
      plan1Rate = d("0.01"),
      plan2Rate = d("0.02");
  public BigDecimal repeatTopThreshold = d("18000000"), repeatTopRate = d("0.005");
  public boolean repeatTopLineBonus = true;
  public String recoveryPoint = "RECOVERY";
  public List<BigDecimal> salesRates =
      new ArrayList<>(
          List.of(d(".05"), d(".10"), d(".20"), d(".25"), d(".30"), d(".35"), d(".40"), d(".45")));
  public List<BigDecimal> directorRates =
      new ArrayList<>(List.of(d(".035"), d(".025"), d(".015"), d(".01"), d(".01"), d(".005")));
  public List<BigDecimal> directorThresholds = new ArrayList<>(Collections.nCopies(5, d(".25")));
  public List<Integer> directorCounts = new ArrayList<>(Collections.nCopies(5, 3));
  public List<Tier> repeatTiers = new ArrayList<>();
  public Map<String, List<Grade>> gradeVersions = new LinkedHashMap<>();
  public Map<String, Identity> identities = new LinkedHashMap<>();
  public List<String> assumptions =
      new ArrayList<>(
          List.of(
              "所有演示奖金统一扣10%管理费；忽略需求中采用6%的冲突示例。",
              "复购分红表实际13档，采用2%至14%；现网14档另存参考。",
              "未给出比例的五类特殊身份使用可修改的演示比例，非正式业务结论。",
              "固定计划分红先从同类奖池预留，剩余额度由自动资格成员平均分配。",
              "复购月分红的同档平级奖按最近一代的级差毛奖计算；最高档额外池包含一条线上最高档成员。"));

  public record Tier(BigDecimal min, BigDecimal max, BigDecimal rate) {}

  public record Grade(int grade, BigDecimal team, BigDecimal small) {}

  public record Identity(
      String name, String basis, String cycle, int minimumGrade, BigDecimal rate, String source) {}

  public Rules() {
    String[] bounds = {
      "0",
      "5000",
      "10000",
      "200000",
      "400000",
      "800000",
      "1300000",
      "1800000",
      "2600000",
      "3600000",
      "5600000",
      "9600000",
      "18000000"
    };
    for (int i = 0; i < bounds.length; i++)
      repeatTiers.add(
          new Tier(
              d(bounds[i]),
              i + 1 < bounds.length ? d(bounds[i + 1]) : null,
              d(i + 2).movePointLeft(2)));
    gradeVersions.put(
        "v2",
        List.of(
            new Grade(3, d("24000"), d("6000")),
            new Grade(4, d("100000"), d("30000")),
            new Grade(5, d("300000"), d("100000")),
            new Grade(6, d("600000"), d("200000")),
            new Grade(7, d("1200000"), d("400000"))));
    gradeVersions.put(
        "v1",
        List.of(
            new Grade(3, d("24000"), d("6000")),
            new Grade(4, d("70000"), d("20000")),
            new Grade(5, d("150000"), d("50000")),
            new Grade(6, d("300000"), d("100000")),
            new Grade(7, d("600000"), d("200000"))));
    identities.put("A", new Identity("A店 / 计划2", "REPEAT", "MONTHLY", 6, d(".02"), "需求表；与计划2去重"));
    identities.put("B", new Identity("B店", "REPEAT", "DAILY", 0, d(".0222"), "需求表"));
    identities.put("C", new Identity("C店", "TOTAL", "DAILY", 0, d(".006"), "需求表"));
    identities.put("H_FUNDS", new Identity("韩总资金", "TOTAL", "DAILY", 0, d(".005"), "演示假设：需求未提供比例"));
    identities.put(
        "H_REPEAT", new Identity("韩总复购分润", "REPEAT", "DAILY", 0, d(".01"), "演示假设：需求未提供比例"));
    identities.put("BING", new Identity("丙店", "REPORT", "MONTHLY", 0, d(".005"), "演示假设：需求未提供比例"));
    identities.put("MALI", new Identity("马丽", "TOTAL", "DAILY", 0, d(".003"), "演示假设：需求未提供比例"));
    identities.put(
        "H_COMPANY2", new Identity("韩总公司2", "TOTAL", "DAILY", 0, d(".002"), "演示假设：需求未提供比例"));
  }

  public void validate() {
    for (BigDecimal rate :
        List.of(
            managementFee,
            withdrawalFee,
            tax,
            peerRate,
            sevenDailyRate,
            averageShare,
            plan1Rate,
            plan2Rate,
            repeatTopRate,
            smallShopRate,
            bigShopSubsidy,
            smallShopSubsidy))
      require(
          rate != null && rate.signum() >= 0 && rate.compareTo(BigDecimal.ONE) <= 0, "比例必须在0至1之间");
    require(withdrawalFee.add(tax).compareTo(BigDecimal.ONE) < 0, "提现手续费与税率之和必须小于100%");
    require(
        maxPoints >= 1
            && maxPoints <= 1000
            && directReferrals > 0
            && peerGenerations >= 0
            && peerGenerations <= 10,
        "人数或代数配置错误");
    require(
        shippingThreshold.signum() >= 0
            && smallShopThreshold.signum() >= 0
            && repeatTopThreshold.signum() >= 0,
        "金额门槛不能为负");
    require(
        salesRates.size() == 8
            && directorRates.size() == 6
            && directorThresholds.size() == 5
            && directorCounts.size() == 5,
        "等级参数数量不完整");
    BigDecimal previous = ZERO;
    for (BigDecimal r : salesRates) {
      require(r.compareTo(previous) >= 0 && r.compareTo(BigDecimal.ONE) <= 0, "销售奖比例必须递增");
      previous = r;
    }
    for (BigDecimal r : directorRates)
      require(r.signum() >= 0 && r.compareTo(BigDecimal.ONE) <= 0, "董事比例错误");
    for (int i = 0; i < 5; i++)
      require(
          directorThresholds.get(i).signum() >= 0
              && directorThresholds.get(i).compareTo(BigDecimal.ONE) <= 0
              && directorCounts.get(i) >= 1,
          "董事除权参数错误");
    require(!repeatTiers.isEmpty(), "复购档位不能为空");
    BigDecimal end = ZERO, lastRate = ZERO;
    for (int i = 0; i < repeatTiers.size(); i++) {
      Tier t = repeatTiers.get(i);
      require(t.min().compareTo(end) == 0, "档位必须从0开始连续，不能重叠或留空");
      require(
          t.rate().compareTo(lastRate) >= 0 && t.rate().compareTo(BigDecimal.ONE) <= 0,
          "档位比例必须递增且不超过100%");
      require(
          (i == repeatTiers.size() - 1 && t.max() == null)
              || (t.max() != null && t.max().compareTo(t.min()) > 0),
          "最后一档必须无上限，其余档必须有上限");
      if (t.max() != null) end = t.max();
      lastRate = t.rate();
    }
    for (String v : List.of("v1", "v2")) require(gradeVersions.containsKey(v), "缺少等级版本 " + v);
    for (var grades : gradeVersions.values()) {
      require(grades.size() == 5, "需配置三星至七星门槛");
      for (int i = 0; i < 5; i++)
        require(
            grades.get(i).grade() == i + 3
                && grades.get(i).team().signum() >= 0
                && grades.get(i).small().signum() >= 0,
            "等级门槛错误");
    }
    require(
        identities
            .keySet()
            .containsAll(
                List.of("A", "B", "C", "H_FUNDS", "H_REPEAT", "BING", "MALI", "H_COMPANY2")),
        "八类身份配置不完整");
    for (Identity i : identities.values())
      require(
          Set.of("REPORT", "REPEAT", "TOTAL").contains(i.basis())
              && Set.of("DAILY", "MONTHLY").contains(i.cycle())
              && i.rate().signum() >= 0
              && i.rate().compareTo(BigDecimal.ONE) <= 0
              && i.minimumGrade() >= 0
              && i.minimumGrade() <= 7,
          "特殊身份参数错误");
    Identity a = identities.get("A");
    require(
        a.rate().compareTo(plan2Rate) == 0
            && a.basis().equals("REPEAT")
            && a.cycle().equals("MONTHLY")
            && a.minimumGrade() == 6,
        "A店与计划2共用月结复购参数，比例需保持一致");
    require(Set.of("DAILY", "MONTHLY").contains(smallShopCycle), "小店结算周期错误");
  }

  public BigDecimal repeatRate(BigDecimal amount) {
    return repeatTiers.stream()
        .filter(
            t ->
                amount.compareTo(t.min()) >= 0
                    && (t.max() == null || amount.compareTo(t.max()) < 0))
        .findFirst()
        .orElseThrow()
        .rate();
  }
}
