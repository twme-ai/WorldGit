package org.worldgit.hub.web;

import java.util.Map;
import java.io.IOException;
import org.worldgit.core.normalize.DecodeBudget;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 統一的錯誤回應 {"code": "...", "error": "..."}。 */
@RestControllerAdvice(basePackages = "org.worldgit.hub.web")
public class ApiError {
  private static final Logger log = LoggerFactory.getLogger(ApiError.class);

  public static class NotFound extends RuntimeException {
    public NotFound(String m) {
      super(m);
    }
  }

  public static class Conflict extends RuntimeException { public Conflict(String m) { super(m); } }
  public static class Unavailable extends RuntimeException { public Unavailable(String m) { super(m); } }
  @ExceptionHandler(Conflict.class)
  ResponseEntity<Map<String,String>> conflict(Conflict e) { return ResponseEntity.status(409).contentType(MediaType.APPLICATION_JSON).body(Map.of("code","conflict","error",e.getMessage())); }
  @ExceptionHandler(Unavailable.class)
  ResponseEntity<Map<String,String>> unavailable(Unavailable e) { return ResponseEntity.status(503).header("Retry-After","2").contentType(MediaType.APPLICATION_JSON).body(Map.of("code","unavailable","error",e.getMessage())); }
  @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
  ResponseEntity<Map<String,String>> duplicate(Exception e) { return ResponseEntity.status(409).contentType(MediaType.APPLICATION_JSON).body(Map.of("code","duplicate","error","資料已存在")); }

  public static class Unauthorized extends RuntimeException {
    public Unauthorized(String m) {
      super(m);
    }
  }

  @ExceptionHandler({NotFound.class, NoSuchElementException.class})
  ResponseEntity<Map<String, String>> notFound(RuntimeException e) {
    return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON).body(Map.of("code", "not-found", "error", e.getMessage() == null ? "not found" : e.getMessage()));
  }

  @ExceptionHandler(Unauthorized.class)
  ResponseEntity<Map<String, String>> unauthorized(Unauthorized e) {
    return ResponseEntity.status(401).contentType(MediaType.APPLICATION_JSON).body(Map.of("code", "unauthorized", "error", e.getMessage()));
  }

  @ExceptionHandler(org.worldgit.hub.account.AuthThrottle.Limited.class)
  ResponseEntity<Map<String, String>> limited(org.worldgit.hub.account.AuthThrottle.Limited e) {
    return ResponseEntity.status(429).header("Retry-After", Long.toString(e.retryAfter())).contentType(MediaType.APPLICATION_JSON).body(Map.of("code", "rate-limit", "error", e.getMessage()));
  }

  @ExceptionHandler(SecurityException.class)
  ResponseEntity<Map<String, String>> forbidden(SecurityException e) {
    return ResponseEntity.status(403).contentType(MediaType.APPLICATION_JSON).body(Map.of("code", "forbidden", "error", e.getMessage()));
  }

  @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
      org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
      org.springframework.web.bind.MissingServletRequestParameterException.class})
  ResponseEntity<Map<String, String>> request(Exception e) {
    return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Map.of("code", "bad-request", "error", "請求格式或參數無效"));
  }

  @ExceptionHandler(NumberFormatException.class)
  ResponseEntity<Map<String, String>> coordinates(NumberFormatException e) {
    return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Map.of("code", "bad-request", "error", "世界資料座標無效"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> bad(IllegalArgumentException e) {
    return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Map.of("code", "bad-request", "error", e.getMessage()));
  }

  @ExceptionHandler(DecodeBudget.Exceeded.class)
  ResponseEntity<Map<String, String>> budget(DecodeBudget.Exceeded e) {
    return ResponseEntity.status(413).contentType(MediaType.APPLICATION_JSON).body(Map.of("code", "budget", "error", e.getMessage()));
  }

  @ExceptionHandler(IOException.class)
  ResponseEntity<Map<String, String>> data(IOException e) {
    log.warn("世界資料讀取失敗", e);
    return ResponseEntity.unprocessableEntity().contentType(MediaType.APPLICATION_JSON).body(Map.of("code", "invalid-world", "error", "世界資料無效或無法讀取"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, String>> other(Exception e) {
    log.error("API 錯誤", e);
    return ResponseEntity.status(500).contentType(MediaType.APPLICATION_JSON).body(Map.of("code", "internal", "error", "伺服器內部錯誤"));
  }
}
