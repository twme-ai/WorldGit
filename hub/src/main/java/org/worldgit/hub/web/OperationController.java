package org.worldgit.hub.web;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.nio.file.Files;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.*;
import org.worldgit.hub.account.*;
import org.worldgit.hub.account.Models.*;
import org.worldgit.hub.operation.*;
import org.worldgit.hub.collaboration.*;
import org.worldgit.hub.history.MergePreviewService;
import org.worldgit.core.model.DimensionId;

@RestController
@RequestMapping("/api/v1/worlds/{owner}/{world}/operations")
public class OperationController {
  private final org.worldgit.hub.account.RequestUser users;private final Access access;private final AccountService accounts;private final Operations operations;private final PullRequests prs;private final MergePreviewService previews;private final Releases releases;
  private final Semaphore downloads=new Semaphore(2);
  private final ScheduledExecutorService streamers=Executors.newScheduledThreadPool(2);
  private final ConcurrentHashMap<String,AtomicInteger> perUser=new ConcurrentHashMap<>();private final AtomicInteger connections=new AtomicInteger();
  public OperationController(org.worldgit.hub.account.RequestUser users,Access access,AccountService accounts,Operations operations,PullRequests prs,MergePreviewService previews,Releases releases){this.users=users;this.access=access;this.accounts=accounts;this.operations=operations;this.prs=prs;this.previews=previews;this.releases=releases;}
  public record Start(String operation,String dimension,String pr,String fingerprint,String ours,String theirs,String release,Map<String,String> revisions) {}
  @PostMapping ResponseEntity<Object> start(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@RequestBody Start b)throws IOException {
    if(!Set.of("merge","preview","release-zip","world-zip").contains(Objects.toString(b.operation(),"")))throw new IllegalArgumentException("操作無效");
    var needed=b.operation().equals("merge")?Role.WRITER:Role.READER;
    var w=access.world(req,owner,world,needed);var u=actor(req);
    var d=new DimensionId(b.dimension()==null?"minecraft:overworld":b.dimension());
    if(b.operation().equals("merge"))d=new DimensionId(prs.find(w,b.pr()).dimension());
    req.setAttribute("worldgit.dimension",d.value());
    var dimension=d;var limits=operations.reports().secrets(req);
    var entry=operations.submit(w,u,b.operation(),dimension,!b.operation().equals("merge"),b.operation().endsWith("zip"),limits,e->{
      var fresh=accounts.findWorld(owner,world).orElseThrow(()->new ApiError.NotFound("找不到世界"));
      var actor=u.id().startsWith("session:")?null:accounts.userById(u.id()).orElseThrow(()->new ApiError.Unauthorized("帳號已失效"));
      if(!accounts.roleOn(actor,fresh).atLeast(needed))throw new SecurityException("操作權限已撤銷");
      return switch(b.operation()) {
        case "merge" -> prs.merge(fresh,actor,b.pr(),b.fingerprint());
        case "preview" -> previews.report(fresh,b.ours(),b.theirs(),dimension);
        default -> {var path=operations.artifact(e);releases.prepare(fresh,b.release(),b.revisions(),path);yield Map.of("bytes",Files.size(path));}
      };
    });
    return ResponseEntity.accepted().body(Map.of("id",entry.id,"operation",entry.operation));
  }
  private User actor(HttpServletRequest req){
    var user=access.optionalUser(req);if(user!=null)return user;
    var session=req.getSession();Object id=session.getAttribute("worldgit.operation.actor");
    if(!(id instanceof String)){id="session:"+UUID.randomUUID();session.setAttribute("worldgit.operation.actor",id);}
    return new User(id.toString(),"","anonymous",false);
  }
  private Operations.Entry allowed(HttpServletRequest req,String owner,String world,String id) {
    var w=access.world(req,owner,world,Role.READER);var u=actor(req);var e=operations.find(id);
    if(!e.world.id().equals(w.id()) || !u.id().equals(e.actor))throw new ApiError.NotFound("找不到操作");if(e.dimension!=null)req.setAttribute("worldgit.dimension",e.dimension.value());return e;
  }
  @GetMapping Object list(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@RequestParam(defaultValue="20") int limit){
    var w=access.world(req,owner,world,Role.READER);return operations.list(w.id(),actor(req).id(),limit);
  }
  @GetMapping("/{id}") Object get(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String id){return allowed(req,owner,world,id).snapshot();}
  @PostMapping("/{id}/cancel") Object cancel(HttpServletRequest req,@PathVariable String owner,@PathVariable String world,@PathVariable String id){
    access.scope(req,"read");var e=allowed(req,owner,world,id);
    if(e.result!=null){req.setAttribute("worldgit.noop",true);return Map.of("cancelled",false);}
    if(!e.cancellable)throw new ApiError.Conflict("此操作不可取消");e.cancelRequested=true;if(e.context!=null)e.context.cancel();return Map.of("cancelled",true);
  }
  @GetMapping("/{id}/download") void download(HttpServletRequest req,HttpServletResponse res,@PathVariable String owner,@PathVariable String world,@PathVariable String id)throws IOException {
    var e=allowed(req,owner,world,id);if(e.result==null || e.result.status()!=org.worldgit.core.operation.OperationResult.Status.SUCCESS || e.artifact==null)throw new ApiError.Conflict("下載尚未準備完成");
    if(!downloads.tryAcquire())throw new ApiError.Unavailable("最多 2 個同時 ZIP 下載");
    try{res.setContentType("application/zip");res.setHeader("Cache-Control","private, no-store");res.setHeader("Content-Disposition","attachment; filename=world-"+e.id+".zip");Files.copy(e.artifact,res.getOutputStream());operations.consumed(e);}finally{downloads.release();}
  }
  @GetMapping(value="/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE) SseEmitter events(HttpServletRequest req,HttpServletResponse res,@PathVariable String owner,@PathVariable String world,@PathVariable String id) {
    var entry=allowed(req,owner,world,id);String actor=entry.actor;
    synchronized(perUser){if(connections.get()>=32 || perUser.computeIfAbsent(actor,k->new AtomicInteger()).get()>=2)throw new ApiError.Unavailable("SSE 上限：全站 32、每人 2");connections.incrementAndGet();perUser.get(actor).incrementAndGet();}
    var emitter=new SseEmitter(30_000L);res.setHeader("Cache-Control","private, no-store");res.setHeader("X-Accel-Buffering","no");
    var closed=new AtomicBoolean();var task=new AtomicReference<ScheduledFuture<?>>();var sequence=new AtomicLong(-1);
    Runnable close=()->{if(closed.compareAndSet(false,true)){var future=task.get();if(future!=null)future.cancel(false);synchronized(perUser){connections.decrementAndGet();if(perUser.get(actor).decrementAndGet()==0)perUser.remove(actor);}}};
    emitter.onCompletion(close);emitter.onTimeout(close);emitter.onError(e->close.run());
    task.set(streamers.scheduleWithFixedDelay(()->{
      if(closed.get())return;
      try{
        // 每次派送重新檢查撤權與 scope；不持 repo／作業簿鎖做網路 IO。
        users.revalidate(req);var w=access.world(req,owner,world,Role.READER);var user=actor.startsWith("session:")?null:accounts.userById(actor).orElseThrow();if(!accounts.roleOn(user,w).atLeast(Role.READER))throw new SecurityException("權限已撤銷");
        var data=entry.snapshot();long next=((Number)data.get("sequence")).longValue();
        if(sequence.getAndSet(next)!=next)emitter.send(SseEmitter.event().id(Long.toString(next)).name("operation").data(data));
        if(entry.result!=null){emitter.complete();close.run();}
      }catch(Exception e){emitter.completeWithError(e);close.run();}
    },0,100,TimeUnit.MILLISECONDS));
    if(closed.get())task.get().cancel(false);return emitter;
  }
  public int connections(){return connections.get();}
  @PreDestroy public void close(){streamers.shutdownNow();}
}
