package cn.chuyu.shared;

import static cn.chuyu.shared.BusinessException.require;
import static cn.chuyu.shared.Json.*;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final Store db;
  private final BCryptPasswordEncoder encoder;
  private final AccessPolicy policy;

  public AuthController(Store db, BCryptPasswordEncoder encoder, AccessPolicy policy) {
    this.db = db;
    this.encoder = encoder;
    this.policy = policy;
  }

  @PostMapping("/login")
  public Object login(
      @RequestBody Map<String, Object> b, jakarta.servlet.http.HttpServletRequest request) {
    var u =
        db.one(
            "SELECT * FROM app_users WHERE username=#{p.username} AND active=TRUE",
            map("username", text(b, "username")));
    if (u != null
        && u.get("lockedUntil") != null
        && LocalDateTime.parse(text(u, "lockedUntil")).isAfter(LocalDateTime.now(Store.ZONE))) {
      loginLog(text(b, "username"), u, request, "FAILED", "账号暂时锁定");
      throw new BusinessException("账号暂时锁定，请稍后重试或联系管理员");
    }
    if (u == null || !encoder.matches(text(b, "password"), text(u, "passwordHash"))) {
      if (u != null)
        db.exec(
            "UPDATE app_users SET locked_until=CASE WHEN failed_attempts>=4 THEN #{p.until} ELSE"
                + " locked_until END,failed_attempts=failed_attempts+1 WHERE id=#{p.id}",
            map(
                "id",
                u.get("id"),
                "until",
                Timestamp.valueOf(LocalDateTime.now(Store.ZONE).plusMinutes(15))));
      loginLog(text(b, "username"), u, request, "FAILED", "账号或密码错误");
      throw new BusinessException("账号或密码错误");
    }
    if (policy.actor(u).permissions().isEmpty()) {
      loginLog(text(b, "username"), u, request, "FAILED", "账号角色已停用");
      throw new BusinessException("账号角色已停用");
    }
    if (text(u, "role").equals("MEMBER")
        && !(db.count(
                "SELECT COUNT(*) n FROM member_points WHERE owner_id=#{p.owner} AND active=TRUE",
                map("owner", u.get("ownerId")))
            > 0)) {
      loginLog(text(b, "username"), u, request, "FAILED", "账号不存在");
      throw new BusinessException("账号不存在");
    }
    String token = id() + id();
    db.exec(
        "UPDATE app_users SET failed_attempts=0,locked_until=NULL WHERE id=#{p.id}",
        map("id", u.get("id")));
    db.exec(
        "INSERT INTO auth_sessions(token_hash,user_id,expires_at)"
            + " VALUES(#{p.hash},#{p.id},#{p.expiry})",
        map(
            "hash",
            hash(token),
            "id",
            u.get("id"),
            "expiry",
            Timestamp.valueOf(LocalDateTime.now(Store.ZONE).plusHours(12))));
    loginLog(text(b, "username"), u, request, "SUCCESS", "登录成功");
    return map("token", token, "user", safe(u));
  }

  private void loginLog(
      String username,
      Map<String, Object> user,
      jakarta.servlet.http.HttpServletRequest request,
      String status,
      String message) {
    String ua = Optional.ofNullable(request.getHeader("User-Agent")).orElse("");
    db.exec(
        "INSERT INTO login_logs(id,username,user_id,ip,user_agent,status,message)"
            + " VALUES(#{p.id},#{p.username},#{p.user},#{p.ip},#{p.ua},#{p.status},#{p.message})",
        map(
            "id",
            id(),
            "username",
            username.substring(0, Math.min(64, username.length())),
            "user",
            user == null ? null : user.get("id"),
            "ip",
            request.getRemoteAddr(),
            "ua",
            ua.substring(0, Math.min(512, ua.length())),
            "status",
            status,
            "message",
            message));
  }

  private Map<String, Object> safe(Map<String, Object> u) {
    return map(
        "id",
        u.get("id"),
        "username",
        u.get("username"),
        "displayName",
        u.get("displayName"),
        "role",
        u.get("role"),
        "ownerId",
        u.get("ownerId"),
        "mustChangePassword",
        u.get("mustChangePassword"),
        "permissions",
        policy.actor(u).permissions(),
        "department",
        u.get("department"),
        "dataScope",
        policy.actor(u).dataScope());
  }

  @GetMapping("/me")
  public Object me() {
    return safe(
        db.required("SELECT * FROM app_users WHERE id=#{p.id}", map("id", Actors.current().id())));
  }

  @PostMapping("/password")
  @org.springframework.transaction.annotation.Transactional
  public Object password(@RequestBody Map<String, Object> b) {
    var u =
        db.required("SELECT * FROM app_users WHERE id=#{p.id}", map("id", Actors.current().id()));
    String next = text(b, "newPassword");
    require(encoder.matches(text(b, "oldPassword"), text(u, "passwordHash")), "原密码错误");
    require(next.length() >= 10 && next.length() <= 72, "新密码长度需为10至72位");
    String payment = text(b, "newPaymentPassword");
    require(payment.isBlank() || payment.matches("[0-9]{6}"), "支付密码需为6位数字");
    db.exec(
        "UPDATE app_users SET"
            + " password_hash=#{p.password},payment_hash=#{p.payment},must_change_password=FALSE"
            + " WHERE id=#{p.id}",
        map(
            "id",
            Actors.current().id(),
            "password",
            encoder.encode(next),
            "payment",
            payment.isBlank() ? u.get("paymentHash") : encoder.encode(payment)));
    db.audit("PASSWORD_CHANGE", Actors.current().id(), null, null, "本人修改密码");
    return map("message", "密码已更新");
  }

  @PostMapping("/logout")
  public Object logout(@RequestHeader("Authorization") String token) {
    db.exec(
        "DELETE FROM auth_sessions WHERE token_hash=#{p.hash}",
        map("hash", hash(token.substring(7))));
    return map("message", "已退出");
  }
}
