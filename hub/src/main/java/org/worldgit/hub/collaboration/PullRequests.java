package org.worldgit.hub.collaboration;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.worldgit.core.merge.MergeReport;
import org.worldgit.core.model.*;
import org.worldgit.core.remote.*;
import org.worldgit.core.store.*;
import org.worldgit.hub.account.*;
import org.worldgit.hub.account.Models.*;
import org.worldgit.hub.config.CollaborationProperties;
import org.worldgit.hub.git.RepoCache;
import org.worldgit.hub.history.*;
import org.worldgit.hub.storage.OwnerQuota;
import org.worldgit.hub.web.ApiError;

@Service
public class PullRequests {
  public record Pull(String id,int number,String authorId,String author,String source,String target,String title,String description,
      String status,String fingerprint,boolean selectionsInvalidated,long createdAt,long updatedAt,String snapshot,Object commits,String dimension,boolean legacy) {}
  public record Review(String userId,String username,String decision,String fingerprint,long at) {}
  public record Detail(Pull pr,MergePreviewService.Report preview,Map<Integer,String> choices,List<Review> reviews,
      String mergeability,int approvals,int requiredReviews,List<Dto.CommitInfo> commits,boolean commitsTruncated) {}
  private final JdbcClient db;private final AccountService accounts;private final WorldGroups groups;private final MergePreviewService previews;
  private final BranchPolicy policy;private final OwnerQuota quota;private final RepoCache repos;private final EventService events;private final TransactionTemplate tx;
  private final long mergeLockTimeoutNanos;
  public PullRequests(JdbcClient db,AccountService accounts,WorldGroups groups,MergePreviewService previews,BranchPolicy policy,OwnerQuota quota,RepoCache repos,EventService events,PlatformTransactionManager manager,CollaborationProperties props) {
    this.db=db;this.accounts=accounts;this.groups=groups;this.previews=previews;this.policy=policy;this.quota=quota;this.repos=repos;this.events=events;this.tx=new TransactionTemplate(manager);
    this.mergeLockTimeoutNanos=props.mergeLockTimeout().toNanos();
  }
  private Pull row(java.sql.ResultSet rs,int n) throws java.sql.SQLException { return new Pull(rs.getString(1),rs.getInt(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getInt(11)!=0,rs.getLong(12),rs.getLong(13),rs.getString(14),rs.getString(15)==null?Map.of():events.decode(rs.getString(15)),rs.getString(16),rs.getInt(17)!=0); }
  private static final String SELECT="SELECT p.id,p.number,p.author_id,u.username,p.source_branch,p.target_branch,p.title,p.description,p.status,p.fingerprint,p.invalidated,p.created_at,p.updated_at,p.merged_snapshot,p.merge_commits,p.dimension,p.legacy FROM pull_requests p JOIN users u ON u.id=p.author_id ";
  public Pull find(WorldRow w,String id){return db.sql(SELECT+"WHERE p.world_id=? AND p.id=?").params(w.id(),id).query(this::row).optional().orElseThrow(()->new ApiError.NotFound("找不到 PR"));}
  private void writer(WorldRow w,User u){if(!accounts.roleOn(u,w).atLeast(Role.WRITER))throw new SecurityException("需要 write 權限");}
  private static void open(Pull p){if(!p.status().equals("open"))throw new ApiError.Conflict("PR 已結束");}
  public static String text(String s,int max,String label,boolean required){if(s==null)s="";if(s.length()>max || required && s.isBlank() || s.indexOf('\0')>=0)throw new IllegalArgumentException(label+"無效，最多 "+max+" 字");return s;}
  public Page<Pull> list(WorldRow w,String status,int offset,int limit) {
    return list(w,status,null,offset,limit);
  }
  public Page<Pull> list(WorldRow w,String status,String dimension,int offset,int limit){
    Page.offset(offset);Page.limit(limit);if(status!=null && !Set.of("open","merged","closed").contains(status))throw new IllegalArgumentException("PR 狀態無效");
    if(dimension!=null)new DimensionId(dimension);
    var params=new ArrayList<Object>();params.add(w.id());if(status!=null)params.add(status);if(dimension!=null)params.add(dimension);params.add(limit+1);params.add(offset);
    var rows=db.sql(SELECT+"WHERE p.world_id=? "+(status==null?"":"AND p.status=? ")+(dimension==null?"":"AND p.dimension=? ")+"ORDER BY p.created_at DESC,p.id LIMIT ? OFFSET ?").params(params.toArray());
    return Page.of(rows.query(this::row).list(),offset,limit);
  }
  public Pull create(WorldRow w,User u,String source,String target,String title,String description) throws IOException {
    return create(w,u,DimensionId.OVERWORLD,source,target,title,description);
  }
  public Pull create(WorldRow w,User u,DimensionId dimension,String source,String target,String title,String description) throws IOException {
    writer(w,u);BranchPolicy.branch(source);BranchPolicy.branch(target);if(source.equals(target))throw new IllegalArgumentException("來源與目標需不同");
    String t=text(title,200,"標題",true),d=text(description,16000,"描述",false);
    var lock=quota.lock(w.ownerSlug());lock.lock();try {
      reconcile(w);groups.branchTips(w,dimension,target);groups.branchTips(w,dimension,source);var report=previews.report(w,target,source,dimension);
      return tx.execute(status->{
        long count=db.sql("SELECT COUNT(*) FROM pull_requests WHERE world_id=? AND status='open'").param(w.id()).query(Long.class).single();
        if(count>=500)throw new ApiError.Conflict("每個世界最多 500 個 open PR");
        int number=db.sql("SELECT COALESCE(MAX(number),0) FROM pull_requests WHERE world_id=?").param(w.id()).query(Integer.class).single()+1;
        String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();
        db.sql("INSERT INTO pull_requests(id,world_id,number,author_id,source_branch,target_branch,title,description,status,fingerprint,created_at,updated_at,dimension,legacy) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,0)").params(id,w.id(),number,u.id(),source,target,t,d,"open",report.fingerprint(),now,now,dimension.value()).update();
        events.emit(w,"pr.opened",Map.of("pr",id,"number",number,"source",source,"target",target,"title",t,"dimension",dimension.value()),Set.of(u.id()));return find(w,id);
      });
    }finally{lock.unlock();}
  }
  public Detail detail(WorldRow w,String id) throws IOException {
    var l=quota.lock(w.ownerSlug());l.lock();try{reconcile(w);return detailLocked(w,id);}finally{l.unlock();}
  }
  private Detail detailLocked(WorldRow w,String id) throws IOException {
    var p=find(w,id);
    if(!p.status().equals("open"))return new Detail(p,null,choices(p),reviews(p),p.status(),0,0,List.of(),false);
    var dimId=new DimensionId(p.dimension());var target=groups.branchTips(w,dimId,p.target());var source=groups.branchTips(w,dimId,p.source());
    var report=previews.report(w,p.target(),p.source(),dimId);
    if(!report.fingerprint().equals(p.fingerprint())) {
      tx.executeWithoutResult(s->{db.sql("DELETE FROM pr_choices WHERE pr_id=?").param(id).update();db.sql("DELETE FROM pr_reviews WHERE pr_id=?").param(id).update();
        db.sql("UPDATE pull_requests SET fingerprint=?,invalidated=1,updated_at=? WHERE id=?").params(report.fingerprint(),System.currentTimeMillis(),id).update();});p=find(w,id);
    }
    var selections=choices(p);var reviews=reviews(p);int required=policy.find(w.id(),p.dimension(),p.target()).map(BranchPolicy.Rule::reviews).orElse(0);
    int approved=0;boolean changes=false;
    for(var r:reviews) {
      if(!report.fingerprint().equals(r.fingerprint()) || r.userId().equals(p.authorId()))continue;
      var reviewer=accounts.userById(r.userId()).orElse(null);if(reviewer==null || !accounts.roleOn(reviewer,w).atLeast(Role.WRITER))continue;
      if(r.decision().equals("approve"))approved++;else changes=true;
    }
    boolean ff=true;var commits=new LinkedHashMap<String,Dto.CommitInfo>();boolean truncated=false;
    for(var dim:target.keySet())try(var h=repos.open(w.ownerSlug(),w.slug(),dim);var walk=new RevWalk(h.repository())){
      ff &= walk.isMergedInto(walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(target.get(dim))),walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(source.get(dim))));
      walk.reset();walk.markStart(walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(source.get(dim))));walk.markUninteresting(walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(target.get(dim))));
      for(var c:walk){if(commits.size()>=200){truncated=true;break;}var rc=h.store().readCommit(c.name());commits.put(dim+"/"+c.name(),HistoryService.info(rc));}
    }
    String mergeability=!report.canMerge()?"unmergeable":changes?"changes-requested":approved<required?"needs-review":
      report.regions().stream().anyMatch(r->!Set.of("ours","theirs","base").contains(selections.getOrDefault(r.id(),"manual")))?"conflicts":ff?"ff":"clean";
    return new Detail(p,report,selections,reviews,mergeability,approved,required,List.copyOf(commits.values()),truncated);
  }
  private Map<Integer,String> choices(Pull p) {
    var out=new TreeMap<Integer,String>();db.sql("SELECT region_id,choice FROM pr_choices WHERE pr_id=? ORDER BY region_id").param(p.id()).query((rs,n)->Map.entry(rs.getInt(1),rs.getString(2))).list().forEach(e->out.put(e.getKey(),e.getValue()));return out;
  }
  private List<Review> reviews(Pull p){return db.sql("SELECT r.user_id,u.username,r.decision,r.fingerprint,r.at FROM pr_reviews r JOIN users u ON u.id=r.user_id WHERE r.pr_id=? ORDER BY r.at,r.user_id").param(p.id()).query((rs,n)->new Review(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5))).list();}
  public Detail choices(WorldRow w,User u,String id,String fingerprint,Map<Integer,String> values) throws IOException {
    writer(w,u);var l=quota.lock(w.ownerSlug());l.lock();try {
      var d=detailLocked(w,id);open(d.pr());lease(d,fingerprint);
      var valid=new HashSet<Integer>();d.preview().regions().forEach(r->valid.add(r.id()));
      if(values==null || values.size()>2000 || !valid.containsAll(values.keySet()) || values.values().stream().anyMatch(v->!Set.of("ours","theirs","base","manual").contains(v)))throw new IllegalArgumentException("區域選擇無效");
      tx.executeWithoutResult(s->{if(!d.choices().equals(values))db.sql("DELETE FROM pr_reviews WHERE pr_id=?").param(id).update();db.sql("DELETE FROM pr_choices WHERE pr_id=?").param(id).update();for(var e:values.entrySet())db.sql("INSERT INTO pr_choices(pr_id,region_id,choice) VALUES (?,?,?)").params(id,e.getKey(),e.getValue()).update();db.sql("UPDATE pull_requests SET invalidated=0,updated_at=? WHERE id=?").params(System.currentTimeMillis(),id).update();});
      return detailLocked(w,id);
    }finally{l.unlock();}
  }
  private static void lease(Detail d,String fingerprint){if(!Objects.equals(d.pr().fingerprint(),fingerprint))throw new ApiError.Conflict("分支 tip 已變動，請重新載入；舊選擇與審核已作廢");}
  public Detail review(WorldRow w,User u,String id,String fingerprint,String decision) throws IOException {
    writer(w,u);if(!Set.of("approve","request-changes").contains(decision))throw new IllegalArgumentException("審核無效");
    var l=quota.lock(w.ownerSlug());l.lock();try {
      var d=detailLocked(w,id);open(d.pr());lease(d,fingerprint);if(d.pr().authorId().equals(u.id()))throw new SecurityException("作者不能審核自己的 PR");
      tx.executeWithoutResult(s->{db.sql("DELETE FROM pr_reviews WHERE pr_id=? AND user_id=?").params(id,u.id()).update();db.sql("INSERT INTO pr_reviews(pr_id,user_id,decision,fingerprint,at) VALUES (?,?,?,?,?)").params(id,u.id(),decision,fingerprint,System.currentTimeMillis()).update();events.emit(w,"pr.reviewed",Map.of("pr",id,"decision",decision,"reviewer",u.username(),"dimension",d.pr().dimension()),Set.of(d.pr().authorId()));});return detailLocked(w,id);
    }finally{l.unlock();}
  }
  public Pull edit(WorldRow w,User u,String id,String title,String description,String status) {
    var l=quota.lock(w.ownerSlug());l.lock();try{var p=find(w,id);open(p);if(!u.id().equals(p.authorId()) && !accounts.roleOn(u,w).atLeast(Role.ADMIN))throw new SecurityException("需要作者或 admin");
      if(status!=null && !Set.of("open","closed").contains(status))throw new IllegalArgumentException("狀態無效");
      db.sql("UPDATE pull_requests SET title=?,description=?,status=?,updated_at=? WHERE id=? AND status='open'").params(title==null?p.title():text(title,200,"標題",true),description==null?p.description():text(description,16000,"描述",false),status==null?p.status():status,System.currentTimeMillis(),id).update();return find(w,id);
    }finally{l.unlock();}
  }
  public Pull merge(WorldRow w,User u,String id,String fingerprint) throws IOException {
    writer(w,u);var l=quota.lock(w.ownerSlug());
    // afterPush 的短暫維護也持有此鎖；等候有上限，取得後仍重新驗權限與分支 tip。
    try {
      if(!l.tryLock(mergeLockTimeoutNanos,TimeUnit.NANOSECONDS))throw new ApiError.Unavailable("等待世界推送、合併或維護逾時，請稍後重試");
    }catch(InterruptedException e){Thread.currentThread().interrupt();throw new ApiError.Unavailable("等待合併鎖時中斷，請稍後重試");}
    try {
      writer(w,u);reconcile(w);var d=detailLocked(w,id);open(d.pr());lease(d,fingerprint);
      if(!Set.of("ff","clean").contains(d.mergeability()))throw new ApiError.Conflict("尚不能合併："+d.mergeability());
      quota.check(w.ownerSlug());
      if(d.pr().legacy())throw new ApiError.Conflict("舊 PR 請先明確選擇維度並重新審核");
      var dimension=new DimensionId(d.pr().dimension());
      org.worldgit.core.operation.OperationProgress.report(dimension,"merge",0L,1L,org.worldgit.core.operation.OperationProgress.Unit.COMMIT);
      try(var core=new BareWorldMerge(groups.path(w,dimension),dimension)) {
        var preview=core.preview(d.pr().target(),d.pr().source(),1);
        // 最後再次比對所選維度的 tips。
        for(var dim:d.preview().dimensions()) {var c=preview.dimensions().get(new DimensionId(dim.dimension()));if(c==null || !Objects.equals(c.ours(),dim.ours()) || !Objects.equals(c.theirs(),dim.theirs()))throw new ApiError.Conflict("合併 tip 已變動");}
        var selected=new TreeMap<Integer,MergeReport.Choice>();d.choices().forEach((region,choice)->{if(!choice.equals("manual"))selected.put(region,MergeReport.Choice.valueOf(choice.toUpperCase(Locale.ROOT)));});
        // 先建候選、quota 預檢；dryRun 不發 refs。core 最終 merge 再驗 lease。
        var dry=core.merge(preview,selected,1,new CommitMetadata.Identity(u.username(),u.username()+"@users.worldgit.invalid"),d.pr().title(),true);
        if(!dry.state().equals("DRY_RUN"))throw new ApiError.Conflict("仍有未選衝突區域");quota.check(w.ownerSlug());
        db.sql("UPDATE pull_requests SET merge_pending=1 WHERE id=? AND status='open'").param(id).update();
        var result=core.merge(preview,selected,1,new CommitMetadata.Identity(u.username(),u.username()+"@users.worldgit.invalid"),"Merge PR #"+d.pr().number()+": "+d.pr().title(),false,Map.of("WorldGit-Merge-PR",id));
        if(!result.state().equals("COMPLETE")) {core.recover();db.sql("UPDATE pull_requests SET merge_pending=0 WHERE id=?").param(id).update();throw new ApiError.Conflict("發布中斷，已恢復，請重試");}
        finish(w,d.pr(),result.snapshot().toString(),WorldGroups.strings(result.commits()));
        org.worldgit.core.operation.OperationProgress.report(dimension,"publish",1L,1L,org.worldgit.core.operation.OperationProgress.Unit.COMMIT);
      }
      return find(w,id);
    }finally{l.unlock();}
  }
  public Pull selectDimension(WorldRow w,User u,String id,String selected) throws IOException {
    var d=new DimensionId(selected);var lock=quota.lock(w.ownerSlug());lock.lock();try {
      var p=find(w,id);open(p);writer(w,u);
      if(!u.id().equals(p.authorId()) && !accounts.roleOn(u,w).atLeast(Role.ADMIN))throw new SecurityException("需要作者或 admin");
      groups.branchTips(w,d,p.source());groups.branchTips(w,d,p.target());
      tx.executeWithoutResult(t->{db.sql("DELETE FROM pr_choices WHERE pr_id=?").param(id).update();db.sql("DELETE FROM pr_reviews WHERE pr_id=?").param(id).update();db.sql("UPDATE pull_requests SET dimension=?,legacy=0,fingerprint=NULL,invalidated=1 WHERE id=?").params(d.value(),id).update();db.sql("UPDATE comments SET project_dimension=? WHERE pr_id=?").params(d.value(),id).update();});
      return detailLocked(w,id).pr();
    }finally{lock.unlock();}
  }
  private void finish(WorldRow w,Pull p,String snapshot,Map<String,String> commits) {
    tx.executeWithoutResult(s->{int changed=db.sql("UPDATE pull_requests SET status='merged',merged_snapshot=?,merge_commits=?,merge_pending=0,updated_at=? WHERE id=? AND status='open'").params(snapshot,events.encode(commits),System.currentTimeMillis(),p.id()).update();if(changed==1)events.emit(w,"pr.merged",Map.of("pr",p.id(),"number",p.number(),"target",p.target(),"snapshot",snapshot,"commits",commits,"dimension",p.dimension()),Set.of(p.authorId()));});
  }
  /** DB finalize 前 JVM 中斷：完整 journal 的 WorldGit-Merge-PR trailer 重建結果；部分發布依 core recover 回舊 refs。 */
  public void reconcile(WorldRow w) throws IOException {
    var pending=db.sql("SELECT id FROM pull_requests WHERE world_id=? AND merge_pending=1").param(w.id()).query(String.class).list();if(pending.isEmpty())return;
    for(String id:pending){
      var p=find(w,id);if(p.legacy() && Files.isRegularFile(groups.root(w).resolve("bare-merge-state.yml"))){reconcileLegacy(w,p);continue;}var dimension=new DimensionId(p.dimension());
      var statePath=groups.path(w,dimension).resolve("bare-merge-state.yml");
      var state=Files.isRegularFile(statePath)?BoundedYaml.parse(Files.readString(statePath)):Map.of();
      if("COMPLETE".equals(state.get("state")) && !Boolean.TRUE.equals(state.get("recovered")) && state.get("changes") instanceof List<?> changes) {
        for(var item:changes)if(item instanceof Map<?,?> c && ("refs/heads/"+p.target()).equals(c.get("ref")) && dimension.value().equals(c.get("dimension"))) {
          String target=c.get("target").toString();
          try(var h=repos.open(w.ownerSlug(),w.slug(),dimension)) {
            var commit=h.store().readCommit(target);
            var raw=h.repository().parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(target)).getFullMessage();
            if(raw.contains("WorldGit-Merge-PR: "+id)){finish(w,p,commit.metadata().snapshot().toString(),Map.of(dimension.value(),target));break;}
          }
        }
        if(find(w,id).status().equals("merged"))continue;
      }
      try(var core=new BareWorldMerge(groups.path(w,dimension),dimension)){core.recover();}
      db.sql("UPDATE pull_requests SET merge_pending=0 WHERE id=?").param(id).update();
    }
  }
  /** 只供升級時恢復既有 Phase 4 journal；新操作一律單維度。 */
  private void reconcileLegacy(WorldRow w,Pull p)throws IOException {
    var path=groups.root(w).resolve("bare-merge-state.yml");var state=BoundedYaml.parse(Files.readString(path));
    if("COMPLETE".equals(state.get("state")) && !Boolean.TRUE.equals(state.get("recovered")) && state.get("changes") instanceof List<?> changes){
      var commits=new TreeMap<String,String>();String snapshot=null;
      for(var row:changes)if(row instanceof Map<?,?> c && ("refs/heads/"+p.target()).equals(c.get("ref"))){
        var dim=new DimensionId(c.get("dimension").toString());String target=c.get("target").toString();
        try(var h=repos.open(w.ownerSlug(),w.slug(),dim)){
          String raw=h.repository().parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(target)).getFullMessage();
          if(!raw.contains("WorldGit-Merge-PR: "+p.id())){commits.clear();break;}
          commits.put(dim.value(),target);snapshot=h.store().readCommit(target).metadata().snapshot().toString();
        }
      }
      if(!commits.isEmpty()){finish(w,p,snapshot,commits);return;}
    }
    try(var core=new BareWorldMerge(groups.root(w),groups.paths(w))){core.recover();}
    db.sql("UPDATE pull_requests SET merge_pending=0 WHERE id=?").param(p.id()).update();
  }

}
