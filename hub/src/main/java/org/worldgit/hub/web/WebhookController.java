package org.worldgit.hub.web;
import java.io.IOException;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.worldgit.hub.account.Models.Role;
import org.worldgit.hub.collaboration.Webhooks;
@RestController
@RequestMapping("/api/v1/worlds/{owner}/{world}/webhooks")
public class WebhookController {
  private final Access access;private final Webhooks hooks;
  public WebhookController(Access access,Webhooks hooks){this.access=access;this.hooks=hooks;}
  @GetMapping Object list(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit){return hooks.list(access.world(req,owner,world,Role.ADMIN),offset,limit);}
  record Config(String url,String secret,List<String> events,boolean enabled) {}
  @PostMapping Object create(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@RequestBody Config b)throws IOException{return hooks.create(access.world(req,owner,world,Role.ADMIN),b.url(),b.secret(),b.events(),b.enabled());}
  @PutMapping("/{id}") Object update(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String id,@RequestBody Config b)throws IOException{return hooks.update(access.world(req,owner,world,Role.ADMIN),id,b.url(),b.secret(),b.events(),b.enabled());}
  @DeleteMapping("/{id}") Object delete(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String id){hooks.delete(access.world(req,owner,world,Role.ADMIN),id);return Map.of("ok",true);}
  @GetMapping("/{id}/deliveries") Object deliveries(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String id,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit){return hooks.deliveries(access.world(req,owner,world,Role.ADMIN),id,offset,limit);}
}
