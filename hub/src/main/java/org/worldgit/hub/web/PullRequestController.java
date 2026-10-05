package org.worldgit.hub.web;

import java.io.*;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.worldgit.hub.account.Models.Role;
import org.worldgit.hub.account.Models.WorldRow;
import org.worldgit.hub.collaboration.PullRequests;

@RestController
@RequestMapping("/api/v1/worlds/{owner}/{world}/pulls")
public class PullRequestController {
  private final Access access;private final PullRequests prs;
  public PullRequestController(Access access,PullRequests prs){this.access=access;this.prs=prs;}
  private WorldRow project(HttpServletRequest req,String owner,String world,Role role,String id){var w=access.world(req,owner,world,role);req.setAttribute("worldgit.dimension",prs.find(w,id).dimension());return w;}
  @GetMapping Object list(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@RequestParam(required=false) String status,@RequestParam(required=false) String dimension,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit){return prs.list(access.world(req,owner,world,Role.READER),status,dimension,offset,limit);}
  record New(String dimension,String source,String target,String title,String description) {}
  @PostMapping Object create(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@RequestBody New b)throws IOException {var w=access.world(req,owner,world,Role.WRITER);var d=new org.worldgit.core.model.DimensionId(b.dimension()==null?"minecraft:overworld":b.dimension());req.setAttribute("worldgit.dimension",d.value());return prs.create(w,access.requireUser(req),d,b.source(),b.target(),b.title(),b.description());}
  @GetMapping("/{id}") Object get(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String id)throws IOException {return prs.detail(project(req,owner,world,Role.READER,id),id);}
  record Edit(String dimension,String title,String description,String status) {}
  @PatchMapping("/{id}") Object edit(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String id,@RequestBody Edit b){var w=project(req,owner,world,Role.READER,id);access.scope(req,"write");if(b.dimension()!=null)try{req.setAttribute("worldgit.dimension",b.dimension());prs.selectDimension(w,access.requireUser(req),id,b.dimension());}catch(IOException e){throw new java.io.UncheckedIOException(e);}return prs.edit(w,access.requireUser(req),id,b.title(),b.description(),b.status());}
  record Choices(String fingerprint,Map<Integer,String> choices) {}
  @PutMapping("/{id}/choices") Object choices(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String id,@RequestBody Choices b)throws IOException{return prs.choices(project(req,owner,world,Role.WRITER,id),access.requireUser(req),id,b.fingerprint(),b.choices());}
  record Review(String fingerprint,String decision) {}
  @PostMapping("/{id}/reviews") Object review(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String id,@RequestBody Review b)throws IOException{return prs.review(project(req,owner,world,Role.WRITER,id),access.requireUser(req),id,b.fingerprint(),b.decision());}
  record Merge(String fingerprint) {}
  @PostMapping("/{id}/merge") Object merge(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String id,@RequestBody Merge b)throws IOException{return prs.merge(project(req,owner,world,Role.WRITER,id),access.requireUser(req),id,b.fingerprint());}
}
