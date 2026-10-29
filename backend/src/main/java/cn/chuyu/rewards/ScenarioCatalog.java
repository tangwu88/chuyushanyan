package cn.chuyu.rewards;

import static cn.chuyu.shared.Money.*;

import java.util.*;

public class ScenarioCatalog {
  public record Scenario(String id, String title, String description, RewardEngine.Result result) {}

  public static RewardEngine.Node node(
      String id,
      String parent,
      int grade,
      int director,
      String shop,
      String small,
      String repeat,
      boolean plan,
      Set<String> identities) {
    return new RewardEngine.Node(
        id,
        parent,
        grade,
        director,
        shop,
        true,
        ZERO,
        d("2000000"),
        d(small),
        d(repeat),
        ZERO,
        0,
        plan,
        identities,
        null,
        null);
  }

  public static List<Scenario> run(Rules r) {
    var e = new RewardEngine();
    List<Scenario> out = new ArrayList<>();
    var sales =
        List.of(
            node("A", null, 7, 0, "NONE", "0", "0", false, Set.of()),
            node("B", "A", 5, 0, "NONE", "0", "0", false, Set.of()),
            node("C", "B", 2, 0, "NONE", "0", "0", false, Set.of()),
            node("D", "C", 0, 0, "NONE", "0", "0", false, Set.of()));
    out.add(
        new Scenario(
            "sales",
            "1490元销售级差",
            "二星直推、五星级差、七星级差；统一按当前版本管理费计算",
            e.immediate(
                r,
                sales,
                new RewardEngine.Sale(
                    "CASE-SALES",
                    "D",
                    "",
                    "FIRST",
                    d("1490"),
                    5,
                    List.of(d(1), d(2), d(3), d(4), d(5)),
                    d(51),
                    d(".05")))));
    var peer =
        List.of(
            node("P1", null, 5, 0, "NONE", "0", "0", false, Set.of()),
            node("P2", "P1", 3, 0, "NONE", "0", "0", false, Set.of()),
            node("P3", "P2", 6, 0, "NONE", "0", "0", false, Set.of()),
            node("P4", "P3", 5, 0, "NONE", "0", "0", false, Set.of()),
            node("BUY", "P4", 0, 0, "NONE", "0", "0", false, Set.of()));
    out.add(
        new Scenario(
            "peer",
            "跨级穿透平级奖",
            "寻找上方最近同级，穿透中间不同等级",
            e.immediate(
                r,
                peer,
                new RewardEngine.Sale(
                    "CASE-PEER",
                    "BUY",
                    "",
                    "FIRST",
                    d("24000"),
                    1,
                    List.of(d(1), d(2), d(3), d(4), d(5)),
                    d(51),
                    d(".05")))));
    var repeat =
        List.of(
            node("R1", null, 7, 0, "BIG", "0", "0", false, Set.of()),
            node("R2", "R1", 2, 0, "NONE", "0", "0", false, Set.of()));
    out.add(
        new Scenario(
            "repeat-fixed",
            "复购按瓶级差与店补",
            "二星自购4瓶，七星按六星封顶，固定级差不发平级奖",
            e.immediate(
                r,
                repeat,
                new RewardEngine.Sale(
                    "CASE-REPEAT",
                    "R2",
                    "R1",
                    "REPEAT",
                    d("396"),
                    4,
                    List.of(d(1), d(2), d(3), d(4), d(5)),
                    d(51),
                    d(".05")))));
    var seven =
        List.of(
            node("S1", null, 7, 0, "NONE", "100000", "0", false, Set.of()),
            node("S2", null, 7, 0, "NONE", "200000", "0", false, Set.of()),
            node("S3", null, 7, 0, "NONE", "200000", "0", false, Set.of()));
    out.add(
        new Scenario(
            "seven-daily",
            "七星日分红",
            "100万报单，三位七星权重10万、20万、20万",
            e.periodic(r, seven, d("1000000"), ZERO, "DAILY", "演示日")));
    List<RewardEngine.Node> directors = new ArrayList<>();
    for (int grade = 1; grade <= 6; grade++) {
      directors.add(node("D" + grade + "A", null, 6, grade, "NONE", "50000", "0", false, Set.of()));
      directors.add(
          node("D" + grade + "B", null, 6, grade, "NONE", "150000", "0", false, Set.of()));
    }
    out.add(
        new Scenario(
            "directors",
            "六个董事等级分红",
            "一至五星平均+加权，六星纯平均",
            e.periodic(r, directors, d("2000000"), ZERO, "DAILY", "演示日")));
    var ex =
        List.of(
            new RewardEngine.Node(
                "EX1",
                null,
                6,
                1,
                "NONE",
                true,
                ZERO,
                d("1000000"),
                d("330000"),
                ZERO,
                d("300000"),
                5,
                false,
                Set.of(),
                null,
                null),
            node("EX2", null, 6, 1, "NONE", "670000", "0", false, Set.of()));
    out.add(
        new Scenario(
            "exright",
            "董事除权",
            "第二市场30万/原权重100万，5位同级；调整后的分子和分母同步重算",
            e.periodic(r, ex, d("5000000"), ZERO, "DAILY", "演示日")));
    var shops =
        List.of(
            node(
                "SHOP1",
                null,
                6,
                0,
                "SMALL",
                "0",
                "0",
                true,
                Set.of("A", "B", "C", "H_FUNDS", "H_REPEAT", "MALI", "H_COMPANY2")),
            node("SHOP2", null, 6, 0, "SMALL", "0", "0", true, Set.of("A", "BING")));
    out.add(
        new Scenario(
            "identities-daily",
            "小店与日结身份",
            "B店、C店及四类日结演示身份分别计算",
            e.periodic(r, shops, d("1000000"), d("300000"), "DAILY", "演示日")));
    out.add(
        new Scenario(
            "identities-monthly",
            "计划1+2与月结身份",
            "A店与计划2只发一次，丙店按配置月结",
            e.periodic(r, shops, d("1000000"), d("300000"), "MONTHLY", "演示月")));
    var monthly =
        List.of(
            node("M1", null, 7, 0, "NONE", "0", "20000000", false, Set.of()),
            node("M2", "M1", 7, 0, "NONE", "0", "50000", false, Set.of()),
            node("M3", null, 7, 0, "NONE", "0", "18000000", false, Set.of()));
    out.add(
        new Scenario(
            "seven-repeat",
            "七星复购月分红",
            "13档级差、同档平级与额外0.5%奖池",
            e.periodic(r, monthly, ZERO, d("38000000"), "MONTHLY_REPEAT", "演示月")));
    return out;
  }
}
