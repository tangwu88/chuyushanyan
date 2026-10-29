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

  public AuthController(Store db, BCryptPasswordEncoder encoder) {
    this.db = db;
    this.encoder = encoder;
  }

  @PostMapping("/login")
  public Object login(@RequestBody Map<String, Object> b) {
    var u =
        db.one(
            "SELECT * FROM app_users WHERE username=#{p.username} AND active=TRUE",
            map("username", text(b, "username")));
    require(u != null && encoder.matches(text(b, "password"), text(u, "passwordHash")), "账号或密码错误");
    if (text(u, "role").equals("MEMBER"))
      require(
          db.count(
                  "SELECT COUNT(*) n FROM member_points WHERE owner_id=#{p.owner} AND active=TRUE",
                  map("owner", u.get("ownerId")))
              > 0,
          "账号不存在");
    String token = id() + id();
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
    return map("token", token, "user", safe(u));
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
        Actors.PERMISSIONS.getOrDefault(text(u, "role"), Set.of()));
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
