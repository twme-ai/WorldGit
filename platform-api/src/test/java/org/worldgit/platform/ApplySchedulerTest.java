package org.worldgit.platform;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.worldgit.core.apply.*;
import org.worldgit.core.capture.*;
import org.worldgit.core.config.*;
import org.worldgit.core.model.*;

class ApplySchedulerTest {
  static final class World implements LiveWorld {
    final List<String> calls=new CopyOnWriteArrayList<>();
    final List<PlayerProtection> protections=new CopyOnWriteArrayList<>();
    CompletableFuture<Void> pending; boolean fail; boolean locked;
    final CountDownLatch applying=new CountDownLatch(1);
    public DimensionId dimension(){return DimensionId.OVERWORLD;}
    public int dataVersion(){return 4903;}
    public Set<ChunkPos> knownChunks(){return Set.of();}
    public Set<ChunkPos> dirtyChunks(){return Set.of();}
    public Set<ChunkPos> entityChunks(){return Set.of();}
    public CompletionStage<Optional<ChunkSnapshot>> snapshot(ChunkPos p,IgnoreRules r){return CompletableFuture.completedFuture(Optional.empty());}
    public CompletionStage<Void> apply(ChunkPatch p){return CompletableFuture.failedFuture(new UnsupportedOperationException());}
    public AutoCloseable lockEdits(Collection<ChunkPos> c,String reason){locked=true;calls.add("lock");return ()->{locked=false;calls.add("unlock");};}
    public CompletionStage<Void> flush(){calls.add("flush");return CompletableFuture.completedFuture(null);}
    public void notifyPlayers(String message){}
    public CompletionStage<Void> nextApplyTick(ApplyPlan batch){calls.add("tick");return CompletableFuture.completedFuture(null);}
    public CompletionStage<Void> protectPlayers(PlayerProtection p){assertEquals(10,p.duration().toSeconds());assertEquals(3,p.causes().size());protections.add(p);calls.add("protect");return CompletableFuture.completedFuture(null);}
    public CompletionStage<Void> finishApply(Collection<ChunkPos> c){calls.add("light/poi/packets");return CompletableFuture.completedFuture(null);}
    public CompletionStage<Void> apply(ApplyPlan batch,ApplyBudget b){
      assertTrue(locked);assertTrue(batch.stats().sections()<=b.sectionsPerTick());
      calls.add(batch.entities().isEmpty() ? "blocks" : batch.entities().getFirst().target()==null ? "remove" : "spawn");
      applying.countDown();
      if(fail) return CompletableFuture.failedFuture(new IllegalStateException("injected"));
      return pending==null ? CompletableFuture.completedFuture(null) : pending;
    }
  }
  private ApplyPlan plan(){
    var sections=new TreeMap<Integer,ApplyPlan.SectionOp>();for(int i=0;i<17;i++)sections.put(i,new ApplyPlan.SectionOp(i,null,null));
    var uuid=UUID.randomUUID();var entity=new org.worldgit.core.anvil.Nbt.Compound().with("id","minecraft:armor_stand").with("UUID",new int[]{0,0,0,1}).with("Pos",new org.worldgit.core.anvil.Nbt.ListTag(6,List.of(17.0,70.0,0.0)));
    return new ApplyPlan(DimensionId.OVERWORLD,null,null,4903,Scope.all(),List.of(new ApplyPlan.ChunkOp(new ChunkPos(0,0),false,sections,new TreeMap<>(),false,null,false,null)),List.of(new ApplyPlan.EntityOp(uuid,new ChunkPos(0,0),new EntitySnapshot(uuid,entity))),Map.of(),List.of());
  }
  @Test void strictBatchesEntityBarrierAndProtection() throws Exception {
    try(var executor=Executors.newSingleThreadExecutor()) {
      var world=new World();var progress=new CopyOnWriteArrayList<ApplyProgress>();
      var handle=ApplyScheduler.start(world,plan(),ApplyBudget.DEFAULT,executor,progress::add);
      var result=handle.result().toCompletableFuture().get(5,TimeUnit.SECONDS);
      assertTrue(result.complete());assertFalse(world.locked);
      assertTrue(world.calls.indexOf("remove")>world.calls.lastIndexOf("blocks"));
      assertTrue(world.calls.indexOf("spawn")>world.calls.indexOf("remove"));
      assertEquals("unlock",world.calls.getLast());
      assertEquals(ApplyProgress.Phase.COMPLETE,progress.getLast().phase());
      assertTrue(world.protections.getFirst().active());assertFalse(world.protections.getLast().active());
      assertEquals(world.protections.getFirst().operation(),world.protections.getLast().operation());
    }
  }
  @Test void cancellationDrainsInFlightBeforeUnlockAndReportsPartial() throws Exception {
    try(var executor=Executors.newSingleThreadExecutor()) {
      var world=new World();world.pending=new CompletableFuture<>();
      var handle=ApplyScheduler.start(world,plan(),ApplyBudget.DEFAULT,executor,p->{});
      assertTrue(world.applying.await(5,TimeUnit.SECONDS));handle.cancel();
      executor.submit(()->{}).get();
      assertTrue(world.locked);assertFalse(handle.result().toCompletableFuture().isDone());
      world.pending.complete(null);
      assertEquals(ApplyProgress.Phase.PARTIAL,handle.result().toCompletableFuture().get(5,TimeUnit.SECONDS).state());
      assertFalse(world.locked);
      assertTrue(world.calls.indexOf("light/poi/packets")>world.calls.indexOf("blocks"));
      assertTrue(world.calls.lastIndexOf("flush")>world.calls.indexOf("light/poi/packets"));
    }
  }
  @Test void uncertainWriteFailureIsPartial() throws Exception {
    try(var executor=Executors.newSingleThreadExecutor()) {
      var world=new World();world.fail=true;
      assertEquals(ApplyProgress.Phase.PARTIAL,ApplyScheduler.start(world,plan(),ApplyBudget.DEFAULT,executor,p->{}).result().toCompletableFuture().get(5,TimeUnit.SECONDS).state());
      assertFalse(world.locked);
    }
  }
  @Test void observerFailureTerminatesAndUnlocks() throws Exception {
    try(var executor=Executors.newSingleThreadExecutor()) {
      var world=new World();
      var result=ApplyScheduler.start(world,plan(),ApplyBudget.DEFAULT,executor,p->{
        if(p.completedBatches()>0) throw new IllegalStateException("journal unavailable");
      }).result().toCompletableFuture().get(5,TimeUnit.SECONDS);
      assertEquals(ApplyProgress.Phase.PARTIAL,result.state());assertFalse(world.locked);
      assertTrue(world.calls.indexOf("light/poi/packets")>world.calls.indexOf("blocks"));
      assertTrue(world.calls.lastIndexOf("flush")>world.calls.indexOf("light/poi/packets"));
      assertFalse(world.protections.getLast().active());
    }
  }
}
