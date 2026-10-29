package cn.chuyu.shared;

import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
  private final Store db;

  public ApiErrors(Store db) {
    this.db = db;
  }

  private void record(String message) {
    var attributes =
        org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
    if (!(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes a)
        || !a.getRequest().getMethod().equals("POST")
        || a.getRequest().getRequestURI().endsWith("/auth/login")) return;
    var auth =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof Actors.Actor)) return;
    try {
      db.audit(
          "REQUEST_FAILED",
          a.getRequest().getRequestURI(),
          null,
          null,
          message.substring(0, Math.min(500, message.length())));
    } catch (RuntimeException ignored) {
      /* Preserve the original response if logging itself is unavailable. */
    }
  }

  @ExceptionHandler(BusinessException.class)
  ResponseEntity<Map<String, Object>> business(BusinessException e) {
    record(e.getMessage());
    return ResponseEntity.status(e.status).body(Json.map("message", e.getMessage()));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<Map<String, Object>> invalid(Exception e) {
    record("输入格式不正确");
    return ResponseEntity.badRequest().body(Json.map("message", "输入格式不正确，请检查后重试"));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<Map<String, Object>> conflict(Exception e) {
    record("记录冲突");
    return ResponseEntity.status(409).body(Json.map("message", "记录冲突，操作未完成，请刷新后重试"));
  }
}
