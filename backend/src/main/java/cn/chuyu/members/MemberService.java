package cn.chuyu.members;

import static cn.chuyu.shared.BusinessException.require;
import static cn.chuyu.shared.Json.*;
import static cn.chuyu.shared.Money.*;

import cn.chuyu.rewards.RuleService;
import cn.chuyu.shared.*;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class MemberService {
  private final Store db;
  private final RuleService rules;
  private final PerformanceService performance;
  private final BCryptPasswordEncoder encoder;

  public MemberService(
      Store db, RuleService rules, PerformanceService performance, BCryptPasswordEncoder encoder) {
    this.db = db;
    this.rules = rules;
    this.performance = performance;
    this.encoder = encoder;
  }

  public Map<String, Object> point(String id) {
    return db.required(
        "SELECT * FROM member_points WHERE id=#{p.id} AND id NOT IN ('SYSTEM','RECOVERY')",
        map("id", id));
  }

  public void own(String id) {
    require(Actors.owns(point(id)), "只能操作本人管理的账户");
  }

  public void readAccess(String id) {
    if (Actors.member()) own(id);
    else Actors.require("members:read");
  }

  public List<Map<String, Object>> list() {
    return performance.views().stream().filter(p -> !Actors.member() || Actors.owns(p)).toList();
  }

  public Map<String, Object> create(Map<String, Object> b) {
    boolean sub = bool(b.get("subPoint")), empty = bool(b.get("emptyPoint"));
    if (!Actors.member()) Actors.require("members:write");
    else require(!empty, "会员不能创建空点");
    String phone = text(b, "phone"), name = text(b, "nickname"), parent = text(b, "parentId");
    require(phone.matches("[0-9]{6,24}"), "手机号需为6至24位数字，支持00开头");
    require(!name.isBlank() && name.length() <= 100, "请输入昵称");
    Map<String, Object> up = parent.isBlank() ? null : point(parent);
    if (up != null) require(bool(up.get("active")), "推荐人已冻结");
    String owner;
    int sequence = 1;
    if (sub) {
      require(up != null, "小号必须选择同手机号的邀请人");
      if (Actors.member()) own(parent);
      require(phone.equals(text(up, "phone")), "小号邀请人错误");
      owner = text(up, "ownerId");
      sequence =
          (int)
                  db.count(
                      "SELECT COUNT(*) n FROM member_points WHERE owner_id=#{p.owner}",
                      map("owner", owner))
              + 1;
      require(sequence <= rules.current().maxPoints, "已达到主小号数量上限");
      require(
          db.count(
                  "SELECT COUNT(*) n FROM member_points WHERE parent_id=#{p.parent} AND"
                      + " owner_id=#{p.owner}",
                  map("parent", parent, "owner", owner))
              < 3,
          "每个点位最多邀请3个同手机号小号");
      int depth = 0;
      var at = up;
      while (at != null && text(at, "ownerId").equals(owner)) {
        depth++;
        String p = text(at, "parentId");
        at = p.isBlank() ? null : point(p);
      }
      require(depth <= 2, "主小号采用主号、三点、九点结构，请选择上层点位");
    } else {
      require(
          db.count(
                  "SELECT COUNT(*) n FROM member_points WHERE phone=#{p.phone}",
                  map("phone", phone))
              == 0,
          "此手机号已有主号，请使用新增小号");
      if (Actors.member()) require(up != null && Actors.owns(up), "请选择本人点位作为推荐人");
      owner = id();
      String password = phone.substring(phone.length() - 6);
      db.exec(
          "INSERT INTO"
              + " app_users(id,username,display_name,phone,password_hash,payment_hash,role,owner_id,must_change_password)"
              + " VALUES(#{p.id},#{p.username},#{p.name},#{p.phone},#{p.password},#{p.password},'MEMBER',#{p.owner},TRUE)",
          map(
              "id",
              id(),
              "username",
              phone,
              "name",
              name,
              "phone",
              phone,
              "password",
              encoder.encode(password),
              "owner",
              owner));
    }
    String id = "CY" + id().substring(0, 10).toUpperCase(Locale.ROOT);
    db.exec(
        "INSERT INTO"
            + " member_points(id,owner_id,phone,nickname,parent_id,is_main,sequence_no,empty_point)"
            + " VALUES(#{p.id},#{p.owner},#{p.phone},#{p.name},#{p.parent},#{p.main},#{p.sequence},#{p.empty})",
        map(
            "id",
            id,
            "owner",
            owner,
            "phone",
            phone,
            "name",
            name,
            "parent",
            parent.isBlank() ? null : parent,
            "main",
            !sub,
            "sequence",
            sequence,
            "empty",
            empty));
    for (String kind : List.of("REWARD", "RECHARGE"))
      db.exec(
          "INSERT INTO wallets(point_id,kind) VALUES(#{p.id},#{p.kind})",
          map("id", id, "kind", kind));
    db.recordPoint(id, java.time.LocalDateTime.now(Store.ZONE));
    db.audit("MEMBER_CREATE", id, null, b, "创建演示会员");
    return map("id", id, "ownerId", owner, "username", phone, "mustChangePassword", !sub);
  }

  public Map<String, Object> update(String id, Map<String, Object> b) {
    Actors.require("members:write");
    var p = point(id);
    String reason = text(b, "reason");
    require(!reason.isBlank(), "请填写变更原因");
    String phone = b.containsKey("phone") ? text(b, "phone") : text(p, "phone");
    String nickname = b.containsKey("nickname") ? text(b, "nickname") : text(p, "nickname");
    require(
        phone.matches("[0-9]{6,24}") && !nickname.isBlank() && nickname.length() <= 100,
        "昵称或手机号格式错误");
    require(
        db.count(
                "SELECT COUNT(*) n FROM member_points WHERE phone=#{p.phone} AND"
                    + " owner_id<>#{p.owner}",
                map("phone", phone, "owner", p.get("ownerId")))
            == 0,
        "此手机号已有其他账户，请先核对账户归属");
    if (!phone.equals(text(p, "phone"))) {
      db.exec(
          "UPDATE member_points SET phone=#{p.phone} WHERE owner_id=#{p.owner}",
          map("phone", phone, "owner", p.get("ownerId")));
      db.exec(
          "UPDATE app_users SET username=CASE WHEN username=#{p.old} THEN #{p.phone} ELSE username"
              + " END,phone=#{p.phone} WHERE owner_id=#{p.owner}",
          map("old", p.get("phone"), "phone", phone, "owner", p.get("ownerId")));
      for (var sibling :
          db.list(
              "SELECT id FROM member_points WHERE owner_id=#{p.owner}",
              map("owner", p.get("ownerId"))))
        db.recordPoint(text(sibling, "id"), java.time.LocalDateTime.now(Store.ZONE));
    }
    db.exec(
        "UPDATE member_points SET nickname=#{p.name} WHERE id=#{p.id}",
        map("id", id, "name", nickname));
    int grade = b.containsKey("grade") ? integer(b.get("grade")) : integer(p.get("grade")),
        director =
            b.containsKey("directorGrade")
                ? integer(b.get("directorGrade"))
                : integer(p.get("directorGrade"));
    require(grade >= 0 && grade <= 7 && director >= 0 && director <= 6, "等级范围不正确");
    String version =
        b.containsKey("gradeVersion") ? text(b, "gradeVersion") : text(p, "gradeVersion");
    require(rules.current().gradeVersions.containsKey(version), "等级版本不存在");
    String parent = b.containsKey("parentId") ? text(b, "parentId") : text(p, "parentId");
    if (!parent.isBlank()) {
      point(parent);
      Map<String, Map<String, Object>> ix = new HashMap<>();
      performance.points().forEach(q -> ix.put(text(q, "id"), q));
      require(!performance.below(parent, id, ix), "不能将推荐人设为本人或自己的下级");
      if (!bool(p.get("isMain")))
        require(text(point(parent), "ownerId").equals(text(p, "ownerId")), "小号必须在同手机号伞下");
    }
    String shop = b.containsKey("shopType") ? text(b, "shopType") : text(p, "shopType");
    require(Set.of("NONE", "SMALL", "BIG").contains(shop), "店铺类型错误");
    require(shop.equals("NONE") || bool(p.get("isMain")), "仅主号可以设置店铺身份");
    String identities = b.containsKey("identities") ? text(b, "identities") : text(p, "identities");
    for (String code : identities.split(","))
      require(code.isBlank() || rules.current().identities.containsKey(code), "特殊身份不存在");
    String override =
        b.containsKey("planOverride") ? text(b, "planOverride") : text(p, "planOverride");
    require(Set.of("AUTO", "YES", "NO").contains(override), "计划资格参数错误");
    var params =
        map(
            "id",
            id,
            "grade",
            grade,
            "director",
            director,
            "version",
            version,
            "parent",
            parent.isBlank() ? null : parent,
            "shop",
            shop,
            "identities",
            identities,
            "override",
            override,
            "active",
            b.containsKey("active") ? bool(b.get("active")) : bool(p.get("active")),
            "own",
            b.containsKey("manualOwn") ? money(b.get("manualOwn")) : d(p.get("manualOwn")),
            "team",
            b.containsKey("manualTeam") ? money(b.get("manualTeam")) : d(p.get("manualTeam")),
            "small",
            b.containsKey("manualSmall") ? money(b.get("manualSmall")) : d(p.get("manualSmall")),
            "fixed1",
            b.containsKey("fixedPlan1") ? b.get("fixedPlan1") : p.get("fixedPlan1"),
            "fixed2",
            b.containsKey("fixedPlan2") ? b.get("fixedPlan2") : p.get("fixedPlan2"));
    for (String f : List.of("fixed1", "fixed2"))
      if (params.get(f) != null) require(d(params.get(f)).signum() >= 0, "固定分红不能为负");
    db.exec(
        "UPDATE member_points SET"
            + " grade=#{p.grade},director_grade=#{p.director},grade_version=#{p.version},parent_id=#{p.parent},shop_type=#{p.shop},identities=#{p.identities},plan_override=#{p.override},active=#{p.active},manual_own=#{p.own},manual_team=#{p.team},manual_small=#{p.small},fixed_plan1=#{p.fixed1},fixed_plan2=#{p.fixed2}"
            + " WHERE id=#{p.id}",
        params);
    db.audit("MEMBER_EDIT", id, p, point(id), reason);
    db.recordPoint(id, java.time.LocalDateTime.now(Store.ZONE));
    if (!Objects.equals(p.get("parentId"), params.get("parent"))) performance.refreshGrades(true);
    return point(id);
  }

  public Map<String, Object> resetPassword(String id, String reason) {
    Actors.require("members:write");
    var p = point(id);
    require(!reason.isBlank(), "请填写重置原因");
    String phone = text(p, "phone");
    db.exec(
        "UPDATE app_users SET password_hash=#{p.hash},must_change_password=TRUE WHERE"
            + " owner_id=#{p.owner}",
        map(
            "hash",
            encoder.encode(phone.substring(phone.length() - 6)),
            "owner",
            p.get("ownerId")));
    db.exec(
        "DELETE FROM auth_sessions WHERE user_id IN (SELECT id FROM app_users WHERE"
            + " owner_id=#{p.owner})",
        map("owner", p.get("ownerId")));
    db.audit("PASSWORD_RESET", id, null, null, reason);
    return map("message", "已重置为手机号后六位，首次登录需修改");
  }
}
