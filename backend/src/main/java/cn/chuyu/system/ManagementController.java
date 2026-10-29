package cn.chuyu.system;

import static cn.chuyu.shared.Json.*;

import cn.chuyu.shared.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ManagementController {
  private final ManagementService management;
  private final Commands commands;
  private final OperationsService operations;

  public ManagementController(
      ManagementService management, Commands commands, OperationsService operations) {
    this.management = management;
    this.commands = commands;
    this.operations = operations;
  }

  @GetMapping("/content")
  public Object content() {
    return api(management.content());
  }

  @GetMapping("/management/{resource}")
  public Object list(@PathVariable String resource) {
    return api(management.list(resource));
  }

  @PostMapping("/management/{resource}")
  public Object create(
      @PathVariable String resource,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> b) {
    return api(
        commands.run(
            key, "CONFIG_CREATE:" + resource, b, () -> management.save(resource, null, b)));
  }

  @PostMapping("/management/{resource}/{id}/{action}")
  public Object action(
      @PathVariable String resource,
      @PathVariable String id,
      @PathVariable String action,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> b) {
    return api(
        commands.run(
            key,
            "CONFIG:" + resource + ":" + id + ":" + action,
            b,
            () ->
                switch (action) {
                  case "update" -> management.save(resource, id, b);
                  case "delete" -> management.delete(resource, id, b);
                  default -> throw new BusinessException(404, "操作不存在");
                }));
  }

  @PostMapping("/jobs/{id}/run")
  public Object run(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> b) {
    return api(operations.runJob(id, key, b));
  }

  @PostMapping("/sync-upgrades")
  public Object upgrade(
      @RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> b) {
    return api(commands.run(key, "SYNC_REQUEST", b, () -> operations.requestUpgrade(b)));
  }

  @PostMapping("/sync-upgrades/{id}/review")
  public Object review(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> b) {
    return api(commands.run(key, "SYNC_REVIEW:" + id, b, () -> operations.reviewUpgrade(id, b)));
  }

  @PostMapping("/system-users/import")
  public Object importUsers(
      @RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> b) {
    return api(commands.run(key, "USER_IMPORT", b, () -> operations.importUsers(b)));
  }

  @PostMapping("/logs/{resource}/{id}/delete")
  public Object deleteLogs(
      @PathVariable String resource,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> b) {
    return api(
        commands.run(
            key,
            "LOG_DELETE:" + resource + ":" + id,
            b,
            () -> operations.removeLogs(resource, id, b)));
  }
}
