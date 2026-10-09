package org.worldgit.paper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.World;
import org.worldgit.core.model.ChunkPos;

/** Budget owner work and bound deferred scheduler tasks during acquisition, save, and restore. */
final class ChunkLockQueue {
  private record Owner(UUID world,long region) {}
  private static final class Usage {long tick=Long.MIN_VALUE,nanos;int chunks;}
  private record Job(World world,ChunkPos pos,Work work,CompletableFuture<Void> result) {}
  private final Map<Owner,Usage> usage=new ConcurrentHashMap<>();
  private final ConcurrentLinkedQueue<Job> pending=new ConcurrentLinkedQueue<>();
  private final AtomicInteger active=new AtomicInteger();
  private final WorldGitPlugin plugin;
  ChunkLockQueue(WorldGitPlugin plugin) {this.plugin=plugin;}
  interface Work {void run() throws Exception;}
  CompletableFuture<Void> submit(World world,ChunkPos pos,Work work) {
    var result=new CompletableFuture<Void>();pending.add(new Job(world,pos,work,result));pump();return result;
  }
  private synchronized void pump() {
    while(active.get()<24) {
      var job=pending.poll();if(job==null)return;active.incrementAndGet();
      class Step implements Runnable {
        private void complete(Throwable error) {
          if(error==null)job.result.complete(null);else job.result.completeExceptionally(error);
          active.decrementAndGet();pump();
        }
        public void run() {
          try {
            var tick=plugin.bridge().ownerTick(job.world);var owner=new Owner(plugin.platform().folia() ? job.world.getUID() : null,tick.owner());
            var used=usage.computeIfAbsent(owner,k->new Usage());
            if(used.tick!=tick.tick()) {used.tick=tick.tick();used.nanos=0;used.chunks=0;}
            if(used.nanos>=5_000_000 || used.chunks>=24) {plugin.platform().regionDelayed(job.world,job.pos.x(),job.pos.z(),1,this);return;}
            long start=System.nanoTime();try {job.work.run();}finally {used.nanos+=System.nanoTime()-start;used.chunks++;}
            complete(null);
          }catch(Throwable error){complete(error);}
        }
      }
      try {plugin.platform().region(job.world,job.pos.x(),job.pos.z(),new Step());}
      catch(Throwable error){job.result.completeExceptionally(error);active.decrementAndGet();}
    }
  }
}
