package org.worldgit.hub.web;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.worldgit.hub.account.*;
import org.worldgit.hub.config.CollaborationProperties;

@RestController
@RequestMapping("/api/v1/auth")
public class OAuthController {
  private final CollaborationProperties props;private final Access access;private final OAuthAccounts accounts;private final RegistrationService registration;
  public OAuthController(CollaborationProperties props,Access access,OAuthAccounts accounts,RegistrationService registration) {this.props=props;this.access=access;this.accounts=accounts;this.registration=registration;}
  @GetMapping("/options") Object options() {
    return Map.of("registration",props.registration().enabled(),"oauth",props.oauth().entrySet().stream().filter(e->e.getValue().enabled()).map(Map.Entry::getKey).sorted().toList());
  }
  @GetMapping("/identities") Object identities(HttpServletRequest req) {return accounts.list(access.requireUser(req));}
  @PostMapping("/oauth/{provider}/link") Object link(HttpServletRequest req,@PathVariable String provider) {
    access.scope(req,"admin"); var u=access.requireUser(req);
    var p=props.oauth().get(provider);if(p==null || !p.enabled()) throw new ApiError.NotFound("OAuth 未啟用");
    var s=req.getSession();req.changeSessionId();s.setAttribute(RequestUser.SESSION_USER,u.id());s.setAttribute("worldgit.oauth.link",u.id());s.setAttribute("worldgit.oauth.provider",provider);s.setAttribute("worldgit.oauth.started",System.currentTimeMillis());
    return Map.of("url","/oauth2/authorization/"+provider);
  }
  @DeleteMapping("/identities/{provider}") Object unlink(HttpServletRequest req,@PathVariable String provider) {access.scope(req,"admin");accounts.unlink(access.requireUser(req),provider);return Map.of("ok",true);}
  record Password(String password) {}
  @PutMapping("/password") Object password(HttpServletRequest req,@RequestBody Password b) {access.scope(req,"admin");accounts.password(access.requireUser(req),b.password());return Map.of("ok",true);}
  record Registration(String username,String email,String password) {}
  @PostMapping("/register") Object register(HttpServletRequest req,@RequestBody Registration b) {registration.register(req,b.username(),b.email(),b.password());return Map.of("message","若資料可用，驗證信已寄出");}
  record Verification(String token) {}
  @PostMapping("/verify") Object verify(@RequestBody Verification b) {registration.verify(b.token());return Map.of("ok",true);}
}
