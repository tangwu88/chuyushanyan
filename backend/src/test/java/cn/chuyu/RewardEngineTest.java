package cn.chuyu;

import static cn.chuyu.rewards.ScenarioCatalog.node;
import static cn.chuyu.shared.Money.*;
import static org.junit.jupiter.api.Assertions.*;

import cn.chuyu.rewards.*;
import cn.chuyu.shared.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RewardEngineTest {
  final Rules rules = new Rules();
  final RewardEngine engine = new RewardEngine();

  void eq(String expected, BigDecimal actual) {
    assertEquals(0, d(expected).compareTo(actual), actual.toPlainString());
  }

  ScenarioCatalog.Scenario scenario(String id) {
    return ScenarioCatalog.run(rules).stream()
        .filter(s -> s.id().equals(id))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void goldenSalesAndFees() {
    var r = scenario("sales").result();
    eq("603.45", r.net());
    eq("670.50", r.gross());
    eq("67.05", r.fee());
    eq("268.20", r.awards().get(0).net());
    eq("201.15", r.awards().get(1).net());
    eq("134.10", r.awards().get(2).net());
  }

  @Test
  void peerCompressesAcrossDifferentRanks() {
    var a =
        scenario("peer").result().awards().stream().filter(x -> x.type().equals("PEER")).toList();
    assertEquals(1, a.size());
    assertEquals("P1", a.getFirst().recipient());
    eq("756", a.getFirst().net());
  }

  @Test
  void fixedBottleDifferentialCapsAtSixWithoutPeer() {
    var a =
        scenario("repeat-fixed").result().awards().stream()
            .filter(x -> x.type().equals("REPEAT_FIXED"))
            .toList();
    assertEquals(2, a.size());
    eq("3.60", a.get(0).net());
    eq("14.40", a.get(1).net());
    assertTrue(
        scenario("repeat-fixed").result().awards().stream()
            .noneMatch(x -> x.type().equals("PEER")));
  }

  @Test
  void sevenDailyGoldenPool() {
    var a = scenario("seven-daily").result();
    eq("36000", a.net());
    eq("9600", a.awards().get(0).net());
    eq("13200", a.awards().get(1).net());
    eq("13200", a.awards().get(2).net());
  }

  @Test
  void sixDirectorLevelsAndExrightConservePools() {
    assertEquals(12, scenario("directors").result().awards().size());
    var ex = scenario("exright").result();
    eq("157500", ex.net());
    eq("48700.66", ex.awards().getFirst().net());
    var six =
        scenario("directors").result().awards().stream()
            .filter(a -> a.type().equals("DIRECTOR_6"))
            .toList();
    eq("4500", six.get(0).net());
    eq("4500", six.get(1).net());
  }

  @ParameterizedTest
  @CsvSource({
    "0,.02",
    "4999.99,.02",
    "5000,.03",
    "9999.99,.03",
    "10000,.04",
    "199999.99,.04",
    "200000,.05",
    "399999.99,.05",
    "400000,.06",
    "799999.99,.06",
    "800000,.07",
    "1299999.99,.07",
    "1300000,.08",
    "1799999.99,.08",
    "1800000,.09",
    "2599999.99,.09",
    "2600000,.10",
    "3599999.99,.10",
    "3600000,.11",
    "5599999.99,.11",
    "5600000,.12",
    "9599999.99,.12",
    "9600000,.13",
    "17999999.99,.13",
    "18000000,.14",
    "999999999,.14"
  })
  void allThirteenTierBoundaries(String amount, String expected) {
    assertEquals(13, rules.repeatTiers.size());
    eq(expected, rules.repeatRate(d(amount)));
  }

  @ParameterizedTest
  @CsvSource({
    "100000,100000,20000",
    "120000,100000,20000",
    "120000,110000,30000",
    "120000,140000,40000"
  })
  void smallMarketFourReferenceCases(String a, String b, String expected) {
    eq(
        expected,
        RewardEngine.smallIncrement(
            ZERO, List.of(d("80000"), d("100000")), ZERO, List.of(d(a), d(b))));
  }

  @Test
  void allSpecialIdentitiesAndAPlanDedup() {
    Set<String> found = new HashSet<>();
    for (var s : ScenarioCatalog.run(rules)) for (var a : s.result().awards()) found.add(a.type());
    for (String type :
        List.of(
            "SALES",
            "PEER",
            "REPEAT_FIXED",
            "SHOP_SUBSIDY",
            "SEVEN_DAILY",
            "SMALL_SHOP",
            "PLAN1",
            "PLAN2",
            "SEVEN_REPEAT",
            "REPEAT_TOP",
            "IDENTITY_B",
            "IDENTITY_C",
            "IDENTITY_H_FUNDS",
            "IDENTITY_H_REPEAT",
            "IDENTITY_BING",
            "IDENTITY_MALI",
            "IDENTITY_H_COMPANY2")) assertTrue(found.contains(type), type);
    var month = scenario("identities-monthly").result();
    assertEquals(2, month.awards().stream().filter(a -> a.type().equals("PLAN2")).count());
    assertTrue(month.awards().stream().noneMatch(a -> a.type().equals("IDENTITY_A")));
  }

  @Test
  void monthlyPeerPaysNearestSameTierOnly() {
    var nodes =
        List.of(
            node("M1", null, 7, 0, "NONE", "0", "80000", false, Set.of()),
            node("M2", "M1", 6, 0, "NONE", "0", "0", false, Set.of()),
            node("M3", "M2", 7, 0, "NONE", "0", "50000", false, Set.of()));
    var result = engine.periodic(rules, nodes, ZERO, d("80000"), "MONTHLY_REPEAT", "2026-09");
    var peers = result.awards().stream().filter(a -> a.type().equals("SEVEN_REPEAT_PEER")).toList();
    assertEquals(1, peers.size());
    assertEquals("M1", peers.getFirst().recipient());
    eq("180", peers.getFirst().net());
  }

  @Test
  void zeroParticipantsAndWeightsAreSafe() {
    eq("0", engine.periodic(rules, List.of(), d("100"), d("100"), "DAILY", "test").net());
    var n =
        List.of(
            node("X", null, 7, 0, "NONE", "0", "0", false, Set.of()),
            node("Y", null, 7, 0, "NONE", "0", "0", false, Set.of()));
    var result = engine.periodic(rules, n, d("1000"), ZERO, "DAILY", "test");
    eq("18", result.awards().get(0).net());
    eq("36", result.net());
  }

  @Test
  void frozenNodesDoNotReceiveAndSalesPassesThrough() {
    var frozen =
        new RewardEngine.Node(
            "F", "UP", 5, 0, "NONE", false, ZERO, ZERO, ZERO, ZERO, ZERO, 0, false, Set.of(), null,
            null);
    var nodes =
        List.of(
            node("UP", null, 7, 0, "NONE", "0", "0", false, Set.of()),
            frozen,
            node("BUY", "F", 0, 0, "NONE", "0", "0", false, Set.of()));
    var result =
        engine.immediate(
            rules,
            nodes,
            new RewardEngine.Sale(
                "test",
                "BUY",
                "",
                "FIRST",
                d("100"),
                1,
                List.of(d(1), d(2), d(3), d(4), d(5)),
                d(51),
                d(".05")));
    assertEquals(1, result.awards().size());
    assertEquals("UP", result.awards().getFirst().recipient());
    eq("40.50", result.net());
  }

  @Test
  void poolsAllocateEveryCentDeterministically() {
    var a = RewardEngine.allocate(d("1.00"), Map.of("B", d(1), "A", d(1), "C", d(1)));
    eq(".34", a.get("A"));
    eq("1", a.values().stream().reduce(ZERO, BigDecimal::add));
    for (int cents = 1; cents < 200; cents++) {
      var b =
          RewardEngine.allocate(
              BigDecimal.valueOf(cents, 2), Map.of("A", d("17"), "B", d("123"), "C", d("0.01")));
      eq(
          BigDecimal.valueOf(cents, 2).toPlainString(),
          b.values().stream().reduce(ZERO, BigDecimal::add));
    }
  }

  @Test
  void invalidGapOverlapAndFeeCannotPublish() {
    rules.repeatTiers.set(1, new Rules.Tier(d("5001"), d("10000"), d(".03")));
    assertThrows(BusinessException.class, rules::validate);
    var r = new Rules();
    r.withdrawalFee = d(".8");
    r.tax = d(".3");
    assertThrows(BusinessException.class, r::validate);
  }

  @Test
  void cyclesRejected() {
    var nodes =
        List.of(
            node("A", "B", 7, 0, "NONE", "0", "0", false, Set.of()),
            node("B", "A", 5, 0, "NONE", "0", "0", false, Set.of()),
            node("BUY", "B", 0, 0, "NONE", "0", "0", false, Set.of()));
    assertThrows(
        BusinessException.class,
        () ->
            engine.immediate(
                rules,
                nodes,
                new RewardEngine.Sale(
                    "x",
                    "BUY",
                    "",
                    "FIRST",
                    d("100"),
                    1,
                    List.of(d(1), d(2), d(3), d(4), d(5)),
                    d(51),
                    d(".05"))));
  }

  @Test
  void ruleSerializationRoundTripsAndFixedOverridesBudget() {
    var r = Json.read(Json.write(rules), Rules.class);
    r.validate();
    eq(".1", r.managementFee);
    var n =
        new RewardEngine.Node(
            "P",
            null,
            6,
            0,
            "NONE",
            true,
            ZERO,
            ZERO,
            ZERO,
            ZERO,
            ZERO,
            0,
            true,
            Set.of("A"),
            d("3000"),
            null);
    var auto = node("AUTO", null, 6, 0, "NONE", "0", "0", true, Set.of("A"));
    var result =
        engine.periodic(rules, List.of(n, auto), d("1000000"), d("100000"), "MONTHLY", "test");
    var awards = result.awards().stream().filter(a -> a.type().equals("PLAN1")).toList();
    eq("2700", awards.get(0).net());
    eq("6300", awards.get(1).net());
  }

  @Test
  void historicalMapSnapshotsKeepDecimalPrecision() {
    var snapshot = Json.read("{\"amount\":9007199254740993.01}", Map.class);
    assertInstanceOf(BigDecimal.class, snapshot.get("amount"));
    eq("9007199254740993.01", d(snapshot.get("amount")));
  }

  @Test
  void xlsxKeepsPhoneZerosAndDoesNotExecuteUserFormulas() throws Exception {
    var buffer = new java.io.ByteArrayOutputStream();
    cn.chuyu.reports.StreamXlsx.write(
        List.of(Json.map("phone", "00123456", "total", d("1490.00"), "description", "=1+1")),
        buffer);
    String sheet = "";
    try (var zip =
        new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(buffer.toByteArray()))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
        if (entry.getName().endsWith("sheet1.xml"))
          sheet = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    assertTrue(sheet.contains(">00123456<"));
    assertTrue(sheet.contains("<v>1490.00</v>"));
    assertTrue(sheet.contains(">=1+1<"));
    assertFalse(sheet.contains("<f>"));
  }
}
