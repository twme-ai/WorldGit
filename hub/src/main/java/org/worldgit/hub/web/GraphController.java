package org.worldgit.hub.web;

import java.io.*;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.worldgit.core.graph.CommitGraph;
import org.worldgit.hub.git.RepoCache;
import org.worldgit.hub.account.Models.Role;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 先授權，再讀圖；lane 與 edges 原樣使用 core，合併關係由 PR 的固定 commit map 關聯。 */
@RestController
@RequestMapping("/api/v1/worlds/{owner}/{world}/dims/{dim}")
public class GraphController {
  private final Access access;private final RepoCache repos;private final JdbcClient db;private final org.worldgit.hub.storage.OwnerQuota quota;private final org.worldgit.hub.storage.RepoStorage storage;
  public GraphController(Access access,RepoCache repos,JdbcClient db,org.worldgit.hub.storage.OwnerQuota quota,org.worldgit.hub.storage.RepoStorage storage){this.access=access;this.repos=repos;this.db=db;this.quota=quota;this.storage=storage;}
  @GetMapping("/graph") public Object graph(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String dim,@RequestParam(defaultValue="200") int limit,@RequestParam(defaultValue="true") boolean all)throws IOException {
    var w=access.world(req,owner,world,Role.READER);var d=Access.dimension(dim);
    if(!storage.exists(owner,world,d))throw new ApiError.NotFound("找不到維度");
    if(limit<1 || limit>10000)throw new IllegalArgumentException("graph limit 必須介於 1–10000");
    try(var h=repos.open(owner,world,d)) {
      // 額外限制 refs，避免 all=false 仍被大量標籤耗盡資源。
      int labels=0;for(String prefix:List.of("refs/heads/","refs/tags/","refs/remotes/"))labels+=h.store().refsByPrefix(prefix).size();
      if(labels>2000)throw new org.worldgit.core.normalize.DecodeBudget.Exceeded("graph refs 上限 2000");
      var graph=CommitGraph.read(h.store(),limit,all);var ids=new HashSet<String>();graph.nodes().forEach(n->ids.add(n.id()));
      var merges=new ArrayList<Map<String,Object>>();
      for(var p:db.sql("SELECT id,number,merge_commits FROM pull_requests WHERE world_id=? AND (dimension=? OR legacy=1) AND status='merged' ORDER BY updated_at DESC LIMIT 10000").params(w.id(),d.value()).query((rs,n)->List.of(rs.getString(1),rs.getString(2),rs.getString(3))).list()) {
        var map=new com.fasterxml.jackson.databind.ObjectMapper().readTree(p.get(2));var id=map.path(d.value()).asText();
        if(ids.contains(id))merges.add(Map.of("commit",id,"id",p.get(0),"number",Integer.parseInt(p.get(1))));
      }
      return Map.of("dimension",d.value(),"graph",graph,"merges",merges,"defaultBranch",Objects.toString(h.store().headState().branch(),"main"));
    }
  }
  record DefaultBranch(String branch) {}
  @PutMapping("/default-branch") public Object defaultBranch(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String dim,@RequestBody DefaultBranch body)throws IOException {
    access.world(req,owner,world,Role.ADMIN);var d=Access.dimension(dim);org.worldgit.hub.collaboration.BranchPolicy.branch(body.branch());
    var lock=quota.lock(owner);lock.lock();
    try(var h=repos.open(owner,world,d)) {
      if(h.repository().exactRef("refs/heads/"+body.branch())==null)throw new ApiError.NotFound("找不到分支");
      if(body.branch().equals(h.store().headState().branch()))req.setAttribute("worldgit.noop",true);
      h.repository().updateRef("HEAD").link("refs/heads/"+body.branch());
    }
    finally{lock.unlock();}
    return Map.of("branch",body.branch(),"dimension",d.value());
  }
}
