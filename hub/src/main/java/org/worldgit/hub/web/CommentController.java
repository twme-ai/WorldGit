package org.worldgit.hub.web;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.worldgit.hub.account.Models.Role;
import org.worldgit.hub.collaboration.Comments;
import java.util.Map;
@RestController
@RequestMapping("/api/v1/worlds/{owner}/{world}")
public class CommentController {
  private final Access access;private final Comments comments;
  public CommentController(Access access,Comments comments){this.access=access;this.comments=comments;}
  @GetMapping("/comments") Object list(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@RequestParam(required=false) String pr,@RequestParam(defaultValue="false") boolean pinned,@RequestParam(required=false) String dimension,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit){return comments.list(access.world(req,owner,world,Role.READER),pr,pinned,dimension,offset,limit);}
  record New(String parentId,String body,Comments.Pin pin) {}
  @PostMapping("/pulls/{pr}/comments") Object create(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String pr,@RequestBody New b){var w=access.world(req,owner,world,Role.READER);access.scope(req,"write");return comments.create(w,access.requireUser(req),pr,b.parentId(),b.body(),b.pin());}
  record Edit(String body) {}
  @PatchMapping("/comments/{id}") Object edit(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String id,@RequestBody Edit b){var w=access.world(req,owner,world,Role.READER);access.scope(req,"write");return comments.edit(w,access.requireUser(req),id,b.body());}
  @DeleteMapping("/comments/{id}") Object delete(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String id){var w=access.world(req,owner,world,Role.READER);access.scope(req,"write");comments.delete(w,access.requireUser(req),id);return Map.of("ok",true);}
}
