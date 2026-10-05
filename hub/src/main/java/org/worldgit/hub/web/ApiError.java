package org.worldgit.hub.web;

import java.util.Map;
import java.util.List;
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
  private final org.worldgit.hub.operation.Reports reports;
  @org.springframework.beans.factory.annotation.Autowired
  public ApiError(org.worldgit.hub.operation.Reports reports){this.reports=reports;}
  public ApiError(){reports=null;}
  private Map<String,Object> body(String code,String message){
    if(reports!=null)return reports.error(org.worldgit.hub.operation.Outcomes.request(),code,message);
    var c=org.worldgit.hub.operation.Reports.OperationProgressContext.of(null);var report=org.worldgit.core.operation.OperationResult.ErrorReport.create(code,c.id(),c.operation(),null,"0.1.0-SNAPSHOT","Hub 0.1.0-SNAPSHOT",message);
    return Map.of("code",code,"error",report.message(),"errorReport",report,"result",new org.worldgit.core.operation.OperationResult(c.id(),c.operation(),org.worldgit.core.operation.OperationResult.Status.FAILED,null,Map.of(),0,List.of(),report));
  }

  private static final Logger log = LoggerFactory.getLogger(ApiError.class);

  public static class NotFound extends RuntimeException {
    public NotFound(String m) {
      super(m);
    }
  }

  public static class JsonBudget extends RuntimeException {public JsonBudget(String m){super(m);}}
  @ExceptionHandler(JsonBudget.class) ResponseEntity<Map<String,Object>> jsonBudget(JsonBudget e){return ResponseEntity.status(413).contentType(MediaType.APPLICATION_JSON).body(body("budget",e.getMessage()));}

  public static class Conflict extends RuntimeException { public Conflict(String m) { super(m); } }
  public static class Unavailable extends RuntimeException { public Unavailable(String m) { super(m); } }
  @ExceptionHandler(Conflict.class)
  ResponseEntity<Map<String,Object>> conflict(Conflict e) { return ResponseEntity.status(409).contentType(MediaType.APPLICATION_JSON).body(body("conflict",e.getMessage())); }
  @ExceptionHandler(Unavailable.class)
  ResponseEntity<Map<String,Object>> unavailable(Unavailable e) { return ResponseEntity.status(503).header("Retry-After","2").contentType(MediaType.APPLICATION_JSON).body(body("unavailable",e.getMessage())); }
  @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
  ResponseEntity<Map<String,Object>> duplicate(Exception e) { return ResponseEntity.status(409).contentType(MediaType.APPLICATION_JSON).body(body("duplicate","資料已存在")); }

  public static class Unauthorized extends RuntimeException {
    public Unauthorized(String m) {
      super(m);
    }
  }

  @ExceptionHandler({NotFound.class, NoSuchElementException.class})
  ResponseEntity<Map<String, Object>> notFound(RuntimeException e) {
    return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON).body(body("not-found",e.getMessage() == null ? "not found" : e.getMessage()));
  }

  @ExceptionHandler(Unauthorized.class)
  ResponseEntity<Map<String, Object>> unauthorized(Unauthorized e) {
    return ResponseEntity.status(401).contentType(MediaType.APPLICATION_JSON).body(body("unauthorized",e.getMessage()));
  }

  @ExceptionHandler(org.worldgit.hub.account.AuthThrottle.Limited.class)
  ResponseEntity<Map<String, Object>> limited(org.worldgit.hub.account.AuthThrottle.Limited e) {
    return ResponseEntity.status(429).header("Retry-After", Long.toString(e.retryAfter())).contentType(MediaType.APPLICATION_JSON).body(body("rate-limit",e.getMessage()));
  }

  @ExceptionHandler(SecurityException.class)
  ResponseEntity<Map<String, Object>> forbidden(SecurityException e) {
    return ResponseEntity.status(403).contentType(MediaType.APPLICATION_JSON).body(body("forbidden",e.getMessage()));
  }

  @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
      org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
      org.springframework.web.bind.MissingServletRequestParameterException.class})
  ResponseEntity<Map<String, Object>> request(Exception e) {
    return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(body("bad-request","請求格式或參數無效"));
  }

  @ExceptionHandler(NumberFormatException.class)
  ResponseEntity<Map<String, Object>> coordinates(NumberFormatException e) {
    return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(body("bad-request","世界資料座標無效"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, Object>> bad(IllegalArgumentException e) {
    return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(body("bad-request",e.getMessage()));
  }

  @ExceptionHandler(DecodeBudget.Exceeded.class)
  ResponseEntity<Map<String, Object>> budget(DecodeBudget.Exceeded e) {
    return ResponseEntity.status(413).contentType(MediaType.APPLICATION_JSON).body(body("budget",e.getMessage()));
  }

  @ExceptionHandler(IOException.class)
  ResponseEntity<Map<String, Object>> data(IOException e) {
    log.warn("世界資料讀取失敗", e);
    return ResponseEntity.unprocessableEntity().contentType(MediaType.APPLICATION_JSON).body(body("invalid-world",e.getMessage()==null?"世界資料無效或無法讀取":e.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> other(Exception e) {
    log.error("API 錯誤", e);
    return ResponseEntity.status(500).contentType(MediaType.APPLICATION_JSON).body(body("internal","伺服器內部錯誤"));
  }
}
