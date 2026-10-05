package org.worldgit.hub.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.worldgit.hub.account.*;
import org.worldgit.hub.account.Models.*;

@RestController
@RequestMapping("/api/v1")
public class PermissionController {
  private final Access access; private final AccountService accounts; private final OrganizationService orgs;
  public PermissionController(Access access,AccountService accounts,OrganizationService orgs) { this.access=access; this.accounts=accounts; this.orgs=orgs; }
  record Grant(String role) {}
  @GetMapping("/orgs") Object orgs(HttpServletRequest req) { return orgs.list(access.requireUser(req)); }
  @GetMapping("/orgs/{org}/members") Object members(HttpServletRequest req,@PathVariable String org) { return orgs.members(orgs.org(access.requireUser(req),org,Role.READER)); }
  @GetMapping("/orgs/{org}/teams") Object teams(HttpServletRequest req,@PathVariable String org) { return orgs.teams(orgs.org(access.requireUser(req),org,Role.READER)); }
  record Team(String slug) {}
  @PostMapping("/orgs/{org}/teams") Object createTeam(HttpServletRequest req,@PathVariable String org,@RequestBody Team b) {
    access.scope(req,"admin"); return Map.of("id",orgs.create(orgs.org(access.requireUser(req),org,Role.ADMIN),b.slug()));
  }
  @GetMapping("/orgs/{org}/teams/{team}/members") Object teamMembers(HttpServletRequest req,@PathVariable String org,@PathVariable String team) {
    return orgs.teamMembers(orgs.team(orgs.org(access.requireUser(req),org,Role.READER),team));
  }
  @PutMapping("/orgs/{org}/teams/{team}/members/{username}") Object add(HttpServletRequest req,@PathVariable String org,@PathVariable String team,@PathVariable String username) { return member(req,org,team,username,true); }
  @DeleteMapping("/orgs/{org}/teams/{team}/members/{username}") Object remove(HttpServletRequest req,@PathVariable String org,@PathVariable String team,@PathVariable String username) { return member(req,org,team,username,false); }
  private Object member(HttpServletRequest req,String org,String team,String username,boolean add) {
    access.scope(req,"admin"); String id=orgs.org(access.requireUser(req),org,Role.ADMIN); orgs.member(id,orgs.team(id,team),username,add); return Map.of("ok",true);
  }
  @GetMapping("/worlds/{owner}/{world}/permissions") Object permissions(HttpServletRequest req,@PathVariable String owner,@PathVariable String world) {
    var w=access.world(req,owner,world,Role.ADMIN); return Map.of("users",accounts.grants(w),"teams",orgs.grants(w),"public",w.isPublic());
  }
  @PutMapping("/worlds/{owner}/{world}/permissions/users/{username}") Object grant(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String username,@RequestBody Grant b) {
    var w=access.world(req,owner,world,Role.ADMIN); accounts.grant(w,access.requireUser(req),username,Role.parse(b.role())); return Map.of("ok",true);
  }
  @PutMapping("/worlds/{owner}/{world}/permissions/teams/{team}") Object grantTeam(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String team,@RequestBody Grant b) {
    var w=access.world(req,owner,world,Role.ADMIN); orgs.grant(w,access.requireUser(req),team,Role.parse(b.role())); return Map.of("ok",true);
  }
  record Visibility(boolean isPublic) {}
  @PutMapping("/worlds/{owner}/{world}/visibility") Object visibility(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@RequestBody Visibility b) {
    var w=access.world(req,owner,world,Role.ADMIN);if(w.isPublic()==b.isPublic())req.setAttribute("worldgit.noop",true);accounts.visibility(w,b.isPublic()); return Map.of("ok",true);
  }
}
