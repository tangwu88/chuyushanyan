package cn.chuyu.rewards;

import static cn.chuyu.shared.Json.*;

import cn.chuyu.shared.*;
import cn.chuyu.system.*;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SettlementJobs {
  private final ManagementService management;
  private final OperationsService operations;
  private final boolean enabled;

  public SettlementJobs(
      ManagementService management, OperationsService operations, Environment env) {
    this.management = management;
    this.operations = operations;
    enabled = env.getProperty("chuyu.scheduler-enabled", Boolean.class, false);
  }

  @Scheduled(cron = "* * * * * *", zone = "Asia/Shanghai")
  public void tick() {
    if (!enabled) return;
    LocalDateTime now = LocalDateTime.now(Store.ZONE).withNano(0);
    for (var job : management.rows("job-definitions", true)) {
      if (!now.equals(CronExpression.parse(text(job, "cron")).next(now.minusSeconds(1)))) continue;
      var previous = SecurityContextHolder.getContext().getAuthentication();
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  new Actors.Actor("SCHEDULER", "scheduler", "定时任务", "ADMIN", "", false),
                  null,
                  List.of()));
      try {
        operations.runJob(text(job, "id"), "CRON-" + text(job, "id") + "-" + now, Map.of());
      } catch (RuntimeException ignored) {
        /* The failed attempt and its message remain in job_runs. */
      } finally {
        SecurityContextHolder.getContext().setAuthentication(previous);
      }
    }
  }
}
