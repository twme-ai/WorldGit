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
