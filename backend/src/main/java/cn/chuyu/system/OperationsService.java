package cn.chuyu.system;

import static cn.chuyu.shared.BusinessException.require;
import static cn.chuyu.shared.Json.*;

import cn.chuyu.members.*;
import cn.chuyu.reports.*;
import cn.chuyu.rewards.*;
import cn.chuyu.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class OperationsService {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(OperationsService.class);
  private final Store db;
  private final Commands commands;
  private final ManagementService management;
  private final PerformanceService performance;
  private final MemberService members;
  private final SettlementService settlements;
  private final AdvancedReports reports;
  private final QueryService queries;

  public OperationsService(
      Store db,
      Commands commands,
      ManagementService management,
      PerformanceService performance,
      MemberService members,
      SettlementService settlements,
      AdvancedReports reports,
      QueryService queries) {
    this.db = db;
    this.commands = commands;
    this.management = management;
    this.performance = performance;
    this.members = members;
    this.settlements = settlements;
    this.reports = reports;
    this.queries = queries;
  }

  public Map<String, Object> requestUpgrade(Map<String, Object> b) {
    String from = text(b, "fromPoint"), target = text(b, "targetPoint");
    var start = members.point(from);
    var end = members.point(target);
    if (Actors.member()) members.own(from);
    else {
      Actors.require("members:write");
      Actors.requirePoint(start);
      Actors.requirePoint(end);
    }
    var ix = new HashMap<String, Map<String, Object>>();
    performance.points().forEach(p -> ix.put(text(p, "id"), p));
    require(performance.below(from, target, ix), "只能发起本人或推荐链上级的同步升级");
    int expected = integer(b.get("expectedGrade"));
    require(expected >= 1 && expected <= 7, "升级等级需为1至7星");
    require(bool(start.get("active")) && bool(end.get("active")), "发起人与目标账户需正常");
    require(
        db.count(
                "SELECT COUNT(*) FROM sync_upgrades WHERE from_point=#{p.from} AND"
                    + " target_point=#{p.target} AND expected_grade=#{p.grade} AND"
                    + " status='PENDING'",
                map("from", from, "target", target, "grade", expected))
            == 0,
        "已有相同的待审批申请");
    String id = id();
    db.exec(
        "INSERT INTO sync_upgrades(id,from_point,target_point,expected_grade,reason,request_by)"
            + " VALUES(#{p.id},#{p.from},#{p.target},#{p.grade},#{p.reason},#{p.actor})",
        map(
            "id",
            id,
            "from",
            from,
            "target",
            target,
            "grade",
            expected,
            "reason",
            text(b, "reason"),
            "actor",
            Actors.current().id()));
    db.audit("UPGRADE_REQUEST", id, null, b, "发起同步升级");
    return map("id", id, "eligibleGrade", performance.eligibleGrade(end));
  }

  public Map<String, Object> reviewUpgrade(String id, Map<String, Object> b) {
    Actors.require("members:write");
    var request = db.required("SELECT * FROM sync_upgrades WHERE id=#{p.id}", map("id", id));
    var point = members.point(text(request, "targetPoint"));
    Actors.requirePoint(point);
    require(text(request, "status").equals("PENDING"), "此申请已处理");
    require(!text(b, "reason").isBlank(), "请填写审批说明");
    boolean approved = bool(b.get("approved"));
    int eligible = performance.eligibleGrade(point),
        expected = integer(request.get("expectedGrade"));
    require(!approved || bool(point.get("active")), "目标账户已冻结");
    require(!approved || eligible >= expected, "当前满足的最高等级为" + eligible + "星，尚未达到申请等级");
    if (approved && integer(point.get("grade")) < expected) {
      db.exec(
          "UPDATE member_points SET grade=#{p.grade} WHERE id=#{p.id}",
          map("id", point.get("id"), "grade", expected));
      db.recordPoint(text(point, "id"), LocalDateTime.now(Store.ZONE));
      db.audit("GRADE_SYNC", text(point, "id"), point.get("grade"), expected, text(b, "reason"));
    }
    db.exec(
        "UPDATE sync_upgrades SET"
            + " status=#{p.status},review_by=#{p.actor},reviewed_at=CURRENT_TIMESTAMP,reason=#{p.reason}"
            + " WHERE id=#{p.id}",
        map(
            "id",
            id,
            "status",
            approved ? "APPROVED" : "REJECTED",
            "actor",
            Actors.current().id(),
            "reason",
            text(b, "reason")));
    db.audit("UPGRADE_REVIEW", id, request, b, text(b, "reason"));
    return map("id", id, "eligibleGrade", eligible);
  }

  public Map<String, Object> runJob(String job, String key, Map<String, Object> b) {
    require(
        management.rows("job-definitions", false).stream()
            .anyMatch(row -> text(row, "id").equals(job)),
        "任务不存在");
    if (Set.of("DAILY", "MONTHLY", "MONTHLY_REPEAT").contains(job))
      Actors.requireGlobal("funds:write");
    else Actors.requireGlobal("system:write");
    String period = text(b, "period");
    if (period.isBlank())
      period =
          job.startsWith("MONTHLY")
              ? Store.today().minusMonths(1).toString().substring(0, 7)
              : Store.today().minusDays(1).toString();
    String finalPeriod = period, run = id();
    db.exec(
        "INSERT INTO job_runs(id,job_name,period,status)"
            + " VALUES(#{p.id},#{p.name},#{p.period},'RUNNING')",
        map("id", run, "name", job, "period", period));
    try {
      var result =
          commands.run(key, "JOB:" + job, map("period", period), () -> execute(job, finalPeriod));
      db.exec(
          "UPDATE job_runs SET status='DONE',message=#{p.message},finished_at=CURRENT_TIMESTAMP"
              + " WHERE id=#{p.id}",
          map("id", run, "message", jobMessage(job, result)));
      return map("id", run, "status", "DONE", "result", result);
    } catch (RuntimeException e) {
      String message = e instanceof BusinessException ? e.getMessage() : "任务执行失败，请重试";
      log.warn("Job {} failed for period {}", job, period, e);
      db.exec(
          "UPDATE job_runs SET status='ERROR',message=#{p.message},finished_at=CURRENT_TIMESTAMP"
              + " WHERE id=#{p.id}",
          map("id", run, "message", message.substring(0, Math.min(950, message.length()))));
      throw e;
    }
  }

  private String jobMessage(String job, Map<String, Object> result) {
    return switch (job) {
      case "DAILY", "MONTHLY", "MONTHLY_REPEAT" ->
          "结算已完成，实发 ¥" + cn.chuyu.shared.Money.money(result.get("net"));
      case "UPGRADES" -> "已通过 " + result.get("approved") + " 项同步升级";
      case "PERFORMANCE" -> "已保存 " + result.get("points") + " 个点位的业绩快照";
      case "RANKING" -> "已保存 " + result.get("members") + " 个会员的排行快照";
      case "ARCHIVE" ->
          "已归档 "
              + result.get("orders")
              + " 笔订单、"
              + result.get("entries")
              + " 条流水、"
              + result.get("topups")
              + " 笔充值";
      case "RECONCILIATION" -> "资金对账平衡，待结转调整 " + result.get("pendingOffsets") + " 条";
      default -> text(result, "message");
    };
  }

  private Map<String, Object> execute(String job, String period) {
    if (Set.of("DAILY", "MONTHLY", "MONTHLY_REPEAT").contains(job)) {
      var preview = settlements.preview(map("cycle", job, "period", period));
      var batch =
          text(preview, "status").equals("POSTED")
              ? preview
              : settlements.post(text(preview, "id"));
      return map("batchId", batch.get("id"), "net", batch.get("net"));
    }
    LocalDate day;
    try {
      day = LocalDate.parse(period);
    } catch (Exception e) {
      throw new BusinessException("账期格式应为YYYY-MM-DD");
    }
    require(day.isBefore(Store.today()), "维护任务使用已结束的业务日期");
    switch (job) {
      case "MEMBERS" -> {
        performance.refreshGrades(true);
        return map("message", "会员、董事、小店与计划资格已复核");
      }
      case "UPGRADES" -> {
        int approved = 0;
        for (var request :
            db.list("SELECT * FROM sync_upgrades WHERE status='PENDING' ORDER BY created_at,id")) {
          var point = members.point(text(request, "targetPoint"));
          if (bool(point.get("active"))
              && performance.eligibleGrade(point) >= integer(request.get("expectedGrade"))) {
            reviewUpgrade(text(request, "id"), map("approved", true, "reason", "定时核验升级条件"));
            approved++;
          }
        }
        return map("approved", approved);
      }
      case "PERFORMANCE" -> {
        var points = performance.pointsAt(day.plusDays(1).atStartOfDay());
        var metrics = performance.metrics(points, day.plusDays(1).atStartOfDay());
        for (var point : points) {
          String id = text(point, "id");
          var value = metrics.get(id);
          var args =
              map(
                  "id",
                  id,
                  "day",
                  day,
                  "team",
                  value.team(),
                  "small",
                  value.small(),
                  "own",
                  value.own());
          db.exec(
              "DELETE FROM performance_snapshots WHERE point_id=#{p.id} AND snapshot_date=#{p.day}",
              args);
          db.exec(
              "INSERT INTO performance_snapshots(point_id,snapshot_date,team,small,own)"
                  + " VALUES(#{p.id},#{p.day},#{p.team},#{p.small},#{p.own})",
              args);
        }
        return map("points", points.size());
      }
      case "RANKING" -> {
        var rows = reports.rows("rankings", Map.of("start", period, "end", period));
        snapshot("rankings", day, rows);
        return map("members", rows.size());
      }
      case "FINANCE" -> {
        for (String report : List.of("daily-stats", "shop-stats", "shop-cash"))
          snapshot(report, day, reports.rows(report, Map.of("start", period, "end", period)));
        return map("message", "财务与店铺统计快照已保存");
      }
      case "RECONCILIATION" -> {
        var result = queries.reconciliation();
        require(bool(result.get("balanced")), "账本核对发现差异");
        return map(
            "balanced",
            true,
            "pendingOffsets",
            db.count("SELECT COUNT(*) FROM performance_offsets WHERE remaining>0", Map.of()));
      }
      case "ARCHIVE" -> {
        var settings =
            management.rows("parameters", true).stream()
                .filter(r -> text(r, "id").equals("RETENTION"))
                .findFirst()
                .orElseThrow(() -> new BusinessException("未配置保留策略"));
        var args =
            map(
                "cutoff",
                java.sql.Timestamp.valueOf(
                    day.plusDays(1).minusDays(integer(settings.get("archiveDays"))).atStartOfDay()),
                "audit",
                java.sql.Timestamp.valueOf(
                    day.plusDays(1).minusDays(integer(settings.get("auditDays"))).atStartOfDay()),
                "login",
                java.sql.Timestamp.valueOf(
                    day.plusDays(1).minusDays(integer(settings.get("loginDays"))).atStartOfDay()));
        int
            orders =
                db.exec(
                    "UPDATE shop_orders SET archived=TRUE WHERE created_at<#{p.cutoff} AND"
                        + " archived=FALSE AND (cancelled=TRUE OR fulfilment_status='FULFILLED')",
                    args),
            entries =
                db.exec(
                    "UPDATE ledger_entries SET archived=TRUE WHERE created_at<#{p.cutoff} AND"
                        + " archived=FALSE",
                    args),
            topups =
                db.exec(
                    "UPDATE topups SET archived=TRUE WHERE created_at<#{p.cutoff} AND"
                        + " status<>'PENDING' AND archived=FALSE",
                    args);
        db.exec("UPDATE audit_logs SET deleted=TRUE WHERE created_at<#{p.audit}", args);
        db.exec("UPDATE login_logs SET deleted=TRUE WHERE created_at<#{p.login}", args);
        db.audit(
            "DATA_ARCHIVE",
            period,
            null,
            map("orders", orders, "entries", entries, "topups", topups),
            "按配置归档，财务原始记录仍保留");
        return map("orders", orders, "entries", entries, "topups", topups);
      }
      default -> throw new BusinessException("任务未实现");
    }
  }

  private void snapshot(String type, LocalDate day, Object data) {
    var args = map("type", type, "day", day);
    var old =
        db.one(
            "SELECT * FROM report_snapshots WHERE report_type=#{p.type} AND snapshot_date=#{p.day}",
            args);
    if (old == null)
      db.exec(
          "INSERT INTO report_snapshots(id,report_type,snapshot_date,data_json)"
              + " VALUES(#{p.id},#{p.type},#{p.day},#{p.data})",
          map("id", id(), "type", type, "day", day, "data", write(data)));
    else
      db.exec(
          "UPDATE report_snapshots SET data_json=#{p.data} WHERE id=#{p.id}",
          map("id", old.get("id"), "data", write(data)));
  }

  public Map<String, Object> removeLogs(String resource, String id, Map<String, Object> b) {
    Actors.requireGlobal("system:write");
    require(Set.of("audits", "login-logs").contains(resource), "日志类型错误");
    require(!text(b, "reason").isBlank(), "请填写清理原因");
    String table = resource.equals("audits") ? "audit_logs" : "login_logs";
    int count =
        db.exec(
            "UPDATE "
                + table
                + " SET deleted=TRUE WHERE deleted=FALSE"
                + (id.equals("all") ? "" : " AND id=#{p.id}"),
            map("id", id));
    db.audit("LOG_CLEAR", resource, null, map("count", count), text(b, "reason"));
    return map("count", count);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> importUsers(Map<String, Object> b) {
    Actors.requireGlobal("system:write");
    require(b.get("rows") instanceof List<?>, "请提供导入数据");
    var rows = (List<Map<String, Object>>) b.get("rows");
    require(!rows.isEmpty() && rows.size() <= 500, "单次导入1至500行");
    List<String> ids = new ArrayList<>();
    for (var input : rows) {
      var row = new LinkedHashMap<>(input);
      String phone = text(row, "phone");
      require(phone.matches("[0-9]{6,24}"), "导入电话需为6至24位数字");
      row.put("password", phone.substring(phone.length() - 6));
      row.put("phonePassword", true);
      row.putIfAbsent("role", "OPERATOR");
      row.putIfAbsent("department", "初语山言");
      ids.add(text(management.user(null, row), "id"));
    }
    db.audit("SYSTEM_USER_IMPORT", null, null, map("count", ids.size()), "导入后台用户");
    return map("count", ids.size(), "ids", ids);
  }
}
