package org.worldgit.hub.web;
import java.io.IOException;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import org.worldgit.hub.account.Models.Role;
import org.worldgit.hub.collaboration.Releases;
@RestController
@RequestMapping("/api/v1/worlds/{owner}/{world}/releases")
public class ReleaseController {
  private final Access access;private final Releases releases;
  public ReleaseController(Access access,Releases releases){this.access=access;this.releases=releases;}
  @GetMapping Object list(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit){return releases.list(access.world(req,owner,world,Role.READER),offset,limit);}
  record New(String tag,String title,String body,java.util.Map<String,String> revisions) {}
  @PostMapping Object create(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@RequestBody New b)throws IOException{return releases.create(access.world(req,owner,world,Role.WRITER),access.requireUser(req),b.tag(),b.title(),b.body(),b.revisions());}
  @GetMapping("/{id}") Object get(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String id){return releases.find(access.world(req,owner,world,Role.READER),id);}
  @DeleteMapping("/{id}") Object delete(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String id){releases.delete(access.world(req,owner,world,Role.WRITER),id);return java.util.Map.of("deleted",true);}
  @GetMapping("/{id}/zip") void zip(HttpServletRequest req,HttpServletResponse res,@PathVariable String owner,@PathVariable String world,@PathVariable String id)throws IOException {releases.zip(access.world(req,owner,world,Role.READER),id,res);}
}
