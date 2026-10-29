package cn.chuyu;

import static cn.chuyu.shared.Json.*;
import static cn.chuyu.shared.Money.*;
import static org.junit.jupiter.api.Assertions.*;

import cn.chuyu.funds.*;
import cn.chuyu.members.*;
import cn.chuyu.orders.*;
import cn.chuyu.reports.*;
import cn.chuyu.rewards.*;
import cn.chuyu.shared.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:chuyu;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.datasource.hikari.connection-init-sql=SET TIME ZONE '+08:00'",
      "chuyu.scheduler-enabled=false"
    })
class BusinessIntegrationTest {
  @Autowired Store db;
  @Autowired Commands commands;
  @Autowired Ledger ledger;
  @Autowired FundsService funds;
  @Autowired MemberService members;
  @Autowired OrderService orders;
  @Autowired FulfilmentService shipping;
  @Autowired RuleService rules;
  @Autowired SettlementService settlements;
  @Autowired QueryService queries;
  @Autowired PerformanceService performance;
  static final AtomicInteger seq = new AtomicInteger(1000);

  void actor(String id, String role, String owner) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new Actors.Actor(id, id, id, role, owner, false), null, List.of()));
  }

  void admin() {
    actor("U_ADMIN", "ADMIN", "");
  }

  @BeforeEach
  void before() {
    admin();
    db.exec("UPDATE app_settings SET transfer_enabled=TRUE WHERE id=1", Map.of());
  }

  @AfterEach
  void after() {
    SecurityContextHolder.clearContext();
  }

  void eq(String expected, Object actual) {
    assertEquals(0, d(expected).compareTo(d(actual)), String.valueOf(actual));
  }

  Map<String, Object> run(
      String name, Map<String, Object> b, java.util.function.Supplier<Map<String, Object>> f) {
    return commands.run(id(), name, b, f);
  }

  String point(int grade, String shop, String parent, String balance) {
    String point = "TEST" + id().substring(0, 12), owner = "OWN" + id();
    int number = seq.incrementAndGet();
    db.exec(
        "INSERT INTO"
            + " member_points(id,owner_id,phone,nickname,parent_id,is_main,sequence_no,grade,shop_type,activated,manual_own)"
            + " VALUES(#{p.id},#{p.owner},#{p.phone},'集成测试点位',#{p.parent},TRUE,1,#{p.grade},#{p.shop},TRUE,#{p.own})",
        map(
            "id",
            point,
            "owner",
            owner,
            "phone",
            "00999" + number,
            "parent",
            parent,
            "grade",
            grade,
            "shop",
            shop,
            "own",
            grade >= 2 ? d(5960) : grade == 1 ? d(1490) : ZERO));
    for (String kind : List.of("REWARD", "RECHARGE"))
      db.exec(
          "INSERT INTO wallets(point_id,kind) VALUES(#{p.id},#{p.kind})",
          map("id", point, "kind", kind));
    db.recordPoint(point, LocalDateTime.now(Store.ZONE));
    if (d(balance).signum() > 0)
      commands.run(
          id(),
          "OPEN",
          Map.of(),
          () ->
              map(
                  "id",
                  ledger.post(
                      "TEST:" + id(),
                      "TEST_OPENING",
                      point,
                      "集成测试初始余额",
                      List.of(Ledger.Change.money(point, "REWARD", d(balance), "测试初始余额", false)))));
    if (!shop.equals("NONE"))
      commands.run(
          id(),
          "SHOP_OPEN",
          Map.of(),
          () ->
              map(
                  "id",
                  ledger.post(
                      "TEST:" + id(),
                      "TEST_OPENING",
                      point,
                      "测试店铺初始充值",
                      List.of(
                          Ledger.Change.money(point, "RECHARGE", d("20000"), "测试初始充值", false)))));
    return point;
  }

  void owner(String point) {
    actor("U_MEMBER", "MEMBER", text(members.point(point), "ownerId"));
  }

  Map<String, Object> transfer(String from, String to, String amount) {
    return map("fromId", from, "toId", to, "amount", amount, "paymentPassword", "123456");
  }

  Map<String, Object> paidOrder(String buyer, String shop, int qty) {
    var b =
        map(
            "pointId",
            buyer,
            "shopId",
            shop,
            "payerId",
            buyer,
            "type",
            "FIRST",
            "quantity",
            qty,
            "productId",
            "PR001",
            "rewardAmount",
            "0");
    var o = run("ORDER", b, () -> orders.create(b));
    return run("PAY", Map.of(), () -> orders.pay(text(o, "id")));
  }

  @Test
  void transferAtomicIdempotentAndDifferentPayloadRejected() {
    String from = point(1, "NONE", null, "100"), to = point(1, "NONE", null, "0");
    owner(from);
    var b = transfer(from, to, "20.25");
    String key = id();
    var first = commands.run(key, "TRANSFER", b, () -> funds.transfer(b));
    var retry = commands.run(key, "TRANSFER", b, () -> funds.transfer(b));
    assertEquals(first.get("id"), retry.get("id"));
    eq("79.75", ledger.wallet(from, "REWARD").get("balance"));
    eq("20.25", ledger.wallet(to, "REWARD").get("balance"));
    assertThrows(
        BusinessException.class,
        () ->
            commands.run(
                key,
                "TRANSFER",
                transfer(from, to, "30"),
                () -> funds.transfer(transfer(from, to, "30"))));
    admin();
    assertEquals(true, queries.reconciliation().get("balanced"));
  }

  @Test
  void twoConcurrentTransfersCannotOverspend() throws Exception {
    String from = point(1, "NONE", null, "100"),
        to = point(1, "NONE", null, "0"),
        owner = text(members.point(from), "ownerId");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger success = new AtomicInteger();
    List<Future<?>> tasks = new ArrayList<>();
    for (int i = 0; i < 2; i++)
      tasks.add(
          pool.submit(
              () -> {
                actor("U_MEMBER", "MEMBER", owner);
                try {
                  start.await();
                  var b = transfer(from, to, "80");
                  run("TRANSFER", b, () -> funds.transfer(b));
                  success.incrementAndGet();
                } catch (BusinessException expected) {
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  SecurityContextHolder.clearContext();
                }
              }));
    start.countDown();
    for (var f : tasks) f.get(15, TimeUnit.SECONDS);
    pool.shutdown();
    assertEquals(1, success.get());
    eq("20", ledger.wallet(from, "REWARD").get("balance"));
    eq("80", ledger.wallet(to, "REWARD").get("balance"));
  }

  @Test
  void disabledWrongPasswordForeignOwnerFrozenAndFractionRejected() {
    String from = point(1, "NONE", null, "100"), to = point(1, "NONE", null, "0");
    owner(from);
    var b = transfer(from, to, "10");
    db.exec("UPDATE app_settings SET transfer_enabled=FALSE WHERE id=1", Map.of());
    assertThrows(BusinessException.class, () -> run("TRANSFER", b, () -> funds.transfer(b)));
    db.exec("UPDATE app_settings SET transfer_enabled=TRUE WHERE id=1", Map.of());
    var bad = transfer(from, to, "10");
    bad.put("paymentPassword", "654321");
    assertThrows(BusinessException.class, () -> run("TRANSFER", bad, () -> funds.transfer(bad)));
    var fractional = transfer(from, to, "1.001");
    assertThrows(
        BusinessException.class,
        () -> run("TRANSFER", fractional, () -> funds.transfer(fractional)));
    owner(to);
    assertThrows(BusinessException.class, () -> run("TRANSFER", b, () -> funds.transfer(b)));
    owner(from);
    db.exec("UPDATE member_points SET active=FALSE WHERE id=#{p.id}", map("id", from));
    assertThrows(BusinessException.class, () -> run("TRANSFER", b, () -> funds.transfer(b)));
    eq("100", ledger.wallet(from, "REWARD").get("balance"));
  }

  @Test
  void partialFailureRollsBackBothWalletsAndJournal() {
    String a = point(1, "NONE", null, "10"), b = point(1, "NONE", null, "0");
    long before = db.count("SELECT COUNT(*) n FROM ledger_transactions", Map.of());
    assertThrows(
        BusinessException.class,
        () ->
            run(
                "FAIL",
                Map.of(),
                () ->
                    map(
                        "id",
                        ledger.post(
                            "FAIL:" + id(),
                            "TEST",
                            a,
                            "验证回滚",
                            List.of(
                                Ledger.Change.money(a, "REWARD", d("-5"), "扣款", false),
                                Ledger.Change.money(b, "REWARD", d("-100"), "余额不足", false))))));
    eq("10", ledger.wallet(a, "REWARD").get("balance"));
    eq("0", ledger.wallet(b, "REWARD").get("balance"));
    assertEquals(before, db.count("SELECT COUNT(*) n FROM ledger_transactions", Map.of()));
  }

  @Test
  void withdrawalFreezeApproveAndReject() {
    String from = point(1, "NONE", null, "100");
    owner(from);
    var b =
        map(
            "pointId",
            from,
            "amount",
            "40",
            "bankAccount",
            "00000000001234",
            "paymentPassword",
            "123456");
    var w = run("WITHDRAW", b, () -> funds.withdraw(b));
    eq("2.40", w.get("fee"));
    eq("37.60", w.get("net"));
    eq("40", ledger.wallet(from, "REWARD").get("frozen"));
    String to = point(1, "NONE", null, "0");
    var tr = transfer(from, to, "80");
    assertThrows(BusinessException.class, () -> run("TRANSFER", tr, () -> funds.transfer(tr)));
    actor("U_FINANCE", "FINANCE", "");
    run(
        "REVIEW",
        Map.of(),
        () -> funds.reviewWithdrawal(text(w, "id"), map("approved", true, "reason", "测试审核")));
    eq("60", ledger.wallet(from, "REWARD").get("balance"));
    eq("0", ledger.wallet(from, "REWARD").get("frozen"));
    owner(from);
    var b2 =
        map(
            "pointId",
            from,
            "amount",
            "20",
            "bankAccount",
            "00000000001234",
            "paymentPassword",
            "123456");
    var second = run("WITHDRAW", b2, () -> funds.withdraw(b2));
    actor("U_FINANCE", "FINANCE", "");
    run(
        "REVIEW",
        Map.of(),
        () -> funds.reviewWithdrawal(text(second, "id"), map("approved", false, "reason", "测试驳回")));
    eq("60", ledger.wallet(from, "REWARD").get("balance"));
    eq("0", ledger.wallet(from, "REWARD").get("frozen"));
  }

  @Test
  void conversionConservesAndUsesNoExtraPerformance() {
    String p = point(1, "NONE", null, "100");
    owner(p);
    var b = map("pointId", p, "amount", "20", "paymentPassword", "123456");
    run("CONVERT", b, () -> funds.convert(b));
    eq("80", ledger.wallet(p, "REWARD").get("balance"));
    eq("20", ledger.wallet(p, "RECHARGE").get("balance"));
    eq("0", db.count("SELECT COUNT(*) n FROM shop_orders WHERE point_id=#{p.id}", map("id", p)));
    admin();
    assertEquals(true, queries.reconciliation().get("balanced"));
  }

  @Test
  void quoteSeventyPercentCapAndRepeatQuotaAndPrice() {
    String p = point(1, "NONE", null, "2000"), shop = point(6, "BIG", null, "0");
    var b =
        map(
            "pointId",
            p,
            "shopId",
            shop,
            "payerId",
            p,
            "type",
            "FIRST",
            "productId",
            "PR001",
            "quantity",
            5,
            "rewardAmount",
            "1043");
    eq("447", orders.quote(b).get("cashAmount"));
    b.put("rewardAmount", "1043.01");
    assertThrows(BusinessException.class, () -> orders.quote(b));
    b.put("type", "REPEAT");
    b.put("quantity", 2);
    b.put("rewardAmount", "0");
    eq("300", orders.quote(b).get("total"));
    var o = run("ORDER", b, () -> orders.create(b));
    run("PAY", Map.of(), () -> orders.pay(text(o, "id")));
    b.put("quantity", 1);
    assertThrows(BusinessException.class, () -> orders.quote(b));
    String two = point(2, "NONE", null, "0");
    b.put("pointId", two);
    b.put("quantity", 4);
    eq("396", orders.quote(b).get("total"));
    b.put("rewardAmount", "1");
    assertThrows(BusinessException.class, () -> orders.quote(b));
  }

  @Test
  void splitAndMergedShippingCancelPreservesHistoryAndRestrictedRefund() {
    String buyer = point(1, "NONE", null, "0"), shop = point(6, "BIG", null, "0");
    var a = paidOrder(buyer, shop, 5);
    var b = paidOrder(buyer, shop, 4);
    var form =
        map(
            "shopId",
            shop,
            "recipient",
            "测试收货人",
            "phone",
            "00123456",
            "address",
            "独立测试地址",
            "pickup",
            false,
            "lines",
            List.of(
                map("orderId", a.get("id"), "quantity", 3),
                map("orderId", b.get("id"), "quantity", 2)));
    var app = run("SHIP", form, () -> shipping.apply(form));
    run(
        "REVIEW",
        Map.of(),
        () -> shipping.review(text(app, "id"), map("approved", true, "reason", "测试合并发货")));
    run(
        "DISPATCH",
        Map.of(),
        () -> shipping.ship(text(app, "id"), map("carrier", "测试快递", "tracking", "DEMO-TEST")));
    assertEquals("PARTIAL", orders.order(text(a, "id")).get("fulfilmentStatus"));
    var over =
        map(
            "shopId",
            shop,
            "recipient",
            "测试收货人",
            "phone",
            "00123456",
            "address",
            "测试地址",
            "pickup",
            false,
            "lines",
            List.of(map("orderId", a.get("id"), "quantity", 3)));
    assertThrows(BusinessException.class, () -> run("SHIP", over, () -> shipping.apply(over)));
    run("CANCEL", Map.of(), () -> orders.cancel(text(a, "id"), map("reason", "测试已发货撤销")));
    assertTrue(shipping.lines(text(app, "id")).stream().anyMatch(l -> bool(l.get("cancelled"))));
    assertEquals(
        "SHIPPED",
        db.required(
                "SELECT status FROM fulfilment_applications WHERE id=#{p.id}",
                map("id", app.get("id")))
            .get("status"));
    eq("1490", ledger.wallet(shop, "RECHARGE").get("restricted"));
    var transfer =
        map(
            "fromId",
            shop,
            "toId",
            "CY9001",
            "amount",
            ledger.wallet(shop, "RECHARGE").get("balance"),
            "paymentPassword",
            "123456");
    owner(shop);
    assertThrows(
        BusinessException.class, () -> run("STOCK", transfer, () -> funds.stockTransfer(transfer)));
  }

  @Test
  void cancellationUsesOriginalAwardsAndCanGoNegative() {
    String upper = point(4, "NONE", null, "0"),
        direct = point(2, "NONE", upper, "0"),
        buyer = point(0, "NONE", direct, "0"),
        shop = point(6, "SMALL", null, "0");
    var o = paidOrder(buyer, shop, 20);
    eq("1072.80", ledger.wallet(direct, "REWARD").get("balance"));
    eq("536.40", ledger.wallet(upper, "REWARD").get("balance"));
    run(
        "SPEND",
        Map.of(),
        () ->
            map(
                "id",
                ledger.post(
                    "TEST-SPEND:" + id(),
                    "TEST_SPEND",
                    direct,
                    "演示已花费奖金",
                    List.of(
                        Ledger.Change.money(direct, "REWARD", d("-1072.80"), "已花费奖金", false)))));
    run("CANCEL", Map.of(), () -> orders.cancel(text(o, "id"), map("reason", "测试撤单追回")));
    eq("-1072.80", ledger.wallet(direct, "REWARD").get("balance"));
    eq("0", ledger.wallet(upper, "REWARD").get("balance"));
    run(
        "INCOME",
        Map.of(),
        () ->
            map(
                "id",
                ledger.post(
                    "TEST-INCOME:" + id(),
                    "TEST_INCOME",
                    direct,
                    "后续奖励抵扣",
                    List.of(Ledger.Change.money(direct, "REWARD", d("100"), "后续奖励", false)))));
    eq("-972.80", ledger.wallet(direct, "REWARD").get("balance"));
    assertThrows(
        BusinessException.class,
        () -> run("CANCEL", Map.of(), () -> orders.cancel(text(o, "id"), map("reason", "重复撤单"))));
  }

  @Test
  void rulesRequireRetestAndPublishedVersionsImmutable() {
    var b = map("name", "测试版本");
    var draft = run("DRAFT", b, () -> rules.draft(b));
    String rule = text(draft, "id");
    assertThrows(
        BusinessException.class, () -> run("PUBLISH", Map.of(), () -> rules.publish(rule)));
    run("TEST", Map.of(), () -> rules.test(rule));
    var config = rules.get(rule);
    config.managementFee = d(".11");
    var edit = map("config", config);
    run("EDIT", edit, () -> rules.save(rule, edit));
    assertThrows(
        BusinessException.class, () -> run("PUBLISH", Map.of(), () -> rules.publish(rule)));
    run("TEST", Map.of(), () -> rules.test(rule));
    run("PUBLISH", Map.of(), () -> rules.publish(rule));
    assertThrows(BusinessException.class, () -> run("EDIT", edit, () -> rules.save(rule, edit)));
    db.exec("UPDATE rule_versions SET status='ARCHIVED' WHERE id=#{p.id}", map("id", rule));
    db.exec("UPDATE rule_versions SET status='ACTIVE' WHERE id='DEMO-V2'", Map.of());
    db.exec("UPDATE app_settings SET current_rule='DEMO-V2' WHERE id=1", Map.of());
  }

  @Test
  void closedBatchPostsOnceAndOpenPeriodCannotPost() {
    var b =
        map("cycle", "MONTHLY", "period", Store.today().minusMonths(1).toString().substring(0, 7));
    var preview = run("PREVIEW", b, () -> settlements.preview(b));
    String id = text(preview, "id");
    run("POST", Map.of(), () -> settlements.post(id));
    assertThrows(BusinessException.class, () -> run("POST", Map.of(), () -> settlements.post(id)));
    var current = map("cycle", "DAILY", "period", Store.today().toString());
    var open = run("PREVIEW", current, () -> settlements.preview(current));
    assertThrows(
        BusinessException.class,
        () -> run("POST", Map.of(), () -> settlements.post(text(open, "id"))));
    assertEquals(true, queries.reconciliation().get("balanced"));
  }

  @Test
  void memberCyclesAndPhoneStringsAndThirteenPointLimit() {
    String phone = "00077" + seq.incrementAndGet();
    var b = map("nickname", "前导零测试", "phone", phone, "parentId", "CY1001");
    var root = run("MEMBER", b, () -> members.create(b));
    String r = text(root, "id");
    assertEquals(phone, members.point(r).get("phone"));
    assertThrows(
        BusinessException.class,
        () ->
            run(
                "CYCLE",
                Map.of(),
                () -> members.update("CY1001", map("parentId", r, "reason", "循环测试"))));
    List<String> first = new ArrayList<>();
    for (int i = 1; i <= 12; i++) {
      String parent = i <= 3 ? r : first.get((i - 4) / 3);
      var sub = map("nickname", "测试小号" + i, "phone", phone, "parentId", parent, "subPoint", true);
      var x = run("SUB", sub, () -> members.create(sub));
      if (i <= 3) first.add(text(x, "id"));
    }
    var extra =
        map("nickname", "超额小号", "phone", phone, "parentId", first.getFirst(), "subPoint", true);
    assertThrows(BusinessException.class, () -> run("SUB", extra, () -> members.create(extra)));
    assertEquals(
        13,
        db.count(
            "SELECT COUNT(*) n FROM member_points WHERE owner_id=#{p.owner}",
            map("owner", root.get("ownerId"))));
  }

  @Test
  void readAndButtonPermissionsRemainIsolated() {
    owner("CY1001");
    var result = queries.page("members", Map.of("size", "200"));
    assertTrue(((List<?>) result.get("items")).size() <= 13);
    assertThrows(BusinessException.class, () -> queries.dashboard(Map.of()));
    assertThrows(BusinessException.class, () -> rules.draft(map("name", "越权")));
    actor("U_AUDITOR", "AUDITOR", "");
    assertThrows(
        BusinessException.class,
        () ->
            funds.adjust(
                map("pointId", "CY1001", "kind", "REWARD", "amount", "1", "reason", "越权")));
    assertDoesNotThrow(() -> queries.page("orders", Map.of()));
  }

  @Test
  void pendingPaidOrderReservesFundsUntilActivationOrCancellation() {
    String shop = point(6, "BIG", null, "0");
    String phone = "00066" + seq.incrementAndGet();
    var member =
        run(
            "MEMBER",
            Map.of(),
            () -> members.create(map("phone", phone, "nickname", "预留测试", "parentId", "CY1001")));
    String buyer = text(member, "id");
    var b =
        map(
            "pointId",
            buyer,
            "shopId",
            shop,
            "type",
            "NEW",
            "productId",
            "PR001",
            "quantity",
            5,
            "rewardAmount",
            "0");
    var order = run("ORDER", b, () -> orders.create(b));
    run("PAY", Map.of(), () -> orders.pay(text(order, "id")));
    eq("1490", ledger.wallet(shop, "RECHARGE").get("frozen"));
    eq("1490", orders.order(text(order, "id")).get("reservedAmount"));
    run("CANCEL", Map.of(), () -> orders.cancel(text(order, "id"), map("reason", "验证待激活资金释放")));
    eq("0", ledger.wallet(shop, "RECHARGE").get("frozen"));
    eq("1490", ledger.wallet(shop, "RECHARGE").get("restricted"));
  }

  @Test
  void earlierPointMustActivateFirst() {
    String phone = "00055" + seq.incrementAndGet();
    var main =
        run(
            "MAIN",
            Map.of(),
            () -> members.create(map("phone", phone, "nickname", "顺序测试主号", "parentId", "CY1001")));
    String root = text(main, "id");
    var sub =
        run(
            "SUB",
            Map.of(),
            () ->
                members.create(
                    map("phone", phone, "nickname", "顺序测试小号", "parentId", root, "subPoint", true)));
    var b =
        map(
            "pointId",
            sub.get("id"),
            "shopId",
            "CY9001",
            "type",
            "NEW",
            "productId",
            "PR001",
            "quantity",
            1,
            "rewardAmount",
            "0");
    var o = run("ORDER", b, () -> orders.create(b));
    run("PAY", Map.of(), () -> orders.pay(text(o, "id")));
    assertThrows(
        BusinessException.class,
        () -> run("ACTIVATE", Map.of(), () -> orders.activate(text(o, "id"))));
  }

  @Test
  void auditorCannotConfirmPayment() {
    String buyer = point(1, "NONE", null, "0");
    var b =
        map(
            "pointId",
            buyer,
            "shopId",
            "CY9001",
            "type",
            "FIRST",
            "productId",
            "PR001",
            "quantity",
            1,
            "rewardAmount",
            "0");
    var o = run("ORDER", b, () -> orders.create(b));
    actor("U_AUDITOR", "AUDITOR", "");
    assertThrows(
        BusinessException.class, () -> run("PAY", Map.of(), () -> orders.pay(text(o, "id"))));
  }

  @Test
  void cancellationUsesEachMembersOwnGradeVersion() {
    String shop = point(6, "BIG", null, "0");
    for (String version : List.of("v1", "v2")) {
      String leader = point(4, "NONE", null, "0");
      run(
          "EDIT",
          Map.of(),
          () ->
              members.update(
                  leader,
                  map(
                      "gradeVersion",
                      version,
                      "grade",
                      4,
                      "manualTeam",
                      "80000",
                      "manualSmall",
                      "25000",
                      "reason",
                      "测试版本降级门槛")));
      String buyer = point(0, "NONE", leader, "0");
      var order = paidOrder(buyer, shop, 1);
      run("CANCEL", Map.of(), () -> orders.cancel(text(order, "id"), map("reason", "测试按会员所属版本降级")));
      assertEquals(version.equals("v1") ? 4 : 3, integer(members.point(leader).get("grade")));
    }
  }

  @Test
  void tenDirectReferralsUpgradeIndependentlyOfRecharge() {
    String leader = point(0, "NONE", null, "0");
    for (int i = 0; i < 9; i++) point(0, "NONE", leader, "0");
    run(
        "REFRESH",
        Map.of(),
        () -> {
          performance.refreshGrades(false);
          return Map.of();
        });
    assertEquals(0, integer(members.point(leader).get("grade")));
    point(0, "NONE", leader, "0");
    run(
        "REFRESH",
        Map.of(),
        () -> {
          performance.refreshGrades(false);
          return Map.of();
        });
    assertEquals(1, integer(members.point(leader).get("grade")));
  }
}
