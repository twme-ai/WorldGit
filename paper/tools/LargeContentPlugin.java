package org.worldgit.fixture;

import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.entity.ArmorStand;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** 大型實體／BE 驗收專用；每個 chunk 的讀寫都在自己的 owner callback。 */
public final class LargeContentPlugin extends JavaPlugin {
  public void onEnable() {
    getCommand("wglarge").setExecutor((sender,command,label,args)->{
      var world=Objects.requireNonNull(Bukkit.getWorld("world"));
      String variant=args[0];var jobs=new ArrayList<CompletableFuture<?>>();
      for(int x=-8;x<8;x++)for(int z=-4;z<4;z++) {
        int cx=x,cz=z;
        jobs.add(world.getChunkAtAsync(cx,cz,false).thenAccept(chunk->{
          if(chunk==null || !Bukkit.isOwnedByCurrentRegion(world,cx,cz))throw new IllegalStateException("missing chunk owner "+cx+","+cz);
          chunk.addPluginChunkTicket(this);
          for(int i=0;i<16;i++) {
            int bx=cx*16+1+(i%4)*3,bz=cz*16+1+(i/4)*3;
            var block=world.getBlockAt(bx,64,bz);block.setType(Material.CHEST,false);
            var chest=(Chest)block.getState();
            var item=new ItemStack(variant.equals("A")?Material.GOLD_INGOT:Material.DIAMOND,32);
            var meta=item.getItemMeta();meta.displayName(net.kyori.adventure.text.Component.text("WG large "+variant));
            meta.lore(List.of(net.kyori.adventure.text.Component.text("Native BE fixture "+"payload ".repeat(32))));item.setItemMeta(meta);
            for(int slot=0;slot<27;slot++)chest.getBlockInventory().setItem(slot,item.clone());
            var location=new Location(world,bx+.5,66,bz+.5);
            var stand=Arrays.stream(chunk.getEntities()).filter(e->e instanceof ArmorStand && e.getScoreboardTags().contains("wglarge-"+bx+"-"+bz)).map(e->(ArmorStand)e).findFirst().orElseGet(()->world.spawn(location,ArmorStand.class,e->{e.setGravity(false);e.setInvulnerable(true);e.setMarker(true);e.addScoreboardTag("wglarge-"+bx+"-"+bz);}));
            stand.customName(net.kyori.adventure.text.Component.text("WG large "+variant));
            stand.getEquipment().setHelmet(new ItemStack(variant.equals("A")?Material.GOLDEN_HELMET:Material.DIAMOND_HELMET));
          }
          if(Arrays.stream(chunk.getEntities()).filter(e->e.getScoreboardTags().stream().anyMatch(tag->tag.startsWith("wglarge-"))).count()!=16)throw new IllegalStateException("entity fixture count");
        }));
      }
      CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).whenComplete((ignored,error)->{
        if(error!=null){sender.sendMessage("WGLARGE failed "+error);error.printStackTrace();}
        else sender.sendMessage("WGLARGE complete chunks=128 entities=2048 blockEntities=2048 variant="+variant);
      });return true;
    });
  }
}
