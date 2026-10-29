package cn.chuyu.system;

import static cn.chuyu.shared.BusinessException.require;
import static cn.chuyu.shared.Json.*;
import static cn.chuyu.shared.Money.*;

import cn.chuyu.shared.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@Order(50)
public class ManagementService implements ApplicationRunner {
  public static final Set<String> RESOURCES =
      Set.of(
          "roles",
          "departments",
          "posts",
          "menus",
          "freights",
          "announcements",
          "banners",
          "agreements",
          "appearance",
          "parameters",
          "job-definitions");
  private static final Set<String> SYSTEM =
      Set.of("roles", "departments", "posts", "menus", "parameters", "job-definitions");
  private final Store db;
  private final BCryptPasswordEncoder encoder;

  public ManagementService(Store db, BCryptPasswordEncoder encoder) {
    this.db = db;
    this.encoder = encoder;
  }

  public void run(ApplicationArguments args) {
    Map<String, String> names =
        Map.of(
            "ADMIN",
            "超级管理员",
            "MANAGER",
            "初语山言",
            "OPERATOR",
            "初语山言小管理员",
            "FINANCE",
            "初语山言财务",
            "SHIPPING",
            "初语山言发货管理",
            "AUDITOR",
            "初语山言监察",
            "VIEWER",
            "普通角色");
    names.forEach(
        (code, name) ->
            seed(
                "roles",
                code,
                name,
                map(
                    "permissions",
                    Actors.PERMISSIONS.get(code).stream().sorted().toList(),
                    "dataScope",
                    "ALL"),
                true,
                0));
    seed("departments", "吉云集团", "吉云集团", map("parentId", ""), true, 0);
    for (String name : List.of("深圳总公司", "长沙分公司", "初语山言"))
      seed("departments", name, name, map("parentId", "吉云集团"), true, 0);
    for (String name : List.of("董事长", "项目经理", "人力资源", "普通员工"))
      seed("posts", name, name, Map.of(), true, 0);
    String[][] menus = {
      {"dashboard", "工作台", "reports:read"},
      {"members", "会员与关系", "members:read"},
      {"orders", "交易与履约", "orders:read"},
      {"finance", "资金与结算", "funds:read"},
      {"rules", "规则中心", "rules:read"},
      {"system", "系统设置", "audit:read"}
    };
    for (int i = 0; i < menus.length; i++)
      seed(
          "menus",
          menus[i][0],
          menus[i][1],
          map(
              "type",
              "MENU",
              "parentId",
              "",
              "route",
              "/" + menus[i][0],
              "component",
              menus[i][0],
              "permission",
              menus[i][2],
              "icon",
              ""),
          true,
          i);
    seed(
        "freights",
        "DEFAULT",
        "默认运费",
        map("channel", "初语山言", "maxWeight", "1000000", "amount", "0"),
        true,
        1000);
    seed(
        "appearance",
        "DEFAULT",
        "手机页面颜色",
        map("homeBackground", "#f4f5ed", "pageBackground", "#f4f5ed", "richBackground", "#ffffff"),
        true,
        0);
    seed(
        "parameters",
        "RETENTION",
        "数据保留",
        map("archiveDays", 90, "auditDays", 365, "loginDays", 90),
        true,
        0);
    for (String name : List.of("隐私政策", "服务协议"))
      seed("agreements", name, name, map("content", ""), false, 0);
    String[][] jobs = {
      {"DAILY", "每日奖金结算", "0 0 1 * * *"},
      {"MONTHLY", "计划1+2与月结身份", "0 5 1 1 * *"},
      {"MONTHLY_REPEAT", "七星复购月分红", "0 10 1 10 * *"},
      {"MEMBERS", "会员与店铺资格复核", "0 45 0 * * *"},
      {"UPGRADES", "同步升级审批处理", "0 50 0 * * *"},
      {"PERFORMANCE", "小市场业绩快照", "0 20 1 * * *"},
      {"RANKING", "会员排名计算", "0 25 1 * * *"},
      {"FINANCE", "财务与统计快照", "0 30 1 * * *"},
      {"ARCHIVE", "历史数据归档", "0 35 1 1 * *"},
      {"RECONCILIATION", "资金与退单扣减核对", "0 40 1 * * *"}
    };
    for (int i = 0; i < jobs.length; i++)
      seed(
          "job-definitions",
          jobs[i][0],
          jobs[i][1],
          map("cron", jobs[i][2], "zone", "Asia/Shanghai"),
          true,
          i);
    for (var row :
        db.list(
            "SELECT id,after_json FROM audit_logs WHERE action='STOCK_TRANSFER' AND after_json LIKE"
                + " '%paymentPassword%'")) {
      db.exec(
          "UPDATE audit_logs SET after_json=#{p.value} WHERE id=#{p.id}",
          map(
              "id",
              row.get("id"),
              "value",
              write(Store.redact(read(text(row, "afterJson"), Map.class)))));
    }
  }

  public void seed(
      String resource, String id, String name, Map<String, Object> data, boolean active, int sort) {
    if (db.count(
            "SELECT COUNT(*) FROM managed_configs WHERE resource=#{p.resource} AND id=#{p.id}",
            map("resource", resource, "id", id))
        == 0)
      db.exec(
          "INSERT INTO managed_configs(resource,id,name,data_json,active,sort_no)"
              + " VALUES(#{p.resource},#{p.id},#{p.name},#{p.json},#{p.active},#{p.sort})",
          map(
              "resource",
              resource,
              "id",
              id,
              "name",
              name,
              "json",
              write(data),
              "active",
              active,
              "sort",
              sort));
  }

  public void access(String resource, boolean write) {
    require(RESOURCES.contains(resource), "配置类型不存在");
    if (SYSTEM.contains(resource)) {
      if (write || !Actors.has("system:write"))
        Actors.require(write ? "system:write" : "audit:read");
    } else Actors.require(write ? "rules:write" : "rules:read");
    if (write) require(Actors.current().dataScope().equals("ALL"), "配置管理需要全部数据权限");
  }

  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> rows(String resource, boolean publicOnly) {
    var values =
        db.list(
            "SELECT * FROM managed_configs WHERE resource=#{p.resource} AND deleted=FALSE"
                + (publicOnly ? " AND active=TRUE" : "")
                + " ORDER BY sort_no,id",
            map("resource", resource));
    return values.stream()
        .map(
            row -> {
              Map<String, Object> out =
                  new LinkedHashMap<>((Map<String, Object>) read(text(row, "dataJson"), Map.class));
              out.putAll(row);
              out.remove("dataJson");
              return out;
            })
        .toList();
  }

  public Object list(String resource) {
    access(resource, false);
    return rows(resource, false);
  }

  public Map<String, Object> save(String resource, String record, Map<String, Object> b) {
    access(resource, true);
    String id = record == null ? text(b, "code") : record;
    if (id.isBlank()) id = id();
    require(id.length() <= 64, "编号过长");
    var before =
        record == null
            ? null
            : db.required(
                "SELECT * FROM managed_configs WHERE resource=#{p.resource} AND id=#{p.id}",
                map("resource", resource, "id", record));
    String name = text(b, "name");
    require(!name.isBlank() && name.length() <= 128, "请填写名称");
    Map<String, Object> data = new LinkedHashMap<>(b);
    for (String key :
        List.of(
            "id",
            "resource",
            "name",
            "code",
            "createdAt",
            "updatedAt",
            "reason",
            "active",
            "deleted",
            "sortNo")) data.remove(key);
    boolean active = !b.containsKey("active") || bool(b.get("active"));
    switch (resource) {
      case "roles" -> {
        require(id.matches("[A-Z][A-Z0-9_]{1,31}"), "角色编号需为2至32位大写字母、数字或下划线");
        require(!id.equals("MEMBER"), "会员角色不可修改");
        require(Set.of("ALL", "DEPARTMENT", "SELF").contains(text(b, "dataScope")), "请选择数据权限");
        require(b.get("permissions") instanceof Collection<?>, "请选择权限");
        Set<String> known = new HashSet<>();
        Actors.PERMISSIONS.values().forEach(known::addAll);
        known.add("system:write");
        for (Object p : (Collection<?>) b.get("permissions"))
          require(known.contains(p.toString()), "未知权限");
        if (id.equals("ADMIN")) {
          require(
              active
                  && ((Collection<?>) b.get("permissions")).contains("*")
                  && text(b, "dataScope").equals("ALL"),
              "超级管理员必须保留全部权限");
        } else require(!((Collection<?>) b.get("permissions")).contains("*"), "全部权限只用于超级管理员");
      }
      case "departments", "menus" -> {
        String parent = text(b, "parentId");
        Set<String> seen = new HashSet<>();
        seen.add(id);
        while (!parent.isBlank()) {
          require(seen.add(parent), "上级配置不能形成循环");
          String at = parent;
          var up =
              rows(resource, false).stream()
                  .filter(x -> text(x, "id").equals(at))
                  .findFirst()
                  .orElseThrow(() -> new BusinessException("上级配置不存在"));
          parent = text(up, "parentId");
        }
        if (resource.equals("menus")) {
          require(!id.equals("system") || active, "系统设置入口需保持启用");
          require(Set.of("DIRECTORY", "MENU", "BUTTON").contains(text(b, "type")), "菜单类型错误");
          String route = text(b, "route");
          require(
              route.isBlank()
                  || route.matches(
                      "/(dashboard|members|orders|finance|rules|system)(\\?[A-Za-z0-9_=&-]+)?"),
              "路由需指向已实现页面");
        }
      }
      case "freights" -> {
        require(!text(b, "channel").isBlank() && d(b.get("maxWeight")).signum() > 0, "请填写渠道与重量上限");
        data.put("amount", nonnegative(b.get("amount")));
      }
      case "banners" -> {
        validateImage(text(b, "imageUrl"));
        String link = text(b, "link");
        require(link.isBlank() || link.matches("https?://.+") || link.startsWith("/#/"), "链接格式错误");
      }
      case "announcements" -> require(!text(b, "content").isBlank(), "请填写消息内容");
      case "agreements" -> require(text(b, "content").length() <= 100000, "协议内容过长");
      case "appearance" -> {
        for (String key : List.of("homeBackground", "pageBackground", "richBackground"))
          require(text(b, key).matches("#[0-9a-fA-F]{6}"), "请选择有效颜色");
      }
      case "parameters" -> {
        for (String key : List.of("archiveDays", "auditDays", "loginDays"))
          require(integer(b.get(key)) >= 1 && integer(b.get(key)) <= 3650, "保留天数需为1至3650");
      }
      case "job-definitions" ->
          require(
              org.springframework.scheduling.support.CronExpression.isValidExpression(
                  text(b, "cron")),
              "Cron表达式无效");
      default -> {}
    }
    var params =
        map(
            "resource",
            resource,
            "id",
            id,
            "name",
            name,
            "json",
            write(data),
            "active",
            active,
            "sort",
            b.containsKey("sortNo") ? integer(b.get("sortNo")) : 0);
    if (before == null)
      db.exec(
          "INSERT INTO managed_configs(resource,id,name,data_json,active,sort_no)"
              + " VALUES(#{p.resource},#{p.id},#{p.name},#{p.json},#{p.active},#{p.sort})",
          params);
    else
      db.exec(
          "UPDATE managed_configs SET"
              + " name=#{p.name},data_json=#{p.json},active=#{p.active},sort_no=#{p.sort},updated_at=CURRENT_TIMESTAMP"
              + " WHERE resource=#{p.resource} AND id=#{p.id}",
          params);
    db.audit("CONFIG_EDIT", id, before, params, text(b, "reason"));
    return map("id", id);
  }

  private BigDecimal nonnegative(Object value) {
    BigDecimal n = d(value);
    require(n.signum() >= 0 && n.scale() <= 2, "金额需非负，最多两位小数");
    return money(n);
  }

  public static void validateImage(String image) {
    require(
        image.isBlank()
            || image.matches("https?://.+")
            || (image.matches("data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+")
                && image.length() < 2800000),
        "图片需为网址或不超过2MB的PNG/JPEG/WebP图片");
  }

  public Map<String, Object> delete(String resource, String id, Map<String, Object> b) {
    access(resource, true);
    require(
        !Set.of("appearance", "parameters", "job-definitions").contains(resource),
        "此配置可停用或编辑，不能删除");
    if (resource.equals("roles"))
      require(
          !Actors.PERMISSIONS.containsKey(id)
              && db.count("SELECT COUNT(*) FROM app_users WHERE role=#{p.role}", map("role", id))
                  == 0,
          "内置或已分配的角色不能删除");
    if (resource.equals("menus")) require(!id.equals("system"), "系统设置入口不能删除");
    if (resource.equals("posts"))
      require(
          db.count("SELECT COUNT(*) FROM app_users WHERE post_code=#{p.id}", map("id", id)) == 0,
          "岗位仍有关联用户，请先调整岗位");
    if (resource.equals("departments"))
      require(
          db.count("SELECT COUNT(*) FROM app_users WHERE department=#{p.id}", map("id", id)) == 0
              && db.count(
                      "SELECT COUNT(*) FROM member_points WHERE department=#{p.id}", map("id", id))
                  == 0,
          "部门仍有关联用户或会员");
    if (Set.of("departments", "menus").contains(resource))
      require(
          rows(resource, false).stream().noneMatch(r -> text(r, "parentId").equals(id)),
          "请先处理下级配置");
    var before =
        db.required(
            "SELECT * FROM managed_configs WHERE resource=#{p.resource} AND id=#{p.id}",
            map("resource", resource, "id", id));
    db.exec(
        "UPDATE managed_configs SET deleted=TRUE,updated_at=CURRENT_TIMESTAMP WHERE"
            + " resource=#{p.resource} AND id=#{p.id}",
        map("resource", resource, "id", id));
    db.audit("CONFIG_DELETE", id, before, null, text(b, "reason"));
    return map("id", id);
  }

  public Map<String, Object> content() {
    return map(
        "banners",
        rows("banners", true),
        "announcements",
        rows("announcements", true),
        "agreements",
        rows("agreements", true),
        "appearance",
        rows("appearance", true));
  }

  public Map<String, Object> user(String record, Map<String, Object> b) {
    Actors.requireGlobal("system:write");
    String role = text(b, "role");
    var roleRow =
        db.one(
            "SELECT * FROM managed_configs WHERE resource='roles' AND id=#{p.role} AND active=TRUE"
                + " AND deleted=FALSE",
            map("role", role));
    require(roleRow != null, "请选择有效后台角色");
    String id = record == null ? id() : record;
    var before =
        record == null
            ? null
            : db.required("SELECT * FROM app_users WHERE id=#{p.id}", map("id", id));
    if (before != null) {
      require(!text(before, "role").equals("MEMBER"), "请在会员管理中修改会员账号");
      require(!id.equals(Actors.current().id()), "请由其他管理员修改当前账号");
    }
    String phone =
        b.containsKey("phone") ? text(b, "phone") : before == null ? "" : text(before, "phone");
    require(phone.isBlank() || phone.matches("[0-9]{6,24}"), "电话格式错误");
    String department = b.containsKey("department") ? text(b, "department") : "初语山言";
    require(
        rows("departments", true).stream().anyMatch(d -> text(d, "id").equals(department)),
        "请选择有效部门");
    String post = text(b, "postCode");
    require(
        post.isBlank() || rows("posts", true).stream().anyMatch(p -> text(p, "id").equals(post)),
        "请选择有效岗位");
    String name = text(b, "displayName");
    require(!name.isBlank(), "请填写姓名");
    String password = text(b, "password"), username = text(b, "username");
    if (record == null)
      require(
          username.matches("[A-Za-z0-9_]{4,32}")
              && password.length() <= 72
              && (password.length() >= 10
                  || (bool(b.get("phonePassword"))
                      && phone.length() >= 6
                      && password.equals(phone.substring(phone.length() - 6)))),
          "账号需4至32位字母数字，新密码至少10位");
    boolean active = !b.containsKey("active") || bool(b.get("active"));
    if (before != null
        && text(before, "role").equals("ADMIN")
        && (!active || !role.equals("ADMIN")))
      require(
          db.count(
                  "SELECT COUNT(*) FROM app_users WHERE role='ADMIN' AND active=TRUE AND"
                      + " id<>#{p.id}",
                  map("id", id))
              > 0,
          "至少保留一名正常的超级管理员");
    var params =
        map(
            "id",
            id,
            "username",
            username,
            "name",
            name,
            "phone",
            phone,
            "role",
            role,
            "department",
            department,
            "email",
            text(b, "email"),
            "gender",
            text(b, "gender"),
            "post",
            text(b, "postCode"),
            "active",
            active);
    if (record == null) {
      params.put("password", encoder.encode(password));
      params.put("payment", encoder.encode("123456"));
      db.exec(
          "INSERT INTO"
              + " app_users(id,username,display_name,phone,password_hash,payment_hash,role,department,email,gender,post_code,active,must_change_password)"
              + " VALUES(#{p.id},#{p.username},#{p.name},#{p.phone},#{p.password},#{p.payment},#{p.role},#{p.department},#{p.email},#{p.gender},#{p.post},#{p.active},TRUE)",
          params);
    } else {
      db.exec(
          "UPDATE app_users SET"
              + " display_name=#{p.name},phone=#{p.phone},role=#{p.role},department=#{p.department},email=#{p.email},gender=#{p.gender},post_code=#{p.post},active=#{p.active}"
              + " WHERE id=#{p.id}",
          params);
      db.exec("DELETE FROM auth_sessions WHERE user_id=#{p.id}", map("id", id));
    }
    db.audit("SYSTEM_USER_EDIT", id, before, params, text(b, "reason"));
    return map("id", id);
  }

  public Map<String, Object> resetUser(String id, boolean unlock, Map<String, Object> b) {
    Actors.requireGlobal("system:write");
    var user = db.required("SELECT * FROM app_users WHERE id=#{p.id}", map("id", id));
    require(unlock || !text(user, "role").equals("MEMBER"), "请在会员管理中操作");
    if (unlock)
      db.exec(
          "UPDATE app_users SET failed_attempts=0,locked_until=NULL WHERE id=#{p.id}",
          map("id", id));
    else {
      String phone = text(user, "phone");
      require(phone.length() >= 6, "请先填写手机号");
      db.exec(
          "UPDATE app_users SET"
              + " password_hash=#{p.hash},must_change_password=TRUE,failed_attempts=0,locked_until=NULL"
              + " WHERE id=#{p.id}",
          map("id", id, "hash", encoder.encode(phone.substring(phone.length() - 6))));
      db.exec("DELETE FROM auth_sessions WHERE user_id=#{p.id}", map("id", id));
    }
    db.audit(unlock ? "ACCOUNT_UNLOCK" : "PASSWORD_RESET", id, null, null, text(b, "reason"));
    return map("id", id);
  }

  public Map<String, Object> disableUser(String id, Map<String, Object> b) {
    Actors.requireGlobal("system:write");
    require(!id.equals(Actors.current().id()), "不能停用当前登录账号");
    require(!text(b, "reason").isBlank(), "请填写停用原因");
    var before = db.required("SELECT * FROM app_users WHERE id=#{p.id}", map("id", id));
    require(!text(before, "role").equals("MEMBER"), "请在会员管理中操作");
    require(
        !text(before, "role").equals("ADMIN")
            || db.count(
                    "SELECT COUNT(*) FROM app_users WHERE role='ADMIN' AND active=TRUE AND"
                        + " id<>#{p.id}",
                    map("id", id))
                > 0,
        "至少保留一名正常的超级管理员");
    db.exec("UPDATE app_users SET active=FALSE WHERE id=#{p.id}", map("id", id));
    db.exec("DELETE FROM auth_sessions WHERE user_id=#{p.id}", map("id", id));
    db.audit("SYSTEM_USER_DISABLE", id, before, map("active", false), text(b, "reason"));
    return map("id", id);
  }
}
