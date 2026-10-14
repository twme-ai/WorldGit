package org.worldgit.fixture;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Chicken;
import org.bukkit.plugin.java.JavaPlugin;

/** 僅供跨維度乘客驗收；Folia 沒有 data 指令，使用官方 API 並在 chunk owner 設定。 */
public final class EntitiesFixturePlugin extends JavaPlugin {
  public void onEnable() {
    getCommand("wgentityfixture").setExecutor((sender,command,label,args)->{
      if(args.length>0 && args[0].equals("census")) {
        try {
          var world=Bukkit.getWorlds().stream().filter(w->w.getEnvironment()==World.Environment.THE_END).findFirst().orElseThrow();
          var plugin=Bukkit.getPluginManager().getPlugin("WorldGit");
          var getter=plugin.getClass().getDeclaredMethod("bridge");getter.setAccessible(true);
          var bridge=(org.worldgit.paper.NmsBridge)getter.invoke(plugin);
          var uid=UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
          final boolean[] loaded={false};
          bridge.census(world,(x,z,unsaved,entities)->{if(x==96 && z==96)loaded[0]=true;});
          sender.sendMessage("WGENTITYCENSUS loaded="+loaded[0]+" known="+bridge.knownEntityIds(world).contains(uid)+" bukkitLoaded="+world.isChunkLoaded(96,96));
        }catch(Throwable failure){sender.sendMessage("WGENTITYCENSUS failed "+failure);failure.printStackTrace();}
        return true;
      }
      if(args.length>0 && args[0].equals("clear-disk")) {
        var world=Bukkit.getWorlds().stream().filter(w->w.getEnvironment()==World.Environment.THE_END).findFirst().orElseThrow();
        world.getChunkAtAsync(96,96,false).whenComplete((chunk,error)->{
          if(error!=null || chunk==null){sender.sendMessage("WGENTITYDISK failed "+error);return;}
          Bukkit.getRegionScheduler().execute(this,world,96,96,()->{
            try {
              if(!Bukkit.isOwnedByCurrentRegion(world,96,96))throw new AssertionError("disk clear requires owner");
              if(!chunk.addPluginChunkTicket(this))throw new AssertionError("disk fixture ticket missing");
              // FULL terrain can arrive before the separate entity storage.
              // Keep this chunk alive while its owner waits for that IO to finish.
              final int[] ticks={0};
              Bukkit.getRegionScheduler().runAtFixedRate(this,world,96,96,task->{
                try {
                  if(!Bukkit.isOwnedByCurrentRegion(world,96,96))throw new AssertionError("disk clear requires owner");
                  // Folia 26.1.2 implements isEntitiesLoaded via terrain presence.
                  // Its entity slices and valid Bukkit entities can arrive later.
                  var uid=UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
                  var matches=java.util.Arrays.stream(chunk.getEntities())
                      .filter(entity->entity.getUniqueId().equals(uid)).toList();
                  if(matches.isEmpty()) {
                    if(++ticks[0]>=600)throw new AssertionError("exactly one stored UUID must load before removal: 0");
                    return;
                  }
                  if(matches.size()!=1)throw new AssertionError("exactly one stored UUID must be removed: "+matches.size());
                  matches.get(0).remove();
                  // The minimal disk collision is not necessarily in the captured
                  // entity tree. Make the subsequent independent End commit real.
                  var marker=chunk.getBlock(8,64,8);
                  if(marker.getType()!=org.bukkit.Material.AIR)throw new AssertionError("disk fixture marker must start as air");
                  marker.setType(org.bukkit.Material.STONE);
                  sender.sendMessage("WGENTITYDISK success removed=1 marker=stone entityLoaded=true waitedTicks="+ticks[0]);
                  task.cancel();chunk.removePluginChunkTicket(this);
                }catch(Throwable failure){
                  task.cancel();chunk.removePluginChunkTicket(this);
                  sender.sendMessage("WGENTITYDISK failed "+failure);failure.printStackTrace();
                }
              },1,1);
            }catch(Throwable failure){sender.sendMessage("WGENTITYDISK failed "+failure);failure.printStackTrace();}
          });
        });return true;
      }
      var world=Bukkit.getWorlds().stream().filter(w->w.getEnvironment()==World.Environment.NORMAL).findFirst().orElseThrow();
      world.getChunkAtAsync(12,-12,false).whenComplete((chunk,error)->{
        if(error!=null) {sender.sendMessage("WGENTITYFIXTURE failed "+error);return;}
        Bukkit.getRegionScheduler().execute(this,world,12,-12,()->{
          try {
            var uuid=UUID.fromString("cccccccc-dddd-eeee-ffff-111111111111");
            Chicken found=null;
            for(var entity:chunk.getEntities()) if(entity.getUniqueId().equals(uuid) && entity instanceof Chicken chicken) found=chicken;
            if(found==null) throw new AssertionError("controlled chicken missing");
            found.setIsChickenJockey(true);
            if(!found.isChickenJockey()) throw new AssertionError("native jockey flag not applied");
            sender.sendMessage("WGENTITYFIXTURE success jockey=true egg="+found.getEggLayTime());
          }catch(Throwable failure){sender.sendMessage("WGENTITYFIXTURE failed "+failure);failure.printStackTrace();}
        });
      });return true;
    });
  }
}
