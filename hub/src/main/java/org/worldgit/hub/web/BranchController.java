package org.worldgit.hub.web;

import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import java.io.IOException;
import org.springframework.web.bind.annotation.*;
import org.worldgit.hub.account.Models.Role;
import org.worldgit.hub.history.BranchService;
import org.worldgit.hub.history.CompareService;

/** 分支列表與兩個分支／commit 的比較。授權同其他端點：私人世界無權限一律 404。 */
@RestController
@RequestMapping("/api/v1/worlds/{owner}/{world}")
public class BranchController {
  private final ObjectMapper json;
  private final Access access;
  private final BranchService branches;
  private final CompareService compare;

  public BranchController(Access access, BranchService branches, CompareService compare, ObjectMapper json) {
    this.json = json;
    this.access = access;
    this.branches = branches;
    this.compare = compare;
  }

  @GetMapping("/branches")
  ResponseEntity<byte[]> branches(HttpServletRequest req, @PathVariable String owner, @PathVariable String world,
      @RequestParam(required = false) String base,@RequestParam(required=false) String dimension) throws IOException {
    var w=access.world(req,owner,world,Role.READER);return response(dimension==null?branches.list(w,base):branches.list(w,base,new org.worldgit.core.model.DimensionId(dimension)));
  }

  /** a→b；兩端可為分支名、HEAD 或 commit 前綴（4–40 位十六進位）。 */
  @GetMapping("/compare")
  ResponseEntity<byte[]> compare(HttpServletRequest req, @PathVariable String owner, @PathVariable String world,
      @RequestParam String a, @RequestParam String b,@RequestParam(required=false) String dimension) throws IOException {
    var w=access.world(req,owner,world,Role.READER);return response(dimension==null?compare.compare(w,a,b):compare.compare(w,a,b,new org.worldgit.core.model.DimensionId(dimension)));
  }
  private ResponseEntity<byte[]> response(Object value) throws IOException {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
        .body(BoundedJson.encode(json, value));
  }
}
