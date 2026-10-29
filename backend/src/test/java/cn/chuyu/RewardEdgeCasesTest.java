package cn.chuyu;

import static cn.chuyu.shared.Money.*;
import static org.junit.jupiter.api.Assertions.*;

import cn.chuyu.rewards.*;
import cn.chuyu.shared.*;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RewardEdgeCasesTest {
  RewardEngine.Node node(
      String id,
      String parent,
      int grade,
      String small,
      Set<String> identities,
      BigDecimal override) {
    return new RewardEngine.Node(
        id,
        parent,
        grade,
        0,
        "NONE",
        true,
        ZERO,
        ZERO,
        d(small),
        ZERO,
        ZERO,
        0,
        false,
        identities,
        null,
        null,
        override);
  }

  void eq(String expected, BigDecimal actual) {
    assertEquals(0, d(expected).compareTo(actual), actual.toPlainString());
  }

  @Test
  void tinyPoolNeverProducesNegativeIndividualFees() {
    Rules rules = new Rules();
    rules.sevenDailyRate = BigDecimal.ONE;
    rules.averageShare = ZERO;
    rules.managementFee = d(".04");
    List<RewardEngine.Node> nodes = new ArrayList<>();
    String[] weights = {"1500", "1500", "900", "500", "500", "200"};
    for (int i = 0; i < weights.length; i++)
      nodes.add(node("P" + i, null, 7, weights[i], Set.of(), null));
    var result = new RewardEngine().periodic(rules, nodes, d(".26"), ZERO, "DAILY", "2026-10-01");
    eq(".26", result.gross());
    eq(".01", result.fee());
    eq(".25", result.net());
    assertTrue(
        result.awards().stream()
            .allMatch(a -> a.fee().signum() >= 0 && a.net().compareTo(a.gross()) <= 0));
  }

  @Test
  void negativeWeightsAreExcludedFromTheDenominator() {
    var result = RewardEngine.allocate(d("1"), Map.of("A", d("-2"), "B", d("3")));
    eq("0", result.get("A"));
    eq("1", result.get("B"));
    assertThrows(BusinessException.class, () -> RewardEngine.allocate(d("-1"), Map.of("A", d(1))));
  }

  @Test
  void memberSalesOverrideChangesTheActualDifferential() {
    var buyer = node("BUYER", "DIRECT", 0, "0", Set.of(), null);
    var direct = node("DIRECT", "UP", 2, "0", Set.of(), d(".30"));
    var upper = node("UP", null, 4, "0", Set.of(), null);
    var sale =
        new RewardEngine.Sale(
            "ORDER",
            "BUYER",
            "",
            "FIRST",
            d("1000"),
            1,
            List.of(d(1), d(2), d(3), d(4), d(5)),
            ZERO,
            ZERO);
    var result = new RewardEngine().immediate(new Rules(), List.of(buyer, direct, upper), sale);
    eq("270", result.net());
    assertEquals(1, result.awards().size());
    assertEquals("DIRECT", result.awards().getFirst().recipient());
  }

  @Test
  void residualIdentityUsesConfiguredCoefficientsAndCanBeDisabled() {
    Rules rules = new Rules();
    rules.identities.put(
        "EXTRA",
        new Rules.Identity(
            "额外身份",
            "REPEAT",
            "MONTHLY",
            0,
            ZERO,
            "测试来源",
            "RESIDUAL",
            d(".14"),
            d("1.1"),
            d("1.1"),
            "",
            true));
    var person = node("P", null, 0, "0", Set.of("EXTRA"), null);
    var result =
        new RewardEngine()
            .periodic(rules, List.of(person), ZERO, d("100000"), "MONTHLY", "2026-09");
    eq("11500", result.gross());
    eq("10350", result.net());
    rules.identities.put(
        "EXTRA",
        new Rules.Identity(
            "额外身份",
            "REPEAT",
            "MONTHLY",
            0,
            ZERO,
            "测试来源",
            "RESIDUAL",
            d(".14"),
            d("1.1"),
            d("1.1"),
            "",
            false));
    assertTrue(
        new RewardEngine()
            .periodic(rules, List.of(person), ZERO, d("100000"), "MONTHLY", "2026-09")
            .awards()
            .isEmpty());
  }

  @Test
  void residualIdentityHasAZeroFloor() {
    Rules rules = new Rules();
    rules.identities.put(
        "EXTRA",
        new Rules.Identity(
            "额外身份",
            "REPEAT",
            "MONTHLY",
            0,
            ZERO,
            "测试来源",
            "RESIDUAL",
            d(".02"),
            d(1),
            d(2),
            "",
            true));
    assertTrue(
        new RewardEngine()
            .periodic(
                rules,
                List.of(node("P", null, 0, "0", Set.of("EXTRA"), null)),
                ZERO,
                d("100000"),
                "MONTHLY",
                "2026-09")
            .awards()
            .isEmpty());
  }

  @Test
  void existingSixFieldIdentitySnapshotsRemainEnabled() {
    var identity =
        Json.read(
            "{\"name\":\"历史身份\",\"basis\":\"TOTAL\",\"cycle\":\"DAILY\",\"minimumGrade\":0,\"rate\":0.01,\"source\":\"历史快照\"}",
            Rules.Identity.class);
    assertTrue(identity.enabled());
    assertEquals("RATE", identity.mode());
    eq(".01", identity.effectiveRate(new Rules(), d(100)));
  }

  @Test
  void invalidGradeVersionsAndMissingRatesFailWithBusinessMessages() {
    Rules rules = new Rules();
    rules.managementFee = null;
    assertThrows(BusinessException.class, rules::validate);
    Rules wrong = new Rules();
    wrong.gradeVersions.put(
        "bad",
        List.of(
            new Rules.Grade(3, d(100), d(10)),
            new Rules.Grade(4, d(50), d(20)),
            new Rules.Grade(5, d(300), d(30)),
            new Rules.Grade(6, d(400), d(40)),
            new Rules.Grade(7, d(500), d(50))));
    assertThrows(BusinessException.class, wrong::validate);
  }

  @Test
  void incompleteRuleRowsReturnBusinessErrorsInsteadOfNullPointerFailures() {
    Rules rates = new Rules();
    rates.salesRates.set(0, null);
    assertThrows(BusinessException.class, rates::validate);
    Rules tiers = new Rules();
    tiers.repeatTiers.set(0, null);
    assertThrows(BusinessException.class, tiers::validate);
    Rules identities = new Rules();
    identities.identities.put("EXTRA", new Rules.Identity(null, null, "DAILY", 0, null, "测试"));
    assertThrows(BusinessException.class, identities::validate);
    Rules grades = new Rules();
    grades.gradeVersions.put("extra", null);
    assertThrows(BusinessException.class, grades::validate);
  }
}
