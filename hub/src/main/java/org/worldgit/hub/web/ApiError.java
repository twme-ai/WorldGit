package org.worldgit.hub.web;

import java.util.Map;
import java.io.IOException;
import org.worldgit.core.normalize.DecodeBudget;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 統一的錯誤回應 {"error": "..."}。 */
@RestControllerAdvice(basePackages = "org.worldgit.hub.web")
public class ApiError {
  private static final Logger log = LoggerFactory.getLogger(ApiError.class);

  public static class NotFound extends RuntimeException {
    public NotFound(String m) {
      super(m);
    }
  }

  public static class Unauthorized extends RuntimeException {
    public Unauthorized(String m) {
      super(m);
    }
  }

  @ExceptionHandler({NotFound.class, NoSuchElementException.class})
  ResponseEntity<Map<String, String>> notFound(RuntimeException e) {
    return ResponseEntity.status(404).body(Map.of("error", e.getMessage() == null ? "not found" : e.getMessage()));
  }

  @ExceptionHandler(Unauthorized.class)
  ResponseEntity<Map<String, String>> unauthorized(Unauthorized e) {
    return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(org.worldgit.hub.account.AuthThrottle.Limited.class)
  ResponseEntity<Map<String, String>> limited(org.worldgit.hub.account.AuthThrottle.Limited e) {
    return ResponseEntity.status(429).header("Retry-After", Long.toString(e.retryAfter())).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(SecurityException.class)
  ResponseEntity<Map<String, String>> forbidden(SecurityException e) {
    return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
      org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
      org.springframework.web.bind.MissingServletRequestParameterException.class})
  ResponseEntity<Map<String, String>> request(Exception e) {
    return ResponseEntity.badRequest().body(Map.of("error", "請求格式或參數無效"));
  }

  @ExceptionHandler(NumberFormatException.class)
  ResponseEntity<Map<String, String>> coordinates(NumberFormatException e) {
    return ResponseEntity.badRequest().body(Map.of("error", "世界資料座標無效"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> bad(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(DecodeBudget.Exceeded.class)
  ResponseEntity<Map<String, String>> budget(DecodeBudget.Exceeded e) {
    return ResponseEntity.status(413).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(IOException.class)
  ResponseEntity<Map<String, String>> data(IOException e) {
    log.warn("世界資料讀取失敗", e);
    return ResponseEntity.unprocessableEntity().body(Map.of("error", "世界資料無效或無法讀取"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, String>> other(Exception e) {
    log.error("API 錯誤", e);
    return ResponseEntity.status(500).body(Map.of("error", "伺服器內部錯誤"));
  }
}
