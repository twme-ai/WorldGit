package org.worldgit.hub.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.worldgit.hub.account.AccountService;
import org.worldgit.hub.account.Models.*;

@RestController
@RequestMapping("/api/v1")
public class AccountController {
  private final AccountService accounts;
  private final Access access;
  private final org.worldgit.hub.account.AuthThrottle throttle;

  public AccountController(AccountService accounts, Access access, org.worldgit.hub.account.AuthThrottle throttle) {
    this.accounts = accounts;
    this.access = access;
    this.throttle = throttle;
  }

  @GetMapping("/auth/outcome")
  Map<String,Object> outcome(HttpServletRequest req){
    var session=req.getSession(false);Object result=session==null?null:session.getAttribute("worldgit.notification");
    if(session!=null)session.removeAttribute("worldgit.notification");var body=new java.util.LinkedHashMap<String,Object>();body.put("result",result);return body;
  }
  record Login(String username, String password) {}

  @PostMapping("/auth/login")
  Map<String, Object> login(HttpServletRequest req, @RequestBody Login body) {
    User u = throttle.authenticate(body.username(), req, false, () -> accounts.authenticatePassword(body.username(), body.password()))
        .orElseThrow(() -> new ApiError.Unauthorized("帳號或密碼錯誤"));
    throttle.successful(u.id(), req);
    req.getSession(); req.changeSessionId();
    req.getSession().removeAttribute("worldgit.oauth.link");
    req.getSession().setAttribute(org.worldgit.hub.account.RequestUser.SESSION_USER, u.id());
    return Map.of("token", accounts.createToken(u, "web session", true), "user", userJson(u));
  }

  @PostMapping("/auth/logout")
  Map<String,Object> logout(HttpServletRequest req) {
    if (req.getSession(false) != null) req.getSession(false).invalidate();
    return Map.of("ok",true);
  }

  @GetMapping("/me")
  Map<String, Object> me(HttpServletRequest req) {
    Object csrf = req.getAttribute(org.springframework.security.web.csrf.CsrfToken.class.getName());
    if (csrf instanceof org.springframework.security.web.csrf.CsrfToken t) t.getToken();
    User u = access.optionalUser(req);
    return u == null ? Map.of("user", Map.of()) : Map.of("user", userJson(u));
  }

  static Map<String, Object> userJson(User u) {
    return Map.of("id", u.id(), "username", u.username(), "admin", u.admin());
  }

  record NewUser(String username, String password, Boolean admin) {}

  @PostMapping("/users")
  Map<String, Object> createUser(HttpServletRequest req, @RequestBody NewUser body) {
    access.scope(req,"admin");
    User actor = access.requireUser(req);
    if (!actor.admin()) throw new SecurityException("只有管理員可以建立帳號");
    return userJson(accounts.createUser(body.username(), body.password(), Boolean.TRUE.equals(body.admin())));
  }

  record NewToken(String name, java.time.Instant expiresAt, String scope) {}

  @PostMapping("/tokens")
  Map<String, Object> createToken(HttpServletRequest req, @RequestBody(required = false) NewToken body) {
    access.scope(req,"admin");
    User u = access.requireUser(req);
    return Map.of("token", accounts.createToken(u, body == null ? null : body.name(), false, body == null ? null : body.expiresAt(), body == null ? "admin" : body.scope()));
  }

  @GetMapping("/tokens")
  List<Map<String, Object>> tokens(HttpServletRequest req) {
    return accounts.listTokens(access.requireUser(req));
  }

  @DeleteMapping("/tokens/{id}")
  Map<String, Object> deleteToken(HttpServletRequest req, @PathVariable String id) {
    access.scope(req,"admin");
    boolean deleted=accounts.deleteToken(access.requireUser(req), id);if(!deleted)req.setAttribute("worldgit.noop",true);return Map.of("deleted",deleted);
  }

  record NewOrg(String slug, String displayName) {}

  @PostMapping("/orgs")
  Map<String, Object> createOrg(HttpServletRequest req, @RequestBody NewOrg body) {
    access.scope(req,"admin");
    accounts.createOrganization(access.requireUser(req), body.slug(), body.displayName());
    return Map.of("slug", body.slug());
  }

  record Member(String role) {}

  @PutMapping("/orgs/{slug}/members/{username}")
  Map<String, Object> member(HttpServletRequest req, @PathVariable String slug, @PathVariable String username, @RequestBody Member body) {
    access.scope(req,"admin");
    accounts.setMember(access.requireUser(req), slug, username, Role.parse(body.role()));
    return Map.of("ok", true);
  }
}
