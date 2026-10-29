package cn.chuyu.shared;

import static cn.chuyu.shared.BusinessException.require;
import static cn.chuyu.shared.Json.*;
import static cn.chuyu.shared.Money.*;

import cn.chuyu.funds.*;
import cn.chuyu.members.*;
import cn.chuyu.orders.*;
import cn.chuyu.reports.*;
import cn.chuyu.rewards.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
  private final Store db;
  private final Commands commands;
  private final MemberService members;
  private final PerformanceService performance;
  private final OrderService orders;
  private final FulfilmentService shipping;
  private final FundsService funds;
  private final RuleService rules;
  private final SettlementService settlements;
  private final QueryService queries;
  private final BCryptPasswordEncoder encoder;

  public ApiController(
      Store db,
      Commands commands,
      MemberService members,
      PerformanceService performance,
      OrderService orders,
      FulfilmentService shipping,
      FundsService funds,
      RuleService rules,
      SettlementService settlements,
      QueryService queries,
      BCryptPasswordEncoder encoder) {
    this.db = db;
    this.commands = commands;
    this.members = members;
    this.performance = performance;
    this.orders = orders;
    this.shipping = shipping;
    this.funds = funds;
    this.rules = rules;
    this.settlements = settlements;
    this.queries = queries;
    this.encoder = encoder;
  }

  @GetMapping("/config")
  public Object config() {
    return api(
        map(
            "demo",
            true,
            "transferEnabled",
            rules.settings().get("transferEnabled"),
            "ruleId",
            rules.currentId(),
            "rules",
            rules.current(),
            "roles",
            Actors.PERMISSIONS,
            "today",
            Store.today().toString(),
            "timezone",
            "Asia/Shanghai"));
  }

  @GetMapping("/lists/{resource}")
  public Object list(@PathVariable String resource, @RequestParam Map<String, String> q) {
    return api(queries.page(resource, q));
  }

  @GetMapping("/catalog")
  public Object catalog() {
    return api(
        map(
            "products",
            db.list("SELECT * FROM products WHERE active=TRUE ORDER BY id"),
            "shops",
            db.list(
                "SELECT id,nickname,phone,shop_type FROM member_points WHERE active=TRUE AND"
                    + " activated=TRUE AND shop_type<>'NONE' ORDER BY nickname")));
  }

  @GetMapping("/members/lookup")
  public Object lookup(@RequestParam String account) {
    require(account.length() >= 4, "请输入完整账户编号或手机号");
    var found =
        db.list(
            "SELECT id,nickname,phone,grade,shop_type,active FROM member_points WHERE"
                + " (id=#{p.account} OR phone=#{p.account}) AND id NOT IN ('SYSTEM','RECOVERY') AND"
                + " active=TRUE ORDER BY sequence_no LIMIT 20",
            map("account", account));
    return api(
        found.stream()
            .map(
                p -> {
                  String phone = text(p, "phone");
                  p.put(
                      "phone",
                      phone.length() > 6
                          ? phone.substring(0, 3) + "****" + phone.substring(phone.length() - 3)
                          : "***" + phone.substring(phone.length() - 3));
                  return p;
                })
            .toList());
  }

  @GetMapping("/orders/{id}")
  public Object order(@PathVariable String id) {
    var o = orders.order(id);
    orders.readAccess(o);
    Map<String, Object> result = new LinkedHashMap<>(o);
    result.put(
        "awards",
        db.list(
            "SELECT * FROM reward_awards WHERE order_id=#{p.id} ORDER BY created_at,id",
            map("id", id)));
    result.put(
        "shipments",
        db.list(
            "SELECT a.id,a.status,a.carrier,a.tracking,a.pickup,l.quantity,l.cancelled FROM"
                + " fulfilment_lines l JOIN fulfilment_applications a ON a.id=l.application_id"
                + " WHERE l.order_id=#{p.id}",
            map("id", id)));
    result.put(
        "availableQuantity",
        bool(o.get("cancelled")) ? 0 : integer(o.get("quantity")) - shipping.allocated(id));
    result.put(
        "history",
        db.list(
            "SELECT action,reason,created_at FROM audit_logs WHERE target_id=#{p.id} ORDER BY"
                + " created_at,id",
            map("id", id)));
    return api(result);
  }

  @GetMapping("/shipments/{id}")
  public Object shipment(@PathVariable String id) {
    var a = db.required("SELECT * FROM fulfilment_applications WHERE id=#{p.id}", map("id", id));
    if (Actors.member()) members.own(text(a, "shopId"));
    else Actors.require("shipping:read");
    return api(map("application", a, "lines", shipping.lines(id)));
  }

  @GetMapping("/network/{id}")
  public Object network(@PathVariable String id, @RequestParam(defaultValue = "1") int page) {
    if (!Actors.member()) Actors.require("members:read");
    else {
      var ps = performance.points();
      Map<String, Map<String, Object>> ix = new HashMap<>();
      ps.forEach(p -> ix.put(text(p, "id"), p));
      require(
          ps.stream().filter(Actors::owns).anyMatch(p -> performance.below(id, text(p, "id"), ix)),
          "无权查看此推荐网络");
    }
    require(page >= 1 && page < 100000, "分页参数错误");
    var root = members.point(id);
    var children =
        db.list(
            "SELECT id,nickname,phone,parent_id,grade,director_grade,shop_type,active,(SELECT"
                + " COUNT(*) FROM member_points c WHERE c.parent_id=p.id) child_count FROM"
                + " member_points p WHERE parent_id=#{p.id} ORDER BY created_at,id LIMIT 100 OFFSET"
                + " #{p.offset}",
            map("id", id, "offset", (page - 1) * 100));
    return api(
        map(
            "root",
            map(
                "id",
                root.get("id"),
                "nickname",
                root.get("nickname"),
                "parentId",
                root.get("parentId")),
            "children",
            children,
            "total",
            db.count("SELECT COUNT(*) n FROM member_points WHERE parent_id=#{p.id}", map("id", id)),
            "page",
            page));
  }

  @GetMapping("/rules")
  public Object rules() {
    Actors.require("rules:read");
    return api(rules.list());
  }

  @GetMapping("/scenarios")
  public Object scenarios(@RequestParam(required = false) String ruleId) {
    Actors.require("rules:read");
    return api(ScenarioCatalog.run(ruleId == null ? rules.current() : rules.get(ruleId)));
  }

  @GetMapping("/batches/{id}")
  public Object batch(@PathVariable String id) {
    Actors.require("funds:read");
    return api(settlements.detail(id));
  }

  @GetMapping("/reports")
  public Object reports(@RequestParam Map<String, String> q) {
    return api(queries.dashboard(q));
  }

  @GetMapping("/reconciliation")
  public Object reconciliation() {
    return api(queries.reconciliation());
  }

  @SuppressWarnings("unchecked")
  @GetMapping("/exports/{resource}")
  public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
      export(@PathVariable String resource, @RequestParam Map<String, String> q) {
    Actors.require("reports:export");
    Map<String, String> filters = new HashMap<>(q);
    filters.put("size", "5000");
    filters.put("page", "1");
    var countFilters = new HashMap<>(filters);
    countFilters.put("size", "1");
    require(
        ((Number) queries.page(resource, countFilters).get("total")).longValue() <= 1000000,
        "单次最多导出100万条，请缩小筛选范围");
    var first = (List<Map<String, Object>>) queries.exportPage(resource, filters).get("items");
    var actor = Actors.current();
    org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body =
        output -> {
          org.springframework.security.core.context.SecurityContextHolder.getContext()
              .setAuthentication(
                  new org.springframework.security.authentication
                      .UsernamePasswordAuthenticationToken(actor, null, List.of()));
          try {
            Iterable<Map<String, Object>> rows =
                () ->
                    new Iterator<>() {
                      List<Map<String, Object>> chunk = first;
                      int index = 0, page = 1;

                      public boolean hasNext() {
                        if (index < chunk.size()) return true;
                        if (chunk.size() < 5000 || page >= 200) return false;
                        filters.put("page", "" + (++page));
                        chunk =
                            (List<Map<String, Object>>)
                                queries.exportPage(resource, filters).get("items");
                        index = 0;
                        return !chunk.isEmpty();
                      }

                      public Map<String, Object> next() {
                        if (!hasNext()) throw new NoSuchElementException();
                        return chunk.get(index++);
                      }
                    };
            StreamXlsx.write(rows, output);
          } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
          }
        };
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"chuyu-" + resource + ".xlsx\"")
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(body);
  }

  @PostMapping("/{resource}")
  public Object create(
      @PathVariable String resource,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> b) {
    return api(
        commands.run(
            key,
            resource,
            b,
            () ->
                switch (resource) {
                  case "members" -> members.create(b);
                  case "quotes" -> orders.quote(b);
                  case "orders" -> orders.create(b);
                  case "transfers" -> funds.transfer(b);
                  case "conversions" -> funds.convert(b);
                  case "withdrawals" -> funds.withdraw(b);
                  case "topups" -> funds.requestTopup(b);
                  case "funds-adjustments" -> funds.adjust(b);
                  case "stock-transfers" -> funds.stockTransfer(b);
                  case "shipments" -> shipping.apply(b);
                  case "previews" -> settlements.preview(b);
                  case "rules" -> rules.draft(b);
                  case "products" -> product(null, b);
                  case "system-users" -> user(null, b);
                  default -> throw new BusinessException(404, "操作不存在");
                }));
  }

  @PostMapping("/{resource}/{id}/{action}")
  public Object action(
      @PathVariable String resource,
      @PathVariable String id,
      @PathVariable String action,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody(required = false) Map<String, Object> body) {
    Map<String, Object> b = body == null ? Map.of() : body;
    return api(
        commands.run(
            key,
            resource + ":" + id + ":" + action,
            b,
            () ->
                switch (resource + ":" + action) {
                  case "orders:pay" -> orders.pay(id);
                  case "orders:activate" -> orders.activate(id);
                  case "orders:cancel" -> orders.cancel(id, b);
                  case "members:update" -> members.update(id, b);
                  case "members:reset-password" -> members.resetPassword(id, text(b, "reason"));
                  case "withdrawals:review" -> funds.reviewWithdrawal(id, b);
                  case "topups:review" -> funds.reviewTopup(id, b);
                  case "shipments:review" -> shipping.review(id, b);
                  case "shipments:ship" -> shipping.ship(id, b);
                  case "rules:update" -> rules.save(id, b);
                  case "rules:test" -> rules.test(id);
                  case "rules:publish" -> rules.publish(id);
                  case "batches:post" -> settlements.post(id);
                  case "settings:update" -> setting(b);
                  case "products:update" -> product(id, b);
                  case "system-users:update" -> user(id, b);
                  default -> throw new BusinessException(404, "操作不存在");
                }));
  }

  private Map<String, Object> setting(Map<String, Object> b) {
    Actors.require("rules:write");
    require(!text(b, "reason").isBlank(), "请填写开关变更原因");
    var before = rules.settings();
    db.exec(
        "UPDATE app_settings SET transfer_enabled=#{p.enabled},revision=revision+1 WHERE id=1",
        map("enabled", bool(b.get("transferEnabled"))));
    db.audit("TRANSFER_SWITCH", "1", before, rules.settings(), text(b, "reason"));
    return rules.settings();
  }

  private Map<String, Object> product(String id, Map<String, Object> b) {
    Actors.require("rules:write");
    String name = text(b, "name");
    require(!name.isBlank(), "请输入商品名称");
    String diff = write(b.getOrDefault("differential", List.of(1, 2, 3, 4, 5)));
    var values = read(diff, List.class);
    require(values.size() == 5, "请配置二至六星的五档固定级差");
    java.math.BigDecimal last = ZERO;
    for (Object v : values) {
      require(d(v).compareTo(last) >= 0, "按瓶级差必须递增");
      last = d(v);
    }
    var p =
        map(
            "id",
            id == null ? "PR" + id() : id,
            "name",
            name,
            "first",
            input(b.get("firstPrice")),
            "one",
            input(b.get("repeatOne")),
            "other",
            input(b.get("repeatOther")),
            "limit1",
            integer(b.get("limitOne")),
            "limit2",
            integer(b.get("limitOther")),
            "subsidy1",
            money(b.get("subsidyOne")),
            "rate",
            d(b.get("subsidyRate")),
            "diff",
            diff,
            "weight",
            d(b.get("weightKg")),
            "active",
            b.getOrDefault("active", true));
    require(
        integer(p.get("limit1")) >= 1
            && integer(p.get("limit2")) >= 1
            && d(p.get("weight")).signum() > 0
            && d(p.get("rate")).signum() >= 0
            && d(p.get("rate")).compareTo(d(1)) <= 0
            && d(p.get("subsidy1")).signum() >= 0,
        "商品配置范围错误");
    if (id == null)
      db.exec(
          "INSERT INTO"
              + " products(id,name,first_price,repeat_one,repeat_other,limit_one,limit_other,subsidy_one,subsidy_rate,differential_json,weight_kg,active)"
              + " VALUES(#{p.id},#{p.name},#{p.first},#{p.one},#{p.other},#{p.limit1},#{p.limit2},#{p.subsidy1},#{p.rate},#{p.diff},#{p.weight},#{p.active})",
          p);
    else
      db.exec(
          "UPDATE products SET"
              + " name=#{p.name},first_price=#{p.first},repeat_one=#{p.one},repeat_other=#{p.other},limit_one=#{p.limit1},limit_other=#{p.limit2},subsidy_one=#{p.subsidy1},subsidy_rate=#{p.rate},differential_json=#{p.diff},weight_kg=#{p.weight},active=#{p.active}"
              + " WHERE id=#{p.id}",
          p);
    db.audit("PRODUCT_EDIT", text(p, "id"), null, p, text(b, "reason"));
    return map("id", p.get("id"));
  }

  private Map<String, Object> user(String id, Map<String, Object> b) {
    Actors.require("system:write");
    String role = text(b, "role");
    require(Actors.PERMISSIONS.containsKey(role) && !role.equals("MEMBER"), "请选择后台角色");
    if (id == null) {
      String username = text(b, "username"), password = text(b, "password");
      require(
          username.matches("[A-Za-z0-9_]{4,32}")
              && password.length() >= 10
              && password.length() <= 72,
          "账号需4至32位字母数字，新密码至少10位");
      id = id();
      db.exec(
          "INSERT INTO"
              + " app_users(id,username,display_name,phone,password_hash,payment_hash,role,must_change_password)"
              + " VALUES(#{p.id},#{p.username},#{p.name},#{p.phone},#{p.password},#{p.payment},#{p.role},TRUE)",
          map(
              "id",
              id,
              "username",
              username,
              "name",
              text(b, "displayName"),
              "phone",
              text(b, "phone"),
              "password",
              encoder.encode(password),
              "payment",
              encoder.encode("123456"),
              "role",
              role));
    } else {
      require(!id.equals(Actors.current().id()), "请由其他管理员修改当前账号权限");
      db.exec(
          "UPDATE app_users SET role=#{p.role},active=#{p.active},department=#{p.department} WHERE"
              + " id=#{p.id}",
          map(
              "id",
              id,
              "role",
              role,
              "active",
              bool(b.get("active")),
              "department",
              text(b, "department")));
      db.exec("DELETE FROM auth_sessions WHERE user_id=#{p.id}", map("id", id));
    }
    db.audit("SYSTEM_USER_EDIT", id, null, map("role", role), text(b, "reason"));
    return map("id", id);
  }
}
