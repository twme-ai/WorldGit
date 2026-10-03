package org.worldgit.hub.collaboration;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.eclipse.jgit.revwalk.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.remote.*;
import org.worldgit.core.store.RefStore;
import org.worldgit.hub.account.Models.*;
import org.worldgit.hub.config.*;
import org.worldgit.hub.git.RepoCache;
import org.worldgit.hub.storage.OwnerQuota;
import org.worldgit.hub.web.ApiError;

@Service
public class Releases {
  public record Release(String id,String tag,String title,String body,Map<String,String> commits,String authorId,long createdAt) {}
  private final JdbcClient db;private final WorldGroups groups;private final RepoCache repos;private final EventService events;private final OwnerQuota quota;private final CollaborationProperties.Downloads budget;private final Path temp;private final Semaphore permits;private final TransactionTemplate tx;
  public Releases(JdbcClient db,WorldGroups groups,RepoCache repos,EventService events,OwnerQuota quota,CollaborationProperties props,HubProperties hub,PlatformTransactionManager manager){this.db=db;this.groups=groups;this.repos=repos;this.events=events;this.quota=quota;this.budget=props.downloads();this.temp=hub.dataDir().resolve("downloads");this.permits=new Semaphore(budget.concurrency());this.tx=new TransactionTemplate(manager);}
  private Release row(java.sql.ResultSet rs,int n)throws java.sql.SQLException {
    var commits=new TreeMap<String,String>();((Map<?,?>)events.decode(rs.getString(5))).forEach((k,v)->commits.put(k.toString(),v.toString()));return new Release(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),commits,rs.getString(6),rs.getLong(7));
  }
  private static final String SELECT="SELECT id,tag,title,body,commits,author_id,created_at FROM releases ";
  public Release find(WorldRow w,String id){return db.sql(SELECT+"WHERE world_id=? AND id=?").params(w.id(),id).query(this::row).optional().orElseThrow(()->new ApiError.NotFound("找不到 release"));}
  public Page<Release> list(WorldRow w,int offset,int limit){Page.offset(offset);Page.limit(limit);return Page.of(db.sql(SELECT+"WHERE world_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?").params(w.id(),limit+1,offset).query(this::row).list(),offset,limit);}
  public Release create(WorldRow w,User u,String tag,String title,String body)throws IOException {
    if(tag==null || tag.length()>100 || !org.eclipse.jgit.lib.Repository.isValidRefName("refs/tags/"+tag))throw new IllegalArgumentException("tag 無效");
    String t=PullRequests.text(title,200,"標題",true),b=PullRequests.text(body,16000,"說明",false);var lock=quota.lock(w.ownerSlug());lock.lock();try{
      var paths=groups.paths(w);var commits=new TreeMap<DimensionId,RefStore.Commit>();
      for(var d:paths.keySet())try(var h=repos.open(w.ownerSlug(),w.slug(),d);var walk=new RevWalk(h.repository())){var ref=h.repository().exactRef("refs/tags/"+tag);if(ref==null)throw new ApiError.Conflict("tag 缺少維度："+d);var obj=walk.peel(walk.parseAny(ref.getObjectId()));if(!(obj instanceof RevCommit c))throw new IllegalArgumentException("tag 必須指向 commit");commits.put(d,h.store().readCommit(c.name()));}
      RepositoryGroup.validateSnapshot(commits,(d,s)->{try(var h=repos.open(w.ownerSlug(),w.slug(),d)){return h.store().resolve("refs/worldgit/groups/"+s);}});
      var ids=new TreeMap<String,String>();commits.forEach((d,c)->ids.put(d.value(),c.id()));String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();
      return tx.execute(status->{db.sql("INSERT INTO releases(id,world_id,tag,title,body,commits,author_id,created_at) VALUES (?,?,?,?,?,?,?,?)").params(id,w.id(),tag,t,b,events.encode(ids),u.id(),now).update();events.emit(w,"release",Map.of("release",id,"tag",tag,"commits",ids),Set.of(u.id()));return find(w,id);});
    }finally{lock.unlock();}
  }
  /** 同步 servlet 串流：先取得許可才設定 200；中斷關閉 stream，暫存與許可一定釋放。無 ZIP 磁碟快取。 */
  public void zip(WorldRow w,String id,jakarta.servlet.http.HttpServletResponse response)throws IOException {
    var release=find(w,id);if(!permits.tryAcquire())throw new ApiError.Unavailable("下載併發已滿");
    var lock=quota.lock(w.ownerSlug());if(!lock.tryLock()){permits.release();throw new ApiError.Unavailable("世界正在寫入");}
    var parsing=budget.limits();
    try(var scope=new org.worldgit.core.normalize.DecodeBudget(parsing.decodedBytes(),parsing.readBytes(),parsing.nodes(),parsing.objects(),parsing.work()).open();var group=new RepositoryGroup(groups.root(w),groups.paths(w))){
      var commits=new TreeMap<DimensionId,RefStore.Commit>();for(var e:release.commits().entrySet()){var d=new DimensionId(e.getKey());if(!group.repos().containsKey(d))throw new IOException("release 維度已不存在");commits.put(d,group.repos().get(d).refs().readCommit(e.getValue()));}group.validate(commits);
      response.setContentType("application/zip");response.setHeader("Content-Disposition","attachment; filename=world-"+release.id()+".zip");response.setHeader("Cache-Control","private, no-store");
      new WorldAssembler(new WorldAssembler.Budget(budget.maxBytes(),Duration.ofSeconds(budget.maxSeconds()))).zip(group,commits,response.getOutputStream(),temp);
    }catch(IOException ex){if(!response.isCommitted()){response.resetBuffer();response.setContentType("application/json");response.setHeader("Content-Disposition",null);if(ex instanceof org.worldgit.core.normalize.DecodeBudget.Exceeded)throw ex;if(ex.getMessage()!=null && ex.getMessage().contains("預算"))throw new org.worldgit.core.normalize.DecodeBudget.Exceeded("下載超過大小或時間預算");}throw ex;}finally{lock.unlock();permits.release();}
  }
}
