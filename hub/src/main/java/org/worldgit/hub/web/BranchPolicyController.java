package org.worldgit.hub.web;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.worldgit.hub.account.Models.Role;
import org.worldgit.hub.collaboration.BranchPolicy;
@RestController
@RequestMapping("/api/v1/worlds/{owner}/{world}/protected-branches")
public class BranchPolicyController {
  private final Access access;private final BranchPolicy policy;
  public BranchPolicyController(Access access,BranchPolicy policy){this.access=access;this.policy=policy;}
  @GetMapping Object list(HttpServletRequest req,@PathVariable String owner,@PathVariable String world){return policy.list(access.world(req,owner,world,Role.READER).id());}
  @PutMapping Object set(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@RequestBody BranchPolicy.Rule rule){policy.set(access.world(req,owner,world,Role.ADMIN),rule);return Map.of("ok",true);}
  @DeleteMapping Object delete(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@RequestParam String branch,@RequestParam(defaultValue="minecraft:overworld") String dimension){policy.delete(access.world(req,owner,world,Role.ADMIN),branch,dimension);return Map.of("ok",true);}
}
