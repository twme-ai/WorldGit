package org.worldgit.hub.account;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.worldgit.hub.account.Models.*;
import org.worldgit.hub.storage.NameRules;
import org.worldgit.hub.web.ApiError;

@Service
public class OrganizationService {
  private final JdbcClient db;
  private final AccountService accounts;
  public OrganizationService(JdbcClient db, AccountService accounts) { this.db=db; this.accounts=accounts; }
  public String org(User user, String slug, Role needed) {
    String id = db.sql("SELECT id FROM owners WHERE slug = ? AND kind = 'ORG'").param(slug).query(String.class).optional().orElseThrow(() -> new ApiError.NotFound("找不到組織"));
    Role role = accounts.roleOnOwner(user,id);
    if (role == Role.NONE) throw new ApiError.NotFound("找不到組織");
    if (!role.atLeast(needed)) throw new SecurityException("組織權限不足");
    return id;
  }
  public List<Map<String,Object>> list(User u) {
    return db.sql("SELECT o.slug,o.display_name,m.role FROM owners o JOIN memberships m ON m.owner_id=o.id WHERE o.kind='ORG' AND m.user_id=? ORDER BY o.slug")
      .param(u.id()).query((rs,n)->Map.<String,Object>of("slug",rs.getString(1),"displayName",rs.getString(2),"role",Role.parse(rs.getString(3)).api())).list();
  }
  public List<Map<String,Object>> members(String org) {
    return db.sql("SELECT u.username,m.role FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.owner_id=? ORDER BY u.username").param(org)
      .query((rs,n)->Map.<String,Object>of("username",rs.getString(1),"role",Role.parse(rs.getString(2)).api())).list();
  }
  public List<Map<String,Object>> teams(String org) {
    return db.sql("SELECT id,slug FROM teams WHERE owner_id=? ORDER BY slug").param(org).query((rs,n)->Map.<String,Object>of("id",rs.getString(1),"slug",rs.getString(2))).list();
  }
  public String team(String org,String slug) {
    return db.sql("SELECT id FROM teams WHERE owner_id=? AND slug=?").params(org,slug).query(String.class).optional().orElseThrow(()->new ApiError.NotFound("找不到團隊"));
  }
  @Transactional public String create(String org,String slug) {
    NameRules.requireSlug(slug);
    String id=UUID.randomUUID().toString();
    db.sql("INSERT INTO teams(id,owner_id,slug) VALUES (?,?,?)").params(id,org,slug).update(); return id;
  }
  @Transactional public void member(String org,String team,String username,boolean present) {
    var u=accounts.findUser(username).orElseThrow(()->new ApiError.NotFound("找不到使用者"));
    if (present && !accounts.roleOnOwner(u,org).atLeast(Role.READER)) throw new IllegalArgumentException("團隊成員必須是組織成員");
    db.sql("DELETE FROM team_members WHERE team_id=? AND user_id=?").params(team,u.id()).update();
    if(present) db.sql("INSERT INTO team_members(team_id,user_id) VALUES (?,?)").params(team,u.id()).update();
  }
  public List<String> teamMembers(String team) {
    return db.sql("SELECT u.username FROM team_members m JOIN users u ON u.id=m.user_id WHERE team_id=? ORDER BY u.username").param(team).query(String.class).list();
  }
  @Transactional public void grant(WorldRow w,User actor,String slug,Role role) {
    if(!accounts.roleOn(actor,w).atLeast(Role.ADMIN)) throw new SecurityException("需要 admin");
    if(role==Role.OWNER || !accounts.roleOn(actor,w).atLeast(role)) throw new IllegalArgumentException("團隊不能授予 owner 或高於自身權限");
    String id=team(w.ownerId(),slug);
    db.sql("DELETE FROM team_grants WHERE world_id=? AND team_id=?").params(w.id(),id).update();
    if(role!=Role.NONE) db.sql("INSERT INTO team_grants(world_id,team_id,role) VALUES (?,?,?)").params(w.id(),id,role.name()).update();
  }
  public List<Map<String,Object>> grants(WorldRow w) {
    return db.sql("SELECT t.slug,g.role FROM team_grants g JOIN teams t ON t.id=g.team_id WHERE g.world_id=? ORDER BY t.slug").param(w.id()).query((rs,n)->Map.<String,Object>of("team",rs.getString(1),"role",Role.parse(rs.getString(2)).api())).list();
  }
}
