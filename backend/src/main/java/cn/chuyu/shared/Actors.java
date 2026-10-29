package cn.chuyu.shared;

import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;

public final class Actors {
  public record Actor(
      String id,
      String username,
      String displayName,
      String role,
      String ownerId,
      boolean mustChangePassword) {}

  public static final Map<String, Set<String>> PERMISSIONS =
      Map.of(
          "ADMIN", Set.of("*"),
          "MANAGER",
              Set.of(
                  "members:read",
                  "members:write",
                  "orders:read",
                  "orders:create",
                  "orders:activate",
                  "orders:cancel",
                  "funds:read",
                  "funds:write",
                  "shipping:read",
                  "shipping:review",
                  "shipping:ship",
                  "rules:read",
                  "rules:write",
                  "reports:read",
                  "reports:export",
                  "audit:read"),
          "OPERATOR",
              Set.of(
                  "members:read",
                  "members:write",
                  "orders:read",
                  "orders:create",
                  "orders:activate",
                  "shipping:read",
                  "rules:read",
                  "reports:read"),
          "FINANCE",
              Set.of(
                  "members:read",
                  "orders:read",
                  "funds:read",
                  "funds:write",
                  "withdraw:review",
                  "rules:read",
                  "reports:read",
                  "reports:export",
                  "audit:read"),
          "SHIPPING",
              Set.of(
                  "orders:read",
                  "members:read",
                  "shipping:read",
                  "shipping:review",
                  "shipping:ship"),
          "AUDITOR",
              Set.of(
                  "members:read",
                  "orders:read",
                  "funds:read",
                  "shipping:read",
                  "rules:read",
                  "reports:read",
                  "reports:export",
                  "audit:read"),
          "VIEWER", Set.of("reports:read"),
          "MEMBER", Set.of("self"));

  private Actors() {}

  public static Actor current() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof Actor a))
      throw new BusinessException(401, "请先登录");
    return a;
  }

  public static boolean has(String permission) {
    var p = PERMISSIONS.getOrDefault(current().role(), Set.of());
    return p.contains("*") || p.contains(permission);
  }

  public static void require(String permission) {
    if (!has(permission)) throw new BusinessException(403, "没有此操作权限");
  }

  public static boolean owns(Map<String, Object> point) {
    return Objects.equals(current().ownerId(), point.get("ownerId"));
  }

  public static boolean member() {
    return current().role().equals("MEMBER");
  }
}
