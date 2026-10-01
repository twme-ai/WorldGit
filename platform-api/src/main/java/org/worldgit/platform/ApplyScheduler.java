package org.worldgit.platform;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.worldgit.core.apply.*;
import org.worldgit.core.model.ChunkPos;

/**
 * 保守的共用 coordinator：一次一個有界批次，section/實體移除/生成/meta barrier。
 * 平台可以替換為多 lane；仍須依實際 region/tick 共享 8 section/5ms、全域 16–24 ticket 上限。
 * repo executor 呼叫；owner scheduler 由 LiveWorld 實作，這裡不假設主執行緒或 region 格網。
 */
public final class ApplyScheduler {
  private ApplyScheduler() {}
  public record Result(ApplyProgress.Phase state,int completedBatches,int totalBatches,String error) {
    public boolean complete() { return state==ApplyProgress.Phase.COMPLETE; }
  }
  public static final class Handle {
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final CompletableFuture<Result> result=new CompletableFuture<>();
    public void cancel() { cancelled.set(true); }
    public CompletionStage<Result> result() { return result.minimalCompletionStage(); }
  }
  /** observer 須先持久化 APPLYING/PARTIAL，COMPLETE 之後呼叫端才可移動 HEAD。observer 失敗即停止。 */
  public static Handle start(LiveWorld world,ApplyPlan plan,ApplyBudget budget,
      Executor coordinator,Consumer<ApplyProgress> observer) {
    var handle=new Handle();
    try { coordinator.execute(()->new Runner(world,plan,budget,coordinator,observer,handle).begin()); }
    catch (RejectedExecutionException ex) { handle.result.complete(new Result(ApplyProgress.Phase.FAILED,0,0,ex.toString())); }
    return handle;
  }
  private static final class Runner {
    final LiveWorld world; final ApplyPlan plan; final ApplyBudget budget; final Executor executor;
    final Consumer<ApplyProgress> observer; final Handle handle; final UUID operation=UUID.randomUUID();
    final List<ApplyPlan> batches; final Set<ChunkPos> chunks=new HashSet<>();
    AutoCloseable lock; int completed,sections; boolean attempted,finishing;
    Runner(LiveWorld w,ApplyPlan p,ApplyBudget b,Executor e,Consumer<ApplyProgress> o,Handle h) {
      world=w;plan=p;budget=b;executor=e;observer=o;handle=h;batches=p.batches(b.sectionsPerTick());
      chunks.addAll(p.chunks().keySet());
      for(var entity:p.entities()) { if(entity.hint()!=null) chunks.add(entity.hint()); if(entity.target()!=null) chunks.add(entity.targetChunk()); }
    }
    void emit(ApplyProgress.Phase phase) {
      var progress=new ApplyProgress(operation,phase,completed,batches.size(),sections,plan.stats().sections(),handle.cancelled.get());
      observer.accept(progress); world.applyProgress(progress);
    }
    void begin() {
      try {
        DataVersions.requireSame(plan.dataVersion(),world.dataVersion()); emit(ApplyProgress.Phase.LOCKING);
        if (!plan.dimension().equals(world.dimension())) throw new IllegalArgumentException("apply 維度不符");
        lock=world.lockEdits(chunks,"WorldGit apply "+operation);
        if(handle.cancelled.get()) { finish(false,null); return; }
        world.flush().whenCompleteAsync((v,ex)-> { if(ex!=null) finish(false,ex); else protect(); },executor);
      } catch(Throwable ex) { finish(false,ex); }
    }
    void protect() {
      try { world.protectPlayers(PlayerProtection.operation(chunks,operation,true)).whenCompleteAsync((v,ex)-> {
        if(ex!=null) finish(false,ex); else next();
      },executor); } catch(Throwable ex) { finish(false,ex); }
    }
    void next() {
      if(handle.cancelled.get()) { finish(false,null); return; }
      if(completed==batches.size()) { complete(); return; }
      try {
        emit(ApplyProgress.Phase.APPLYING);
        var batch=batches.get(completed);
        world.nextApplyTick(batch).whenCompleteAsync((v,ex)-> {
          if(ex!=null) { finish(false,ex); return; }
          if(handle.cancelled.get()) { finish(false,null); return; }
          attempted=true;
          try { world.apply(batch,budget).whenCompleteAsync((ignored,error)-> {
            if(error!=null) { finish(false,error); return; }
            completed++; sections+=batch.stats().sections(); next();
          },executor); } catch(Throwable error) { finish(false,error); }
        },executor);
      } catch(Throwable ex) { finish(false,ex); }
    }
    void complete() {
      finish(true,null);
    }
    void finish(boolean success,Throwable error) {
      if(finishing) return; finishing=true;
      // 已派出的 adapter future 必須自行釋放 ticket；等待完成後才會抵達這裡。
      CompletionStage<Throwable> cleanup=CompletableFuture.completedFuture(error);
      if (attempted) {
        Throwable failure=error;
        try { emit(ApplyProgress.Phase.LIGHTING); } catch(Throwable ex) { failure=merge(failure,ex); }
        final Throwable initialFailure=failure;
        CompletionStage<Void> lighting;
        try {
          lighting=world.finishApply(chunks);
        } catch(Throwable ex) { lighting=CompletableFuture.failedFuture(ex); }
        cleanup=lighting.handleAsync((v,ex)->merge(initialFailure,ex),executor).thenComposeAsync(prior->{
          Throwable savingFailure=prior;
          try { emit(ApplyProgress.Phase.SAVING); } catch(Throwable ex) { savingFailure=merge(savingFailure,ex); }
          final Throwable beforeSaving=savingFailure;
          try { return world.flush().handle((v,ex)->merge(beforeSaving,ex)); }
          catch(Throwable ex) { return CompletableFuture.completedFuture(merge(beforeSaving,ex)); }
        },executor);
      }
      final boolean requestedSuccess=success;
      cleanup.thenComposeAsync(cleanupError->{
        CompletionStage<Void> protection;
        try { protection=lock==null ? CompletableFuture.completedFuture(null)
            : world.protectPlayers(PlayerProtection.operation(chunks,operation,false)); }
        catch(Throwable ex) { protection=CompletableFuture.failedFuture(ex); }
        return protection.handle((v,ex)->merge(cleanupError,ex));
      },executor).whenCompleteAsync((failure,unexpected)->{
        if(unexpected!=null) failure=unexpected;
        finishResult(requestedSuccess && failure==null && !handle.cancelled.get(),failure);
      },executor);
    }
    static Throwable merge(Throwable first,Throwable next) {
      if(first==null) return next;
      if(next!=null && first!=next) first.addSuppressed(next);
      return first;
    }
    void finishResult(boolean success,Throwable error) {
      try { if(lock!=null) lock.close(); } catch(Throwable ex) { success=false; error=ex; }
      var state=success ? ApplyProgress.Phase.COMPLETE : attempted ? ApplyProgress.Phase.PARTIAL
          : error==null ? ApplyProgress.Phase.CANCELLED : ApplyProgress.Phase.FAILED;
      try { emit(state); } catch(Throwable ex) { error=ex; state=attempted ? ApplyProgress.Phase.PARTIAL : ApplyProgress.Phase.FAILED; }
      handle.result.complete(new Result(state,completed,batches.size(),error==null ? null : error.toString()));
    }
  }
}
