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
      boolean mustChangePassword,
      Set<String> permissions,
      String dataScope,
      String department) {
    public Actor(
        String id,
        String username,
        String displayName,
        String role,
        String ownerId,
        boolean mustChangePassword) {
      this(
          id,
          username,
          displayName,
          role,
          ownerId,
          mustChangePassword,
          PERMISSIONS.getOrDefault(role, Set.of()),
          "ALL",
          "初语山言");
    }
  }

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
    var p = current().permissions();
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

  public static boolean canPoint(Map<String, Object> point) {
    if (member()) return owns(point);
    return switch (current().dataScope()) {
      case "DEPARTMENT" -> Objects.equals(current().department(), point.get("department"));
      case "SELF" -> Objects.equals(current().id(), point.get("createdBy"));
      default -> true;
    };
  }

  public static void requirePoint(Map<String, Object> point) {
    if (!canPoint(point)) throw new BusinessException(403, "此账户不在当前数据权限范围内");
  }

  public static void requireAnyPoint(Map<String, Object> one, Map<String, Object> two) {
    if (!canPoint(one) && !canPoint(two)) throw new BusinessException(403, "此业务不在当前数据权限范围内");
  }

  public static void requireGlobal(String permission) {
    require(permission);
    if (!current().dataScope().equals("ALL")) throw new BusinessException(403, "此操作需要全部数据权限");
  }

  public static String sqlScope(String alias, String column, Map<String, Object> args) {
    if (member() || current().dataScope().equals("ALL")) return "";
    args.put("scopeDepartment", current().department());
    args.put("scopeCreator", current().id());
    return " AND EXISTS(SELECT 1 FROM member_points data_scope WHERE data_scope.id="
        + alias
        + "."
        + column
        + (current().dataScope().equals("DEPARTMENT")
            ? " AND data_scope.department=#{p.scopeDepartment}"
            : " AND data_scope.created_by=#{p.scopeCreator}")
        + ")";
  }

  public static String orderScope(String alias, Map<String, Object> args) {
    String first = sqlScope(alias, "point_id", args);
    return first.isBlank()
        ? ""
        : " AND ("
            + first.substring(5)
            + " OR "
            + sqlScope(alias, "shop_id", args).substring(5)
            + ")";
  }
}
