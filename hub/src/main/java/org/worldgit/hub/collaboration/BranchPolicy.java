package org.worldgit.hub.collaboration;

import java.util.*;
import org.eclipse.jgit.lib.Repository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.worldgit.hub.account.Models.WorldRow;
import org.worldgit.hub.storage.OwnerQuota;

@Service
public class BranchPolicy {
  public record Rule(String branch,boolean prOnly,int reviews) {}
  private final JdbcClient db;private final OwnerQuota quota;
  public BranchPolicy(JdbcClient db,OwnerQuota quota){this.db=db;this.quota=quota;}
  public static void branch(String b) {if(b==null || b.length()>100 || !Repository.isValidRefName("refs/heads/"+b)) throw new IllegalArgumentException("分支名稱無效");}
  public List<Rule> list(String worldId) {return db.sql("SELECT branch,pr_only,reviews FROM branch_rules WHERE world_id=? ORDER BY branch").param(worldId).query((rs,n)->new Rule(rs.getString(1),rs.getInt(2)!=0,rs.getInt(3))).list();}
  public Optional<Rule> find(String worldId,String branch){return list(worldId).stream().filter(r->r.branch().equals(branch)).findFirst();}
  public void set(WorldRow w,Rule rule) {
    branch(rule.branch());if(rule.reviews()<0 || rule.reviews()>10) throw new IllegalArgumentException("審核數 0–10");
    if(rule.reviews()>0 && !rule.prOnly()) throw new IllegalArgumentException("要求審核時必須只能經 PR 合併");
    var l=quota.lock(w.ownerSlug());l.lock();try{
      // 先更新、沒有才新增；單一 owner 寫入協調也涵蓋 receive-pack。
      int n=db.sql("UPDATE branch_rules SET pr_only=?,reviews=? WHERE world_id=? AND branch=?").params(rule.prOnly()?1:0,rule.reviews(),w.id(),rule.branch()).update();
      if(n==0) db.sql("INSERT INTO branch_rules(world_id,branch,pr_only,reviews) VALUES (?,?,?,?)").params(w.id(),rule.branch(),rule.prOnly()?1:0,rule.reviews()).update();
    }finally{l.unlock();}
  }
  public void delete(WorldRow w,String branch){var l=quota.lock(w.ownerSlug());l.lock();try{db.sql("DELETE FROM branch_rules WHERE world_id=? AND branch=?").params(w.id(),branch).update();}finally{l.unlock();}}
}
