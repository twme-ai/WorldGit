package org.worldgit.paper;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.worldgit.core.apply.ApplyPlan;
import org.worldgit.core.config.IgnoreRules;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.platform.ApplyBudget;

/** 全世界組共用 ticket 上限；owner/tick 預算依真正 Folia region 共享。 */
final class ApplyQueue {
  final WorldGitPlugin plugin;
  final AtomicBoolean cancelled=new AtomicBoolean();
  final AtomicInteger tickets=new AtomicInteger(), peak=new AtomicInteger(), sections=new AtomicInteger();
  private record Owner(UUID world,long region) {}
  final Map<Owner,Usage> usage=new ConcurrentHashMap<>();
  volatile boolean stopping;
  static final class Usage { long tick=Long.MIN_VALUE,nanos; int sections; }
  ApplyQueue(WorldGitPlugin plugin) { this.plugin=plugin; }
  interface Work { CompletionStage<Void> run(World world,Chunk chunk) throws Exception; }
  record Task(World world,ChunkPos pos,Work work) {}

  CompletionStage<Void> run(List<Task> tasks, ApplyBudget budget, boolean cleanup) {
    var result=new CompletableFuture<Void>();
    if(tasks.isEmpty()) { result.complete(null); return result; }
    new Object() {
      int next,active,done; Throwable failure;
      synchronized void pump() {
        if(stopping) { result.completeExceptionally(new IOException("插件關閉中")); return; }
        while(next<tasks.size() && active<budget.maxLoadedChunks() && failure==null && (cleanup||!cancelled.get())) {
          var task=tasks.get(next++); active++;
          load(task,budget,cleanup).whenComplete((v,e)->end(e));
        }
        if(active==0 && (next==tasks.size() || failure!=null || (!cleanup&&cancelled.get()))) {
          if(failure!=null) result.completeExceptionally(failure); else result.complete(null);
        }
      }
      synchronized void end(Throwable error) { active--; done++; if(error!=null) { if(failure==null) failure=error; else if(failure!=error) failure.addSuppressed(error); } pump(); }
    }.pump();
    return result;
  }
  private CompletionStage<Void> load(Task task,ApplyBudget budget,boolean cleanup) {
    var result=new CompletableFuture<Void>();
    task.world().getChunkAtAsync(task.pos().x(),task.pos().z(),true).whenComplete((chunk,error)->{
      if(error!=null) { result.completeExceptionally(error); return; }
      try { plugin.platform().region(task.world(),task.pos().x(),task.pos().z(),()->{
        if(stopping) { result.completeExceptionally(new IOException("插件關閉中")); return; }
        boolean added=false;
        try {
          added=task.world().addPluginChunkTicket(task.pos().x(),task.pos().z(),plugin);
          // 同插件已有 ticket（例如 debug）不可釋放它。
          if(added) peak.accumulateAndGet(tickets.incrementAndGet(),Math::max);
          ready(task,chunk,0,result,added,cleanup);
        } catch(Throwable e) { release(task,result,added,e); }
      }); } catch(Throwable e) { result.completeExceptionally(e); }
    });
    return result;
  }
  private void ready(Task task,Chunk chunk,int tries,CompletableFuture<Void> result,boolean ticket,boolean cleanup) {
    if(stopping || (!cleanup && cancelled.get())) { release(task,result,ticket,null); return; }
    if(!chunk.isEntitiesLoaded()) {
      if(tries>=400) { release(task,result,ticket,new IOException("entity chunk 載入逾時："+task.pos())); return; }
      try { plugin.platform().regionDelayed(task.world(),task.pos().x(),task.pos().z(),1,()->ready(task,chunk,tries+1,result,ticket,cleanup)); }
      catch(Throwable e) { release(task,result,ticket,e); } return;
    }
    try { task.work().run(task.world(),chunk).whenComplete((v,e)->{
      try { plugin.platform().region(task.world(),task.pos().x(),task.pos().z(),()->release(task,result,ticket,e)); }
      catch(Throwable scheduling) { result.completeExceptionally(scheduling); }
    }); } catch(Throwable e) { release(task,result,ticket,e); }
  }
  private void release(Task task,CompletableFuture<Void> result,boolean ticket,Throwable failure) {
    try { if(ticket) { task.world().removePluginChunkTicket(task.pos().x(),task.pos().z(),plugin); tickets.decrementAndGet(); } }
    catch(Throwable e) { if(failure==null) failure=e; else failure.addSuppressed(e); }
    if(failure==null) result.complete(null); else result.completeExceptionally(failure);
  }
  private static Throwable merge(Throwable a,Throwable b) { if(a==null) return b; if(b!=null && a!=b) a.addSuppressed(b); return a; }
  CompletionStage<Void> apply(World world,ApplyPlan.ChunkOp op,IgnoreRules rules,ApplyBudget budget) {
    var result=new CompletableFuture<Void>();
    var sectionList=new ArrayList<>(op.sections().values());
    new Object() {
      int next;
      void step() {
        try {
          var ctx=plugin.bridge().ownerTick(world);
          var owner=new Owner(plugin.platform().folia() ? world.getUID() : null,ctx.owner());
          var used=usage.computeIfAbsent(owner,k->new Usage());
          if(used.tick!=ctx.tick()) { used.tick=ctx.tick(); used.nanos=0; used.sections=0; }
          while(next<sectionList.size() && !cancelled.get() && !stopping) {
            if(used.sections>=budget.sectionsPerTick() || used.nanos>=budget.nanosPerTick()) {
              plugin.platform().regionDelayed(world,op.pos().x(),op.pos().z(),1,this::step); return;
            }
            var section=sectionList.get(next++); long start=System.nanoTime();
            plugin.bridge().applyChunk(world,new ApplyPlan.ChunkOp(op.pos(),false,new TreeMap<>(Map.of(section.y(),section)),new TreeMap<>(),false,null,false,null),rules);
            used.nanos+=System.nanoTime()-start; used.sections++; sections.incrementAndGet();
          }
          if(!cancelled.get() && !stopping) plugin.bridge().applyChunk(world,new ApplyPlan.ChunkOp(op.pos(),op.delete(),new TreeMap<>(),op.biomes(),op.setTicks(),op.ticks(),op.setStructures(),op.structures()),rules);
          finish(null);
        } catch(Throwable e) { finish(e); }
      }
      void finish(Throwable failure) {
        try {
          // 失敗／取消仍等待已提交的光照，並刷新玩家畫面；存檔在全組 barrier 做一次。
          plugin.bridge().finishChunk(world,op.pos().x(),op.pos().z()).whenComplete((v,lighting)->{
            Throwable error=merge(failure,lighting);
            try { plugin.platform().region(world,op.pos().x(),op.pos().z(),()->{
              Throwable finalError=error;
              try { world.refreshChunk(op.pos().x(),op.pos().z()); }
              catch(Throwable ex) { finalError=merge(finalError,ex); }
              if(finalError==null) result.complete(null); else result.completeExceptionally(finalError);
            }); } catch(Throwable ex) { result.completeExceptionally(merge(error,ex)); }
          });
        } catch(Throwable ex) { result.completeExceptionally(merge(failure,ex)); }
      }
    }.step();
    return result;
  }
}
