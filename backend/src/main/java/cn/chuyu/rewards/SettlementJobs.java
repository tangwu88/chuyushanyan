package cn.chuyu.rewards;

import static cn.chuyu.shared.Json.*;

import cn.chuyu.shared.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SettlementJobs {
  private final Store db;
  private final Commands commands;
  private final SettlementService settlements;
  private final boolean enabled;

  public SettlementJobs(
      Store db, Commands commands, SettlementService settlements, Environment env) {
    this.db = db;
    this.commands = commands;
    this.settlements = settlements;
    this.enabled = env.getProperty("chuyu.scheduler-enabled", Boolean.class, false);
  }

  @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Shanghai")
  public void daily() {
    if (!enabled) return;
    process("DAILY", Store.today().minusDays(1).toString());
    if (Store.today().getDayOfMonth() == 1)
      process("MONTHLY", Store.today().minusMonths(1).toString().substring(0, 7));
    if (Store.today().getDayOfMonth() == 10)
      process("MONTHLY_REPEAT", Store.today().minusMonths(1).toString().substring(0, 7));
  }

  private void process(String cycle, String period) {
    String run = id();
    db.exec(
        "INSERT INTO job_runs(id,job_name,period,status)"
            + " VALUES(#{p.id},#{p.name},#{p.period},'RUNNING')",
        map("id", run, "name", cycle, "period", period));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new Actors.Actor("SCHEDULER", "scheduler", "定时结算", "ADMIN", "", false),
                null,
                List.of()));
    try {
      commands.run(
          "SCHEDULE-" + cycle + "-" + period,
          "SCHEDULE",
          map("cycle", cycle, "period", period),
          () -> {
            var preview = settlements.preview(map("cycle", cycle, "period", period));
            return text(preview, "status").equals("POSTED")
                ? preview
                : settlements.post(text(preview, "id"));
          });
      db.exec(
          "UPDATE job_runs SET status='DONE',message='结算完成或该账期已处理',finished_at=CURRENT_TIMESTAMP"
              + " WHERE id=#{p.id}",
          map("id", run));
    } catch (Exception e) {
      db.exec(
          "UPDATE job_runs SET status='ERROR',message=#{p.message},finished_at=CURRENT_TIMESTAMP"
              + " WHERE id=#{p.id}",
          map(
              "id",
              run,
              "message",
              e.getMessage() == null
                  ? "执行失败"
                  : e.getMessage().substring(0, Math.min(900, e.getMessage().length()))));
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
