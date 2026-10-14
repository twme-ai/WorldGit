package org.worldgit.fixture;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;


/** 驗收專用，不進正式 jar：自然出生原因與第三方 /git 衝突。 */
public final class Phase5Fixture extends JavaPlugin implements org.bukkit.event.Listener {
  private final java.util.Map<java.util.UUID,org.bukkit.permissions.PermissionAttachment> permissions=new java.util.HashMap<>();
  public void onEnable() {
    getServer().getPluginManager().registerEvents(this,this);
    if(getConfig().getBoolean("git",false))getServer().getCommandMap().register("fixture",new org.bukkit.command.Command("git") {
      public boolean execute(org.bukkit.command.CommandSender sender,String label,String[] args){sender.sendMessage("OTHER_GIT");return true;}
    });
    logGitFixture("enable");
    getCommand("wgfixture").setExecutor((sender,command,label,args)->{
      var p=Bukkit.getPlayerExact(args[0]);if(p==null)return false;
      p.getScheduler().run(this,task->{
        if(args.length>1 && (args[1].equals("revoke-switch") || args[1].equals("restore-switch"))) {
          var previous=permissions.remove(p.getUniqueId());if(previous!=null)p.removeAttachment(previous);
          if(args[1].equals("revoke-switch")) {
            var attachment=p.addAttachment(this);attachment.setPermission("worldgit.admin",false);
            attachment.setPermission("worldgit.command.switch",false);permissions.put(p.getUniqueId(),attachment);
          }
          sender.sendMessage("SWITCH_ALLOWED="+(p.hasPermission("worldgit.admin") || p.hasPermission("worldgit.command.switch")));return;
        }
        if(args.length>1 && args[1].equals("pin")) {
          p.setFlying(true);p.setGravity(false);
          p.teleportAsync(new Location(p.getWorld(),8.5,65,8.5)).whenComplete((ok,error)->sender.sendMessage("PINNED="+ok+" ERROR="+error));return;
        }
        if(args.length>2 && args[1].equals("give-name")) {
          var item=new org.bukkit.inventory.ItemStack(Material.NAME_TAG);var meta=item.getItemMeta();
          meta.displayName(net.kyori.adventure.text.Component.text(args[2]));item.setItemMeta(meta);
          p.getInventory().setItemInMainHand(item);sender.sendMessage("HELD=NAME_TAG NAME="+p.getInventory().getItemInMainHand().getItemMeta().getDisplayName());return;
        }
        if(args.length>2 && args[1].equals("inspect-name")) {
          var entity=Bukkit.getEntity(java.util.UUID.fromString(args[2]));
          sender.sendMessage("NAMED="+args[2]+" NAME="+(entity==null?null:entity.getCustomName())+" DISTANCE="+(entity==null?null:p.getLocation().distance(entity.getLocation())));return;
        }
        if(args.length>1 && args[1].equals("give-armor")) {
          p.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.ARMOR_STAND));
          sender.sendMessage("HELD="+p.getInventory().getItemInMainHand().getType());return;
        }
        if(args.length>3 && args[1].equals("transfer")) {
          var entity=Bukkit.getEntity(java.util.UUID.fromString(args[2]));
          var destination=Bukkit.getWorld(NamespacedKey.fromString(args[3]));
          if(entity==null || destination==null) {sender.sendMessage("TRANSFERRED=false");return;}
          entity.getScheduler().run(this,ignored->entity.teleportAsync(destination.getSpawnLocation()).whenComplete((ok,error)->sender.sendMessage("TRANSFERRED="+ok+" UUID="+args[2]+" DIMENSION="+args[3]+" ERROR="+error)),null);return;
        }
        if(args.length>1 && args[1].equals("inspect")) {
          var entities=p.getWorld().getChunkAt(p.getLocation()).getEntities();
          sender.sendMessage("ENTITIES="+java.util.Arrays.stream(entities).filter(e->!(e instanceof Player)).map(e->e.getType()+":"+e.getUniqueId()).sorted().toList());return;
        }
        if(args.length>2 && args[1].equals("remove")) {
          for(var entity:p.getWorld().getChunkAt(p.getLocation()).getEntities())if(entity.getUniqueId().toString().equals(args[2]))entity.remove();sender.sendMessage("REMOVED="+args[2]);return;
        }
        var location=p.getLocation().add(2,0,0);
        var cow=p.getWorld().spawn(location,Cow.class,CreatureSpawnEvent.SpawnReason.NATURAL,c->{c.setAI(false);c.setGravity(false);});
        sender.sendMessage("NATURAL_UUID="+cow.getUniqueId());
      },null);return true;
    });
  }

  @org.bukkit.event.EventHandler public void serverLoaded(org.bukkit.event.server.ServerLoadEvent event) {
    logGitFixture("server-load");
  }
  private void logGitFixture(String stage) {
    var map=getServer().getCommandMap();
    getLogger().info("GIT_FIXTURE stage="+stage+" configured="+getConfig().getBoolean("git",false)
        +" git="+map.getCommand("git")+" fixture:git="+map.getCommand("fixture:git")
        +" worldgit:git="+map.getCommand("worldgit:git"));
  }
  @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.MONITOR)
  public void interact(org.bukkit.event.player.PlayerInteractEntityEvent event) {
    var player=event.getPlayer();var entity=event.getRightClicked();
    getLogger().info("INTERACT UUID="+entity.getUniqueId()+" CANCELLED="+event.isCancelled()+" HAND="+event.getHand()+" ITEM="+player.getInventory().getItemInMainHand()+" DISTANCE="+player.getLocation().distance(entity.getLocation()));
  }
}
