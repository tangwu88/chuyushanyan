package cn.chuyu.shared;

import static cn.chuyu.shared.Json.*;

import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AccessPolicy {
  private final Store db;

  public AccessPolicy(Store db) {
    this.db = db;
  }

  @SuppressWarnings("unchecked")
  public Actors.Actor actor(Map<String, Object> user) {
    String role = text(user, "role");
    Set<String> permissions = Actors.PERMISSIONS.getOrDefault(role, Set.of());
    String scope = "ALL";
    var row =
        db.one(
            "SELECT * FROM managed_configs WHERE resource='roles' AND id=#{p.id}", map("id", role));
    if (row != null && !role.equals("ADMIN")) {
      if (!bool(row.get("active")) || bool(row.get("deleted"))) permissions = Set.of();
      else {
        var config = read(text(row, "dataJson"), Map.class);
        permissions = new HashSet<>((List<String>) config.getOrDefault("permissions", List.of()));
        scope = text(config, "dataScope");
        if (scope.isBlank()) scope = "ALL";
      }
    }
    if (!role.equals("ADMIN")) {
      permissions = new HashSet<>(permissions);
      for (var menu :
          db.list(
              "SELECT data_json FROM managed_configs WHERE resource='menus' AND active=FALSE AND"
                  + " deleted=FALSE")) {
        var data = read(text(menu, "dataJson"), Map.class);
        if (text(data, "type").equals("BUTTON")) permissions.remove(text(data, "permission"));
      }
    }
    return new Actors.Actor(
        text(user, "id"),
        text(user, "username"),
        text(user, "displayName"),
        role,
        text(user, "ownerId"),
        bool(user.get("mustChangePassword")),
        Set.copyOf(permissions),
        scope,
        text(user, "department"));
  }
}
