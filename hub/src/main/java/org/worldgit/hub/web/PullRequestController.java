package org.worldgit.hub.web;

import java.io.*;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.worldgit.hub.account.Models.Role;
import org.worldgit.hub.collaboration.PullRequests;

@RestController
@RequestMapping("/api/v1/worlds/{owner}/{world}/pulls")
public class PullRequestController {
  private final Access access;private final PullRequests prs;
  public PullRequestController(Access access,PullRequests prs){this.access=access;this.prs=prs;}
  @GetMapping Object list(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit){return prs.list(access.world(req,owner,world,Role.READER),status,offset,limit);}
  record New(String source,String target,String title,String description) {}
  @PostMapping Object create(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@RequestBody New b)throws IOException {return prs.create(access.world(req,owner,world,Role.WRITER),access.requireUser(req),b.source(),b.target(),b.title(),b.description());}
  @GetMapping("/{id}") Object get(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String id)throws IOException {return prs.detail(access.world(req,owner,world,Role.READER),id);}
  record Edit(String title,String description,String status) {}
  @PatchMapping("/{id}") Object edit(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String id,@RequestBody Edit b){var w=access.world(req,owner,world,Role.READER);access.scope(req,"write");return prs.edit(w,access.requireUser(req),id,b.title(),b.description(),b.status());}
  record Choices(String fingerprint,Map<Integer,String> choices) {}
  @PutMapping("/{id}/choices") Object choices(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String id,@RequestBody Choices b)throws IOException{return prs.choices(access.world(req,owner,world,Role.WRITER),access.requireUser(req),id,b.fingerprint(),b.choices());}
  record Review(String fingerprint,String decision) {}
  @PostMapping("/{id}/reviews") Object review(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String id,@RequestBody Review b)throws IOException{return prs.review(access.world(req,owner,world,Role.WRITER),access.requireUser(req),id,b.fingerprint(),b.decision());}
  record Merge(String fingerprint) {}
  @PostMapping("/{id}/merge") Object merge(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String id,@RequestBody Merge b)throws IOException{return prs.merge(access.world(req,owner,world,Role.WRITER),access.requireUser(req),id,b.fingerprint());}
}
