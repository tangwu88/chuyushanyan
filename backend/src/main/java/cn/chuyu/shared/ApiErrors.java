package cn.chuyu.shared;

import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(BusinessException.class)
  ResponseEntity<Map<String, Object>> business(BusinessException e) {
    return ResponseEntity.status(e.status).body(Json.map("message", e.getMessage()));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<Map<String, Object>> invalid(Exception e) {
    return ResponseEntity.badRequest().body(Json.map("message", "输入格式不正确，请检查后重试"));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<Map<String, Object>> conflict(Exception e) {
    return ResponseEntity.status(409).body(Json.map("message", "记录冲突，操作未完成，请刷新后重试"));
  }
}
