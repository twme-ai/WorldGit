package org.worldgit.fixture;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.ticks.*;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.java.JavaPlugin;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.apply.ApplyPlan;
import org.worldgit.core.config.IgnoreRules;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.paper.NmsBridge;

/** 真服 #170 驗收專用；不進正式 jar。每版以精確 bundle 編譯。 */
public final class ComponentsFixture extends JavaPlugin {
  private NmsBridge bridge;
  public void onEnable() {
    try {
      var plugin=Bukkit.getPluginManager().getPlugin("WorldGit");
      var getter=plugin.getClass().getDeclaredMethod("bridge");getter.setAccessible(true);
      bridge=(NmsBridge)getter.invoke(plugin);
    }catch(Exception error){throw new IllegalStateException(error);}
    getCommand("wgcomponents").setExecutor((sender,command,label,args)->{
      var world=Objects.requireNonNull(Bukkit.getWorld("world"),"pending fixture world");
      require(world.getKey().toString().equals("minecraft:overworld"),"pending fixture must use overworld");
      world.getChunkAtAsync(8,8,false).whenComplete((loaded,error)->{
        if(error!=null) {sender.sendMessage("WGCOMPONENTS failed "+error);return;}
        if(loaded==null) {sender.sendMessage("WGCOMPONENTS failed fixture chunk missing in "+world.getKey());return;}
        // Folia 的 async FULL 載入 callback 已在 owner；其暫時 ticket 只保留到 callback 返回。
        // 不再轉排下一個 region task，避免 chunk 在取得 tick guard 前被卸載。
          AutoCloseable hold=null;
          try {
            require(Bukkit.isOwnedByCurrentRegion(world,8,8),"async fixture callback must own chunk");
            require(loaded.getWorld()==world && loaded.getX()==8 && loaded.getZ()==8,"async fixture chunk identity");
            var level=((CraftWorld)world).getHandle();var chunk=level.getChunkSource().getChunkNow(8,8);
            require(chunk!=null,"fixture chunk must be FULL on owner in "+world.getKey());
            var blocks=(LevelChunkTicks<?>)chunk.getBlockTicks();var fluids=(LevelChunkTicks<?>)chunk.getFluidTicks();
            var field=LevelChunkTicks.class.getDeclaredField("pendingTicks");field.setAccessible(true);
            // 兩個從真 chunk NBT 載入、尚未 ticking 的容器；getAll/removeIf 此時看不到 pending。
            require(field.get(blocks) instanceof List<?> list && list.size()==1,"block pending count");
            require(field.get(fluids) instanceof List<?> list && list.size()==1,"fluid pending count");
            require(blocks.getAll().count()==0 && fluids.getAll().count()==0,"pending must be invisible to getAll");
            // 不先鎖就直接替換，驗證 applyChunk 自己也先 unpackTicks 再 removeIf。
            bridge.applyChunk(world,new ApplyPlan.ChunkOp(new ChunkPos(8,8),false,new TreeMap<>(),new TreeMap<>(),true,null,false,null),IgnoreRules.none());
            require(blocks.count()==0 && fluids.count()==0 && field.get(blocks)==null && field.get(fluids)==null,"replace clears pending and unpacked ticks");
            // 再建立 vanilla 載入器產生的 pending 容器，確認 lock 也展開、保持剩餘延遲與 priority。
            inject(chunk,"blockTicks",new LevelChunkTicks<>(List.of(new SavedTick<>(net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.tryParse("minecraft:stone")),new BlockPos(132,64,132),600,TickPriority.HIGH))));
            inject(chunk,"fluidTicks",new LevelChunkTicks<>(List.of(new SavedTick<>(net.minecraft.core.registries.BuiltInRegistries.FLUID.get(net.minecraft.resources.ResourceLocation.tryParse("minecraft:water")),new BlockPos(133,64,132),600,TickPriority.LOW))));
            hold=bridge.lockChunkTicks(world,8,8);
            var locked=hold;
            var before=bridge.copy(world,8,8).terrain();checkTicks(before);
            Bukkit.getRegionScheduler().runDelayed(this,world,8,8,task->{
              try {
                checkTicks(bridge.copy(world,8,8).terrain());locked.close();
                bridge.saveChunk(world,8,8);
                sender.sendMessage("WGCOMPONENTS success pending-replace=true unpack=true delay=600 priorities=-1,1");
              }catch(Throwable failure){try{locked.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}sender.sendMessage("WGCOMPONENTS failed "+failure);failure.printStackTrace();}
            },20);
          }catch(Throwable failure){if(hold!=null)try{hold.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}sender.sendMessage("WGCOMPONENTS failed "+failure);failure.printStackTrace();}
      });return true;
    });
  }
  private static void inject(LevelChunk chunk,String name,Object ticks) throws Exception {
    var field=LevelChunk.class.getDeclaredField(name);field.setAccessible(true);field.set(chunk,ticks);
  }
  private static void checkTicks(Nbt.Compound terrain) {
    for(String key:List.of("block_ticks","fluid_ticks")) {
      var values=terrain.list(key).values();require(values.size()==1,key+" count");
      var tick=(Nbt.Compound)values.getFirst();require(tick.integer("t",0)==600,key+" remaining delay");
      require(tick.integer("p",0)==(key.equals("block_ticks")?-1:1),key+" priority");
    }
  }
  private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
