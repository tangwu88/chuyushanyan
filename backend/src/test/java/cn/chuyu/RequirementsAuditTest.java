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
import cn.chuyu.system.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:chuyu_audit;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.datasource.hikari.connection-init-sql=SET TIME ZONE '+08:00'",
      "chuyu.scheduler-enabled=false"
    })
class RequirementsAuditTest {
  @Autowired Store db;
  @Autowired Commands commands;
  @Autowired MemberService members;
  @Autowired PerformanceService performance;
  @Autowired FundsService funds;
  @Autowired Ledger ledger;
  @Autowired OrderService orders;
  @Autowired FulfilmentService shipping;
  @Autowired RuleService rules;
  @Autowired SettlementService settlements;
  @Autowired QueryService queries;
  @Autowired AdvancedReports reports;
  @Autowired ManagementService management;
  @Autowired OperationsService operations;
  @Autowired AccessPolicy policy;
  @Autowired ApiController api;
  @Autowired BCryptPasswordEncoder encoder;
  static AtomicInteger seq = new AtomicInteger(300000);

  void actor(Actors.Actor a) {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(a, null, List.of()));
  }

  void admin() {
    actor(new Actors.Actor("U_ADMIN", "admin", "管理员", "ADMIN", "", false));
  }

  void owner(String id) {
    actor(
        new Actors.Actor(
            "U_MEMBER", "member", "会员", "MEMBER", text(members.point(id), "ownerId"), false));
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

  Map<String, Object> run(String operation, java.util.function.Supplier<Map<String, Object>> work) {
    return commands.run(id(), operation, Map.of(), work);
  }

  void eq(String expected, Object actual) {
    assertEquals(0, d(expected).compareTo(d(actual)), String.valueOf(actual));
  }

  String point(int grade, String shop, String parent, String reward) {
    String phone = "007" + seq.incrementAndGet();
    var row =
        run(
            "POINT",
            () ->
                members.create(
                    map(
                        "phone",
                        phone,
                        "nickname",
                        "复核会员" + phone,
                        "parentId",
                        parent == null ? "" : parent)));
    String point = text(row, "id");
    db.exec(
        "UPDATE member_points SET"
            + " activated=TRUE,grade=#{p.grade},shop_type=#{p.shop},manual_own=#{p.own} WHERE"
            + " id=#{p.id}",
        map(
            "id",
            point,
            "grade",
            grade,
            "shop",
            shop,
            "own",
            grade >= 2 ? d(5960) : grade == 1 ? d(1490) : ZERO));
    db.recordPoint(point, LocalDateTime.now(Store.ZONE));
    if (d(reward).signum() > 0)
      run(
          "OPEN",
          () ->
              map(
                  "id",
                  ledger.post(
                      "TEST:" + id(),
                      "TEST_OPENING",
                      point,
                      "复核初始积分",
                      List.of(Ledger.Change.money(point, "REWARD", d(reward), "初始积分", false)))));
    if (!shop.equals("NONE"))
      run(
          "OPEN",
          () ->
              map(
                  "id",
                  ledger.post(
                      "TEST:" + id(),
                      "TEST_OPENING",
                      point,
                      "复核初始充值",
                      List.of(Ledger.Change.money(point, "RECHARGE", d(20000), "初始充值", false)))));
    return point;
  }

  Map<String, Object> body(String buyer, String shop, String type, int qty) {
    return map(
        "pointId",
        buyer,
        "payerId",
        buyer,
        "shopId",
        shop,
        "productId",
        "PR001",
        "type",
        type,
        "quantity",
        qty,
        "rewardAmount",
        "0");
  }

  Map<String, Object> paid(String buyer, String shop, int qty) {
    var order = run("ORDER", () -> orders.create(body(buyer, shop, "FIRST", qty)));
    return run("PAY", () -> orders.pay(text(order, "id")));
  }

  Map<String, Object> shipment(String shop, String order, int qty) {
    return map(
        "shopId",
        shop,
        "recipient",
        "复核收货人",
        "phone",
        "0012345678",
        "address",
        "独立复核地址",
        "pickup",
        false,
        "lines",
        List.of(map("orderId", order, "quantity", qty)));
  }

  @Test
  void everyNewListAndStatisticReturnsAUsablePage() {
    for (String name :
        List.of(
            "jobs",
            "products",
            "empty-points",
            "referrals",
            "promotions",
            "director-promotions",
            "cancellations",
            "sync-upgrades",
            "stock-transfers",
            "unused-recharge",
            "recharge-records",
            "login-logs",
            "report-snapshots",
            "rankings",
            "shop-stats",
            "shop-cash",
            "product-sales",
            "daily-stats")) {
      var result = queries.page(name, Map.of("size", "20"));
      assertNotNull(result.get("items"), name);
      assertNotNull(result.get("total"), name);
    }
  }

  @Test
  void offShelfProductsRemainManageable() {
    String id = "PRODUCT_" + id();
    db.exec(
        "INSERT INTO"
            + " products(id,name,first_price,repeat_one,repeat_other,limit_one,limit_other,subsidy_one,subsidy_rate,differential_json,weight_kg,active)"
            + " VALUES(#{p.id},'下架复核商品',298,150,99,2,4,51,.05,'[1,2,3,4,5]',.5,FALSE)",
        map("id", id));
    assertEquals(
        1, ((Number) queries.page("products", Map.of("search", id)).get("total")).intValue());
    db.exec("UPDATE products SET active=TRUE WHERE id=#{p.id}", map("id", id));
    assertEquals(
        1,
        ((Number) queries.page("products", Map.of("search", id, "status", "ACTIVE")).get("total"))
            .intValue());
  }

  @Test
  void shipmentUsesWeightTariffsAndKeepsTheQuotedSnapshot() {
    String shop = point(6, "BIG", null, "0"),
        buyer = point(1, "NONE", null, "0"),
        order = text(paid(buyer, shop, 3), "id");
    String tariff = "FREIGHT_" + id();
    run(
        "FREIGHT",
        () ->
            management.save(
                "freights",
                null,
                map(
                    "code",
                    tariff,
                    "name",
                    "复核运费",
                    "channel",
                    "初语山言",
                    "maxWeight",
                    "2",
                    "amount",
                    "8",
                    "active",
                    true)));
    var form = shipment(shop, order, 2);
    var quote = shipping.quote(form);
    eq("1", quote.get("weightKg"));
    eq("8", quote.get("freightAmount"));
    eq("596", quote.get("goodsAmount"));
    form.put("expectedFreight", "7");
    assertThrows(BusinessException.class, () -> run("SHIP", () -> shipping.apply(form)));
    form.put("expectedFreight", "8");
    var app = run("SHIP", () -> shipping.apply(form));
    run(
        "FREIGHT",
        () ->
            management.save(
                "freights",
                tariff,
                map(
                    "name",
                    "复核运费",
                    "channel",
                    "初语山言",
                    "maxWeight",
                    "2",
                    "amount",
                    "9",
                    "active",
                    true)));
    eq(
        "8",
        db.required(
                "SELECT freight_amount FROM fulfilment_applications WHERE id=#{p.id}",
                map("id", app.get("id")))
            .get("freightAmount"));
    run("DELETE_FREIGHT", () -> management.delete("freights", tariff, Map.of()));
  }

  @Test
  void frozenPaidNewPointCannotBeActivated() {
    String shop = point(6, "BIG", null, "0"), phone = "007" + seq.incrementAndGet();
    String buyer =
        text(
            run(
                "POINT",
                () ->
                    members.create(
                        map("phone", phone, "nickname", "冻结激活复核", "parentId", "CY1001"))),
            "id");
    var order = run("ORDER", () -> orders.create(body(buyer, shop, "NEW", 1)));
    run("PAY", () -> orders.pay(text(order, "id")));
    run("FREEZE", () -> members.update(buyer, map("active", false, "reason", "复核冻结")));
    assertThrows(
        BusinessException.class, () -> run("ACTIVATE", () -> orders.activate(text(order, "id"))));
    eq("298", ledger.wallet(shop, "RECHARGE").get("frozen"));
  }

  @Test
  void duplicatePendingNewOrdersAreRejectedAndUnpaidOrdersCanBeCancelledByTheirOwner() {
    String phone = "007" + seq.incrementAndGet();
    String buyer =
        text(
            run(
                "POINT",
                () ->
                    members.create(
                        map("phone", phone, "nickname", "重复新人单复核", "parentId", "CY1001"))),
            "id");
    var first = run("ORDER", () -> orders.create(body(buyer, "CY9001", "NEW", 1)));
    assertThrows(
        BusinessException.class,
        () -> run("ORDER", () -> orders.create(body(buyer, "CY9001", "NEW", 1))));
    owner(buyer);
    run("CANCEL", () -> orders.cancel(text(first, "id"), map("reason", "本人取消未付款订单")));
    assertTrue(bool(orders.order(text(first, "id")).get("cancelled")));
  }

  @Test
  void companyTransfersReducePeriodCashButWithdrawalsDoNot() {
    String shop = point(6, "BIG", null, "500");
    var topup = run("TOPUP", () -> funds.requestTopup(map("shopId", shop, "amount", "100")));
    db.exec(
        "UPDATE topups SET created_at=#{p.at} WHERE id=#{p.id}",
        map(
            "id",
            topup.get("id"),
            "at",
            java.sql.Timestamp.valueOf(Store.today().minusMonths(1).atTime(12, 0))));
    run(
        "TOPUP_REVIEW",
        () -> funds.reviewTopup(text(topup, "id"), map("approved", true, "reason", "复核充值")));
    owner(shop);
    run(
        "TRANSFER",
        () ->
            funds.transfer(
                map(
                    "fromId",
                    shop,
                    "toId",
                    "RECOVERY",
                    "amount",
                    "20",
                    "paymentPassword",
                    "123456")));
    var withdrawal =
        run(
            "WITHDRAW",
            () ->
                funds.withdraw(
                    map(
                        "pointId",
                        shop,
                        "amount",
                        "10",
                        "bankAccount",
                        "00000000001234",
                        "paymentPassword",
                        "123456")));
    admin();
    run(
        "WITHDRAW_REVIEW",
        () ->
            funds.reviewWithdrawal(
                text(withdrawal, "id"), map("approved", true, "reason", "复核提现")));
    var row =
        reports
            .rows(
                "shop-cash",
                Map.of(
                    "start",
                    Store.today().toString(),
                    "end",
                    Store.today().toString(),
                    "search",
                    shop))
            .getFirst();
    eq("100", row.get("rechargeTotal"));
    eq("20", row.get("companyReward"));
    eq("80", row.get("cashReceivable"));
  }

  @Test
  void fractionalManualAdjustmentsCannotBeRoundedIntoTheLedger() {
    String point = point(1, "NONE", null, "0");
    assertThrows(
        BusinessException.class,
        () ->
            run(
                "ADJUST",
                () ->
                    funds.adjust(
                        map(
                            "pointId", point, "kind", "REWARD", "amount", "1.001", "reason",
                            "小数位复核"))));
    eq("0", ledger.wallet(point, "REWARD").get("balance"));
  }

  @Test
  void departmentScopeAppliesToPagesWritesAndGlobalReconciliation() {
    String one = point(1, "NONE", null, "0"), two = point(1, "NONE", null, "0");
    db.exec("UPDATE member_points SET department='复核一部' WHERE id=#{p.id}", map("id", one));
    db.exec("UPDATE member_points SET department='复核二部' WHERE id=#{p.id}", map("id", two));
    actor(
        new Actors.Actor(
            "SCOPE",
            "scope",
            "部门财务",
            "FINANCE",
            "",
            false,
            Actors.PERMISSIONS.get("FINANCE"),
            "DEPARTMENT",
            "复核一部"));
    assertEquals(1, ((Number) queries.page("members", Map.of()).get("total")).intValue());
    assertThrows(
        BusinessException.class,
        () ->
            run(
                "ADJUST",
                () ->
                    funds.adjust(
                        map("pointId", two, "kind", "REWARD", "amount", "1", "reason", "越权复核"))));
    assertThrows(BusinessException.class, queries::reconciliation);
    assertDoesNotThrow(() -> queries.dashboard(Map.of()));
  }

  @Test
  void customRolesAndDisabledRolesAffectTheAuthenticatedPolicy() {
    String role = "TEST_" + id().substring(0, 8).toUpperCase();
    run(
        "ROLE",
        () ->
            management.save(
                "roles",
                null,
                map(
                    "code",
                    role,
                    "name",
                    "复核只读角色",
                    "permissions",
                    List.of("members:read"),
                    "dataScope",
                    "DEPARTMENT",
                    "active",
                    true)));
    var person =
        map(
            "id",
            "TEST_USER",
            "username",
            "test",
            "displayName",
            "复核",
            "role",
            role,
            "ownerId",
            "",
            "mustChangePassword",
            false,
            "department",
            "初语山言");
    assertTrue(policy.actor(person).permissions().contains("members:read"));
    assertFalse(policy.actor(person).permissions().contains("funds:write"));
    run(
        "ROLE",
        () ->
            management.save(
                "roles",
                role,
                map(
                    "name",
                    "复核只读角色",
                    "permissions",
                    List.of("members:read"),
                    "dataScope",
                    "DEPARTMENT",
                    "active",
                    false)));
    assertTrue(policy.actor(person).permissions().isEmpty());
  }

  @Test
  void staffResetUsesPhoneSuffixAndRequiresFirstLoginChange() {
    String phone = "007" + seq.incrementAndGet();
    var user =
        run(
            "USER",
            () ->
                management.user(
                    null,
                    map(
                        "username",
                        "staff_" + seq.incrementAndGet(),
                        "displayName",
                        "复核员工",
                        "phone",
                        phone,
                        "role",
                        "OPERATOR",
                        "department",
                        "初语山言",
                        "password",
                        "Testing!2026")));
    run("RESET", () -> management.resetUser(text(user, "id"), false, map("reason", "复核重置")));
    var saved = db.required("SELECT * FROM app_users WHERE id=#{p.id}", map("id", user.get("id")));
    assertTrue(encoder.matches(phone.substring(phone.length() - 6), text(saved, "passwordHash")));
    assertTrue(bool(saved.get("mustChangePassword")));
  }

  @Test
  void userImportIsAtomicAndPreservesLeadingZeroPhones() {
    String username = "import_" + seq.incrementAndGet(), phone = "0007" + seq.incrementAndGet();
    var row =
        map(
            "username",
            username,
            "displayName",
            "导入复核",
            "phone",
            phone,
            "role",
            "OPERATOR",
            "department",
            "初语山言");
    assertThrows(
        RuntimeException.class,
        () -> run("IMPORT", () -> operations.importUsers(map("rows", List.of(row, row)))));
    assertEquals(
        0,
        db.count("SELECT COUNT(*) FROM app_users WHERE username=#{p.name}", map("name", username)));
    run("IMPORT", () -> operations.importUsers(map("rows", List.of(row))));
    var saved =
        db.required("SELECT * FROM app_users WHERE username=#{p.name}", map("name", username));
    assertEquals(phone, saved.get("phone"));
    assertTrue(encoder.matches(phone.substring(phone.length() - 6), text(saved, "passwordHash")));
  }

  @Test
  void freezeWithDescendantsNeedsConfirmationAndRestorationDoesNotRewindMoney() {
    String parent = point(2, "NONE", null, "100");
    point(0, "NONE", parent, "0");
    assertThrows(
        BusinessException.class,
        () -> run("FREEZE", () -> members.update(parent, map("active", false, "reason", "复核冻结"))));
    run(
        "FREEZE",
        () ->
            members.update(
                parent, map("active", false, "confirmDescendants", true, "reason", "复核冻结")));
    var cancellation =
        db.required("SELECT * FROM member_cancellations WHERE point_id=#{p.id}", map("id", parent));
    run(
        "ADJUST",
        () ->
            funds.adjust(
                map("pointId", parent, "kind", "REWARD", "amount", "10", "reason", "注销期间对账调整")));
    run("RESTORE", () -> members.restore(text(cancellation, "id"), map("reason", "复核找回")));
    assertTrue(bool(members.point(parent).get("active")));
    eq("110", ledger.wallet(parent, "REWARD").get("balance"));
    assertTrue(text(cancellation, "snapshotJson").contains("100"));
  }

  @Test
  void phoneCorrectionMergesPointsWithoutMovingBalancesOrOverwritingHistory() {
    String target = point(1, "NONE", null, "0"), source = point(1, "NONE", target, "10");
    String sourcePhone = text(members.point(source), "phone"),
        targetPhone = text(members.point(target), "phone"),
        oldOwner = text(members.point(source), "ownerId");
    run(
        "SUB",
        () ->
            members.create(
                map(
                    "phone",
                    sourcePhone,
                    "nickname",
                    "归并小号",
                    "parentId",
                    source,
                    "subPoint",
                    true)));
    String originalSnapshot =
        text(
            db.required(
                "SELECT snapshot_json FROM member_history WHERE point_id=#{p.id} ORDER BY"
                    + " sequence_id DESC LIMIT 1",
                map("id", source)),
            "snapshotJson");
    run(
        "MERGE",
        () ->
            members.update(
                source, map("phone", targetPhone, "mergeConfirmed", true, "reason", "复核手机号归并")));
    assertEquals(members.point(target).get("ownerId"), members.point(source).get("ownerId"));
    assertFalse(bool(members.point(source).get("isMain")));
    eq("10", ledger.wallet(source, "REWARD").get("balance"));
    assertTrue(originalSnapshot.contains(oldOwner));
    assertEquals(
        0,
        db.count(
            "SELECT COUNT(*) FROM app_users WHERE owner_id=#{p.owner} AND active=TRUE",
            map("owner", oldOwner)));
    assertEquals(
        3,
        db.count(
            "SELECT COUNT(*) FROM member_points WHERE phone=#{p.phone}",
            map("phone", targetPhone)));
  }

  @Test
  void aSubPointCannotLoseItsSamePhoneParent() {
    String main = point(0, "NONE", null, "0"), phone = text(members.point(main), "phone");
    String sub =
        text(
            run(
                "SUB",
                () ->
                    members.create(
                        map(
                            "phone",
                            phone,
                            "nickname",
                            "上级复核",
                            "parentId",
                            main,
                            "subPoint",
                            true))),
            "id");
    assertThrows(
        BusinessException.class,
        () -> run("EDIT", () -> members.update(sub, map("parentId", "", "reason", "非法断开"))));
  }

  @Test
  void emptyPointsCanBeActivatedWithoutBackpayingHistoricalBonuses() {
    String phone = "007" + seq.incrementAndGet();
    String point =
        text(
            run(
                "EMPTY",
                () ->
                    members.create(
                        map(
                            "phone",
                            phone,
                            "nickname",
                            "空点复核",
                            "emptyPoint",
                            true,
                            "grade",
                            4,
                            "manualTeam",
                            "100000"))),
            "id");
    assertEquals(4, integer(members.point(point).get("grade")));
    run("EMPTY_ACTIVATE", () -> members.activateEmpty(point, map("reason", "复核空点激活")));
    assertTrue(bool(members.point(point).get("activated")));
    assertFalse(bool(members.point(point).get("emptyPoint")));
    eq("0", ledger.wallet(point, "REWARD").get("balance"));
  }

  @Test
  void draftsCanBeComparedButCannotBePostedAsFinalSettlementRules() {
    var draft = run("DRAFT", () -> rules.draft(map("name", "比较复核草稿")));
    String id = text(draft, "id");
    var result = rules.compare(rules.currentId(), id);
    assertNotNull(result.get("scenarios"));
    var config = read(write(rules.current()), Rules.class);
    config.managementFee = d("0.10000");
    config.salesRates.set(0, d("0.05000"));
    run("SAVE", () -> rules.save(id, map("config", config, "reason", "相同比例的小数格式比较")));
    assertTrue(((List<?>) rules.compare(rules.currentId(), id).get("changes")).isEmpty());
    config.managementFee = d("0.11");
    run("SAVE", () -> rules.save(id, map("config", config, "reason", "实际比例变更比较")));
    var changed = rules.compare(rules.currentId(), id);
    assertTrue(
        ((List<Map<String, Object>>) changed.get("changes"))
            .stream().anyMatch(c -> text(c, "field").equals("managementFee")));
    assertTrue(
        ((List<Map<String, Object>>) changed.get("scenarios"))
            .stream().anyMatch(c -> d(c.get("difference")).signum() < 0));
    var preview =
        run(
            "PREVIEW",
            () -> settlements.preview(map("cycle", "MONTHLY", "period", "1999-01", "ruleId", id)));
    assertThrows(
        BusinessException.class, () -> run("POST", () -> settlements.post(text(preview, "id"))));
  }

  @Test
  void jobsPostActualDailyAwardsOnceAndKeepFailedAttempts() {
    String day = Store.today().minusDays(1).toString();
    var first = operations.runJob("DAILY", id(), map("period", day));
    String batch = text((Map<String, Object>) first.get("result"), "batchId");
    long count =
        db.count("SELECT COUNT(*) FROM reward_awards WHERE batch_id=#{p.id}", map("id", batch));
    assertTrue(count > 0);
    operations.runJob("DAILY", id(), map("period", day));
    assertEquals(
        count,
        db.count("SELECT COUNT(*) FROM reward_awards WHERE batch_id=#{p.id}", map("id", batch)));
    assertThrows(
        BusinessException.class,
        () -> operations.runJob("DAILY", id(), map("period", Store.today().toString())));
    assertTrue(db.count("SELECT COUNT(*) FROM job_runs WHERE status='ERROR'", Map.of()) > 0);
    assertDoesNotThrow(() -> queries.page("jobs", Map.of()));
  }

  @Test
  void futureCancellationOffsetsDoNotReduceAnEarlierPeriod() {
    String buyer = point(7, "NONE", null, "0"), shop = point(6, "BIG", null, "0");
    var order = paid(buyer, shop, 1);
    LocalDate day = Store.today().minusDays(5);
    db.exec(
        "UPDATE shop_orders SET activated_at=#{p.at} WHERE id=#{p.id}",
        map("id", order.get("id"), "at", java.sql.Timestamp.valueOf(day.atTime(12, 0))));
    db.recordPoint(buyer, day.minusDays(1).atStartOfDay());
    db.exec(
        "INSERT INTO performance_offsets(id,point_id,order_id,remaining)"
            + " VALUES(#{p.id},#{p.point},#{p.order},1000)",
        map("id", id(), "point", buyer, "order", order.get("id")));
    var node =
        performance.nodes(day.atStartOfDay(), day.plusDays(1).atStartOfDay()).stream()
            .filter(n -> n.id().equals(buyer))
            .findFirst()
            .orElseThrow();
    eq("298", node.small());
  }

  @Test
  void stockTransferAuditDoesNotPersistPaymentPasswords() {
    String from = point(6, "BIG", null, "0"), to = point(6, "BIG", null, "0");
    owner(from);
    var transaction =
        run(
            "STOCK",
            () ->
                funds.stockTransfer(
                    map("fromId", from, "toId", to, "amount", "1", "paymentPassword", "123456")));
    var audit =
        db.required(
            "SELECT * FROM audit_logs WHERE action='STOCK_TRANSFER' AND target_id=#{p.id}",
            map("id", transaction.get("id")));
    assertFalse(text(audit, "afterJson").contains("123456"));
    assertTrue(text(audit, "afterJson").contains("***"));
  }

  @Test
  void cancelledUnsentApplicationsBecomeVoidAndRetainTheirLines() {
    String buyer = point(1, "NONE", null, "0"), shop = point(6, "BIG", null, "0");
    var order = paid(buyer, shop, 2);
    var app = run("SHIP", () -> shipping.apply(shipment(shop, text(order, "id"), 1)));
    run("CANCEL", () -> orders.cancel(text(order, "id"), map("reason", "复核取消申请")));
    assertEquals(
        "VOID",
        db.required(
                "SELECT status FROM fulfilment_applications WHERE id=#{p.id}",
                map("id", app.get("id")))
            .get("status"));
    assertTrue(
        shipping.lines(text(app, "id")).stream().allMatch(line -> bool(line.get("cancelled"))));
  }

  @Test
  void syncUpgradeApprovalCannotBypassTheActualGradeThreshold() {
    String parent = point(0, "NONE", null, "0"), child = point(1, "NONE", parent, "0");
    var request =
        run(
            "SYNC",
            () ->
                operations.requestUpgrade(
                    map("fromPoint", child, "targetPoint", parent, "expectedGrade", 1)));
    assertThrows(
        BusinessException.class,
        () ->
            run(
                "REVIEW",
                () ->
                    operations.reviewUpgrade(
                        text(request, "id"), map("approved", true, "reason", "尚未达标"))));
    db.exec("UPDATE member_points SET manual_own=1490 WHERE id=#{p.id}", map("id", parent));
    run(
        "REVIEW",
        () ->
            operations.reviewUpgrade(text(request, "id"), map("approved", true, "reason", "条件达标")));
    assertEquals(1, integer(members.point(parent).get("grade")));
    assertEquals(
        "APPROVED",
        db.required(
                "SELECT status FROM sync_upgrades WHERE id=#{p.id}", map("id", request.get("id")))
            .get("status"));
    assertThrows(
        BusinessException.class,
        () ->
            run(
                "REVIEW",
                () ->
                    operations.reviewUpgrade(
                        text(request, "id"), map("approved", true, "reason", "重复审核"))));
  }

  @Test
  void maintenanceJobsSaveSnapshotsAndArchivingKeepsTheOriginalLedger() {
    String day = Store.today().minusDays(1).toString(),
        buyer = point(1, "NONE", null, "10"),
        shop = point(6, "BIG", null, "0");
    var order = paid(buyer, shop, 1);
    run("CANCEL", () -> orders.cancel(text(order, "id"), map("reason", "归档复核")));
    db.exec(
        "UPDATE shop_orders SET created_at='2000-01-01 00:00:00' WHERE id=#{p.id}",
        map("id", order.get("id")));
    db.exec(
        "UPDATE ledger_entries SET created_at='2000-01-01 00:00:00' WHERE point_id=#{p.id}",
        map("id", buyer));
    long entries =
        db.count("SELECT COUNT(*) FROM ledger_entries WHERE point_id=#{p.id}", map("id", buyer));
    var balance = ledger.wallet(buyer, "REWARD").get("balance");
    for (String job : List.of("PERFORMANCE", "RANKING", "FINANCE", "RECONCILIATION", "ARCHIVE"))
      assertEquals("DONE", operations.runJob(job, id(), map("period", day)).get("status"));
    assertTrue(
        db.count(
                "SELECT COUNT(*) FROM report_snapshots WHERE snapshot_date=#{p.day}",
                map("day", day))
            >= 4);
    assertEquals(
        entries,
        db.count("SELECT COUNT(*) FROM ledger_entries WHERE point_id=#{p.id}", map("id", buyer)));
    assertEquals(balance, ledger.wallet(buyer, "REWARD").get("balance"));
    assertTrue(bool(orders.order(text(order, "id")).get("archived")));
    assertEquals(
        0,
        ((Number) queries.page("orders", Map.of("search", text(order, "id"))).get("total"))
            .intValue());
    assertEquals(
        1,
        ((Number)
                queries
                    .page("orders", Map.of("search", text(order, "id"), "includeArchived", "true"))
                    .get("total"))
            .intValue());
  }

  @Test
  void scopedDashboardIncludesTheFirstSecondOfTheBusinessDay() {
    String point = point(1, "NONE", null, "0"), department = "日界线复核";
    db.exec(
        "UPDATE member_points SET department=#{p.department},created_at=#{p.at} WHERE id=#{p.id}",
        map(
            "id",
            point,
            "department",
            department,
            "at",
            java.sql.Timestamp.valueOf(Store.today().atStartOfDay())));
    actor(
        new Actors.Actor(
            "TIME_SCOPE",
            "time_scope",
            "部门财务",
            "FINANCE",
            "",
            false,
            Actors.PERMISSIONS.get("FINANCE"),
            "DEPARTMENT",
            department));
    var result =
        queries.dashboard(
            Map.of("start", Store.today().toString(), "end", Store.today().toString()));
    assertEquals(1L, ((Map<?, ?>) result.get("totals")).get("newMembers"));
  }

  @Test
  void disablingStaffPreservesTheRecordAndProtectsTheCurrentAdministrator() {
    var staff =
        run(
            "STAFF",
            () ->
                management.user(
                    null,
                    map(
                        "username",
                        "disable_" + seq.incrementAndGet(),
                        "displayName",
                        "停用复核",
                        "phone",
                        "000123456789",
                        "role",
                        "OPERATOR",
                        "department",
                        "初语山言",
                        "password",
                        "Testing!2026")));
    run("DISABLE", () -> management.disableUser(text(staff, "id"), map("reason", "停用复核")));
    assertFalse(
        bool(
            db.required("SELECT active FROM app_users WHERE id=#{p.id}", map("id", staff.get("id")))
                .get("active")));
    assertThrows(
        BusinessException.class,
        () -> run("DISABLE", () -> management.disableUser("U_ADMIN", map("reason", "保护当前账号"))));
  }

  @Test
  void ledgerRowsWithTheSameTimestampFollowPostingSequence() {
    String point = point(1, "NONE", null, "0");
    for (String amount : List.of("1", "2", "3"))
      run(
          "ADJUST",
          () ->
              funds.adjust(
                  map("pointId", point, "kind", "REWARD", "amount", amount, "reason", "流水顺序复核")));
    db.exec(
        "UPDATE ledger_entries SET created_at='2026-01-01 00:00:00' WHERE point_id=#{p.id}",
        map("id", point));
    var rows =
        (List<Map<String, Object>>) queries.page("ledger", Map.of("pointId", point)).get("items");
    eq("6", rows.get(0).get("balanceAfter"));
    eq("3", rows.get(1).get("balanceAfter"));
    eq("1", rows.get(2).get("balanceAfter"));
    assertTrue(
        ((Number) rows.get(0).get("entryNo")).longValue()
            > ((Number) rows.get(1).get("entryNo")).longValue());
  }
}
