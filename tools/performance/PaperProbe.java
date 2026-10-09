package org.worldgit.perf;

import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.concurrent.*;
import org.worldgit.paper.NmsBridge;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.core.config.IgnoreRules;
import org.worldgit.core.config.EntityTagRegistry;
import org.worldgit.core.normalize.ChunkNormalizer;
import org.worldgit.core.normalize.SnapshotCodec;

/** 真伺服器驗收專用，不放入正式插件。A／B 都在自己的 chunk owner 觀察。 */
public final class PaperProbe extends JavaPlugin implements CommandExecutor {
  private World a,b;
  private volatile boolean paused;
  private volatile long aLast=-1,bLast=-1,startNanos,bTicks,aPausedSamples,aChanges,bAdvances,flowAdvances,redstoneAdvances;
  private volatile boolean lastPaused,flowStarted,lastPowered;
  private volatile long farTicks,farFlow,farRedstone,farLast=-1;
  private boolean farStarted,farPowered;
  private volatile String farSource="UNKNOWN",farNeighbor="UNKNOWN";
  private final List<Double> gaps=Collections.synchronizedList(new ArrayList<>());
  @Override public void onEnable() {getCommand("wgperf").setExecutor(this);}
  private static Object level(World world) throws Exception {return world.getClass().getMethod("getHandle").invoke(world);}
  private static long gameTime(World world) throws Exception {var level=level(world);return ((Number)level.getClass().getMethod("getGameTime").invoke(level)).longValue();}
  private static boolean locked(World world) throws Exception {
    var plugin=Bukkit.getPluginManager().getPlugin("WorldGit");
    try {return (boolean)plugin.getClass().getMethod("isChunkEditLocked",World.class,int.class,int.class).invoke(plugin,world,0,0);}
    catch(NoSuchMethodException legacy) {return (boolean)plugin.getClass().getMethod("isEditLocked",World.class).invoke(plugin,world);}
  }
  @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args) {
    if(!(sender instanceof ConsoleCommandSender)) return true;
    try {
      switch(args[0]) {
        case "start" -> {
          a=Bukkit.getWorlds().getFirst();b=Bukkit.getWorlds().stream().filter(w->w.getEnvironment()==World.Environment.NETHER).findFirst().orElseThrow();
          Bukkit.getGlobalRegionScheduler().execute(this,()->{
          a.setGameRule(GameRule.DO_MOB_SPAWNING,false);b.setGameRule(GameRule.DO_MOB_SPAWNING,false);
          a.setChunkForceLoaded(64,0,true);a.setChunkForceLoaded(0,0,true);a.setChunkForceLoaded(2,0,true);b.setChunkForceLoaded(0,0,true);
          var ready=new java.util.concurrent.atomic.AtomicInteger();
          Runnable initialized=()->{if(ready.incrementAndGet()==3)Bukkit.getGlobalRegionScheduler().execute(this,()->sender.sendMessage("WGPERF started"));};
          a.getChunkAtAsync(64,0,true).thenAccept(chunk->Bukkit.getRegionScheduler().run(this,a,64,0,task->{
            for(int x=1031;x<=1035;x++)for(int z=7;z<=9;z++)a.getBlockAt(x,89,z).setType(Material.STONE,false);
            Bukkit.getRegionScheduler().runAtFixedRate(this,a,64,0,repeat->observeFar(),1,1);initialized.run();
          }));
          a.getChunkAtAsync(0,0,true).thenAccept(chunk->Bukkit.getRegionScheduler().run(this,a,0,0,task->{
            Bukkit.getRegionScheduler().runAtFixedRate(this,a,0,0,repeat->observeA(),1,1);initialized.run();
          }));
          b.getChunkAtAsync(0,0,true).thenAccept(chunk->Bukkit.getRegionScheduler().run(this,b,0,0,task->{
            for(int x=7;x<=11;x++) for(int z=7;z<=9;z++) b.getBlockAt(x,89,z).setType(Material.STONE,false);
            Bukkit.getRegionScheduler().runAtFixedRate(this,b,0,0,repeat->observeB(),1,1);
            initialized.run();
          }));
          });
        }
        case "adversary" -> adversary(sender);
        case "entity-scope" -> entityScope(sender);
        case "reset" -> {reset();sender.sendMessage("WGPERF reset");}
        case "stats" -> {
          List<Double> sorted; synchronized(gaps){sorted=new ArrayList<>(gaps);} Collections.sort(sorted);
          double elapsed=(System.nanoTime()-startNanos)/1e9;
          sender.sendMessage(String.format(Locale.ROOT,"WGPERF {\"b_tps\":%.3f,\"b_ticks\":%d,\"locked_samples\":%d,\"a_game_time_advances\":%d,\"b_advances_while_locked\":%d,\"b_flow_while_locked\":%d,\"b_redstone_while_locked\":%d,\"max_gap_ms\":%.3f,\"far_ticks\":%d,\"far_flow\":%d,\"far_redstone\":%d,\"far_source\":\"%s\",\"far_neighbor\":\"%s\"}",
            bTicks/elapsed,bTicks,aPausedSamples,aChanges,bAdvances,flowAdvances,redstoneAdvances,sorted.isEmpty()?0:sorted.getLast(),farTicks,farFlow,farRedstone,farSource,farNeighbor));
        }
        default -> throw new IllegalArgumentException("start/reset/stats");
      }
    } catch(Exception e) {sender.sendMessage("WGPERF failed "+e);}
    return true;
  }
  private void reset() {farTicks=0;farFlow=0;farRedstone=0;farLast=-1;farStarted=false;farPowered=false;aPausedSamples=0;aChanges=0;bTicks=0;bAdvances=0;flowAdvances=0;redstoneAdvances=0;flowStarted=false;lastPowered=false;aLast=-1;bLast=-1;bNanos=0;startNanos=System.nanoTime();gaps.clear();lastPaused=false;}
  private void observeA() {
    try {
      boolean now=locked(a);long time=gameTime(a);
      if(now) {aPausedSamples++;if(paused && aLast!=-1 && time!=aLast)aChanges++;}
      aLast=time;paused=now;
    } catch(Exception e) {getLogger().warning("A probe failed "+e);}
  }
  private long bNanos;
  private void observeB() {
    try {
      long now=System.nanoTime(),time=gameTime(b);
      if(bLast!=-1 && time>bLast) {bTicks+=time-bLast;if(paused && lastPaused)bAdvances+=time-bLast;}
      if(bNanos!=0) gaps.add((now-bNanos)/1e6);bNanos=now;bLast=time;lastPaused=paused;
      if(paused && !flowStarted) {
        for(int x=7;x<=11;x++)for(int z=7;z<=9;z++)b.getBlockAt(x,90,z).setType(Material.AIR,false);
        b.getBlockAt(8,90,8).setType(Material.WATER,true);flowStarted=true;
        for(int x=13;x<=14;x++) b.getBlockAt(x,92,8).setType(Material.AIR,true);
        for(int x=13;x<=14;x++) {
          var state=(org.bukkit.block.data.Directional)Bukkit.createBlockData(Material.OBSERVER);
          state.setFacing(x==13 ? org.bukkit.block.BlockFace.EAST : org.bukkit.block.BlockFace.WEST);
          b.getBlockAt(x,92,8).setBlockData(state,true);
        }
      }
      if(paused && flowStarted && b.getBlockAt(9,90,8).getType()==Material.WATER)flowAdvances++;
      if(flowStarted && b.getBlockAt(13,92,8).getBlockData() instanceof org.bukkit.block.data.Powerable power) {
        boolean nowPowered=power.isPowered();
        if(paused && lastPaused && nowPowered!=lastPowered)redstoneAdvances++;
        lastPowered=nowPowered;
      }
    } catch(Exception e) {getLogger().warning("B probe failed "+e);}
  }
  private void observeFar() {
    try {
      farSource=a.getBlockAt(1032,90,8).getType().name();farNeighbor=a.getBlockAt(1032,90,9).getType().name();
      long time=gameTime(a);if(paused && farLast!=-1 && time>farLast)farTicks+=time-farLast;farLast=time;
      if(paused && !farStarted) {
        for(int x=1031;x<=1035;x++)for(int z=7;z<=9;z++)a.getBlockAt(x,90,z).setType(Material.AIR,false);
        a.getBlockAt(1032,90,8).setType(Material.WATER,true);
        for(int x=1037;x<=1038;x++)a.getBlockAt(x,92,8).setType(Material.AIR,true);
        for(int x=1037;x<=1038;x++) {var data=(org.bukkit.block.data.Directional)Bukkit.createBlockData(Material.OBSERVER);data.setFacing(x==1037 ? org.bukkit.block.BlockFace.EAST : org.bukkit.block.BlockFace.WEST);a.getBlockAt(x,92,8).setBlockData(data,true);}
        farStarted=true;
      }
      if(paused && farStarted && a.getBlockAt(1032,90,9).getType()==Material.WATER)farFlow++;
      if(farStarted && a.getBlockAt(1037,92,8).getBlockData() instanceof org.bukkit.block.data.Powerable power) {boolean value=power.isPowered();if(paused && value!=farPowered)farRedstone++;farPowered=value;}
    } catch(Exception e) {getLogger().warning("Far probe failed "+e);}
  }
  private static Object invoke(Object object,String name,Object...args) throws Exception {
    for(var type=object.getClass();type!=null;type=type.getSuperclass())for(var method:type.getDeclaredMethods())
      if(method.getName().equals(name) && method.getParameterCount()==args.length) {
        boolean matches=true;var types=method.getParameterTypes();for(int i=0;i<args.length;i++)if(args[i]!=null && !types[i].isInstance(args[i]))matches=false;
        if(matches) {method.setAccessible(true);return method.invoke(object,args);}
      }
    throw new NoSuchMethodException(name);
  }
  private String digest(NmsBridge bridge,EntityTagRegistry semantics) throws Exception {
    var hash=java.security.MessageDigest.getInstance("SHA-256");var normalizer=new ChunkNormalizer(IgnoreRules.none(),semantics);
    var raw=bridge.copy(a,0,0);if(raw==null)throw new IllegalStateException("adversary chunk unloaded");
    var files=SnapshotCodec.chunkFiles(normalizer.normalize(new ChunkPos(0,0),raw.terrain(),raw.entities()));
    for(var entry:new TreeMap<>(files).entrySet()) {hash.update(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));hash.update(entry.getValue());}
    return HexFormat.of().formatHex(hash.digest());
  }
  private CompletableFuture<String> digestOwner(NmsBridge bridge,EntityTagRegistry semantics) {
    var result=new CompletableFuture<String>();Bukkit.getRegionScheduler().run(this,a,0,0,t->{try {result.complete(digest(bridge,semantics));}catch(Throwable e){result.completeExceptionally(e);}});return result;
  }
  private CompletableFuture<org.bukkit.entity.Cow> scopeCow(int x) {
    var result=new CompletableFuture<org.bukkit.entity.Cow>();Bukkit.getRegionScheduler().run(this,a,x>>4,0,t->{
      try {var cow=a.spawn(new Location(a,x+.5,96,5.5),org.bukkit.entity.Cow.class);cow.setGravity(false);cow.setAI(false);result.complete(cow);}catch(Throwable e){result.completeExceptionally(e);}
    });return result;
  }
  private void entityScope(CommandSender sender) {
    Bukkit.getAsyncScheduler().runNow(this,task->{
      try {
        var inside=scopeCow(8).get(30,TimeUnit.SECONDS);var outside=scopeCow(1032).get(30,TimeUnit.SECONDS);
        try {
          var wg=Bukkit.getPluginManager().getPlugin("WorldGit");var mapping=invoke(wg,"mapping");
          var queueType=Class.forName("org.worldgit.paper.ApplyQueue",true,wg.getClass().getClassLoader());var qc=queueType.getDeclaredConstructor(wg.getClass());qc.setAccessible(true);var queue=qc.newInstance(wg);
          var type=Class.forName("org.worldgit.paper.PaperOperations",true,wg.getClass().getClassLoader());var constructor=type.getDeclaredConstructor(wg.getClass(),mapping.getClass(),queueType);constructor.setAccessible(true);
          try(var operations=(AutoCloseable)constructor.newInstance(wg,mapping,queue)) {
            var dim=org.worldgit.core.model.DimensionId.OVERWORLD;
            var chunks=new ArrayList<org.worldgit.core.apply.ApplyPlan.ChunkOp>();
            for(int x=0;x<=1;x++)chunks.add(new org.worldgit.core.apply.ApplyPlan.ChunkOp(new ChunkPos(x,0),false,new TreeMap<>(),new TreeMap<>(),false,null,true,null));
            var plan=new org.worldgit.core.apply.ApplyPlan(dim,null,null,Bukkit.getUnsafe().getDataVersion(),org.worldgit.core.apply.Scope.all(),chunks,List.of(new org.worldgit.core.apply.ApplyPlan.EntityOp(inside.getUniqueId(),new ChunkPos(0,0),null)),Map.of(),List.of());
            try(var lock=(AutoCloseable)invoke(operations,"guardAffectedChunks",List.of(plan))) {
              if((boolean)wg.getClass().getMethod("isChunkEditLocked",World.class,int.class,int.class).invoke(wg,a,64,0))throw new AssertionError("unrelated entity chunk locked");
              var insideAge=new CompletableFuture<Integer>();var outsideAge=new CompletableFuture<Integer>();
              Bukkit.getRegionScheduler().run(this,a,0,0,t->insideAge.complete(inside.getTicksLived()));Bukkit.getRegionScheduler().run(this,a,64,0,t->outsideAge.complete(outside.getTicksLived()));
              int beforeInside=insideAge.get(30,TimeUnit.SECONDS),beforeOutside=outsideAge.get(30,TimeUnit.SECONDS);
              var held=new CompletableFuture<Boolean>();Bukkit.getRegionScheduler().runDelayed(this,a,0,0,t->held.complete(inside.getTicksLived()==beforeInside),8);
              if(!held.get(30,TimeUnit.SECONDS))throw new AssertionError("affected entity ticked");
              var advanced=new CompletableFuture<Boolean>();Bukkit.getRegionScheduler().run(this,a,64,0,t->advanced.complete(outside.getTicksLived()>beforeOutside));
              if(!advanced.get(30,TimeUnit.SECONDS))throw new AssertionError("unrelated entity did not tick");
            }
          }
        }finally {
          var removedInside=new CompletableFuture<Void>();var removedOutside=new CompletableFuture<Void>();
          Bukkit.getRegionScheduler().run(this,a,0,0,t->{inside.remove();removedInside.complete(null);});Bukkit.getRegionScheduler().run(this,a,64,0,t->{outside.remove();removedOutside.complete(null);});
          CompletableFuture.allOf(removedInside,removedOutside).get(30,TimeUnit.SECONDS);
        }
        Bukkit.getGlobalRegionScheduler().execute(this,()->sender.sendMessage("WGPERF entity scope passed affected_stable=true unrelated_entity_ticking=true"));
      }catch(Throwable error){Bukkit.getGlobalRegionScheduler().execute(this,()->sender.sendMessage("WGPERF entity scope failed "+error));}
    });
  }
  private void adversary(CommandSender sender) {
    var ready=new CompletableFuture<Void>();
    Bukkit.getRegionScheduler().run(this,a,0,0,task->{try {
      for(int x=12;x<=15;x++)for(int z=2;z<=14;z++)a.getBlockAt(x,90,z).setType(Material.STONE,false);
      a.getBlockAt(15,91,2).setType(Material.WATER,true);a.getBlockAt(15,91,12).setType(Material.LAVA,true);
      for(int x=13;x<=14;x++){var data=(org.bukkit.block.data.Directional)Bukkit.createBlockData(Material.OBSERVER);data.setFacing(x==13 ? org.bukkit.block.BlockFace.EAST : org.bukkit.block.BlockFace.WEST);a.getBlockAt(x,94,8).setBlockData(data,true);}
      var piston=(org.bukkit.block.data.Directional)Bukkit.createBlockData(Material.PISTON);piston.setFacing(org.bukkit.block.BlockFace.EAST);a.getBlockAt(15,94,8).setBlockData(piston,true);
      var sand=a.spawnFallingBlock(new Location(a,14.5,99,6.5),Bukkit.createBlockData(Material.SAND));sand.setVelocity(new org.bukkit.util.Vector(.1,-.3,0));
      var cow=a.spawn(new Location(a,14.5,92,5.5),org.bukkit.entity.Cow.class);cow.setVelocity(new org.bukkit.util.Vector(.1,0,0));
      a.getBlockAt(14,91,10).setType(Material.HOPPER,false);ready.complete(null);
    }catch(Throwable e){ready.completeExceptionally(e);}});
    Bukkit.getAsyncScheduler().runNow(this,task->{
      try {
        ready.get(30,TimeUnit.SECONDS);var wg=Bukkit.getPluginManager().getPlugin("WorldGit");var bridge=(NmsBridge)invoke(wg,"bridge");
        var mapping=invoke(wg,"mapping");var layout=(org.worldgit.core.anvil.WorldLayout)invoke(mapping,"layout");
        var dimension=new org.worldgit.core.model.DimensionId("minecraft:overworld");var state=invoke(wg,"state",dimension,a);
        var type=Class.forName("org.worldgit.paper.PaperLiveWorld",true,wg.getClass().getClassLoader());var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
        var source=constructor.newInstance(wg,state,a,layout,false);
        var lock=(AutoCloseable)invoke(source,"lockEdits",Set.of(new ChunkPos(0,0)),"adversary");
        var semantics=EntityTagRegistry.load(layout.world(),Bukkit.getUnsafe().getDataVersion());
        String before=digestOwner(bridge,semantics).get(30,TimeUnit.SECONDS);
        try {
          var incoming=new CompletableFuture<org.bukkit.entity.FallingBlock>();
          Bukkit.getRegionScheduler().run(this,a,2,0,t->{try {var entity=a.spawnFallingBlock(new Location(a,33.5,99,7.5),Bukkit.createBlockData(Material.SAND));entity.setVelocity(new org.bukkit.util.Vector(-1,0,0));incoming.complete(entity);}catch(Throwable e){incoming.completeExceptionally(e);}});
          var outside=incoming.get(30,TimeUnit.SECONDS);
          var after=new CompletableFuture<String>();Bukkit.getRegionScheduler().runDelayed(this,a,0,0,t->{try {after.complete(digest(bridge,semantics));}catch(Throwable e){after.completeExceptionally(e);}},8);
          if(!before.equals(after.get(30,TimeUnit.SECONDS)))throw new AssertionError("locked water/lava/piston/sand/entity/BE changed");
          var ingress=new CompletableFuture<Boolean>();Bukkit.getRegionScheduler().run(this,a,2,0,t->ingress.complete(outside.isValid() && outside.getLocation().getX()>=32 && outside.getLocation().getY()<99));
          if(!ingress.get(30,TimeUnit.SECONDS))throw new AssertionError("nonliving entity entered locked boundary");
        }finally {lock.close();((AutoCloseable)source).close();}
        var resumed=new CompletableFuture<Boolean>();Bukkit.getRegionScheduler().runDelayed(this,a,0,0,t->{try {resumed.complete(!before.equals(digest(bridge,semantics)) && a.getBlockAt(16,91,2).getType()==Material.WATER && a.getBlockAt(16,91,12).getType()==Material.LAVA);}catch(Throwable e){resumed.completeExceptionally(e);}},40);
        if(!resumed.get(30,TimeUnit.SECONDS))throw new AssertionError("scheduled/entity ticks did not resume");
        Bukkit.getGlobalRegionScheduler().execute(this,()->sender.sendMessage("WGPERF adversary passed held_stable=true resumed=true water=true lava=true piston=true falling_sand=true moving_entity=true block_entity=true"));
      }catch(Throwable error){Bukkit.getGlobalRegionScheduler().execute(this,()->sender.sendMessage("WGPERF adversary failed "+error));}
    });
  }
}
