package org.worldgit.paper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerCommandEvent;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.platform.PlayerProtection;

/** 事件在玩家／block owner 觸發。政策用不可變集合發布，region 不等待 coordinator。 */
final class EditGuard implements Listener {
  private final WorldGitPlugin plugin;
  private final ThreadLocal<Boolean> ownWrite=ThreadLocal.withInitial(()->false);
  private AutoCloseable freeze;
  private int freezeUsers;
  EditGuard(WorldGitPlugin plugin) { this.plugin=plugin; }
  // 此政策的 acquire/release/shutdown 全部由 global owner 執行。
  AutoCloseable freeze(World world) {
    if(freezeUsers==0) freeze=plugin.bridge().freeze(world);
    freezeUsers++;
    return ()->{ if(freezeUsers>0 && --freezeUsers==0) { var f=freeze; freeze=null; f.close(); } };
  }
  void shutdown() {
    if(freeze!=null) try { freeze.close(); } catch(Exception e) { plugin.getLogger().warning("恢復 tick 狀態失敗："+e); }
    freeze=null; freezeUsers=0; locked.clear();
  }
  private final Set<UUID> locked = ConcurrentHashMap.newKeySet();
  private record ProtectionKey(UUID operation, UUID world) {}
  private final Map<ProtectionKey, Policy> protections = new ConcurrentHashMap<>();
  private static final class Policy {
    final UUID world;
    final PlayerProtection protection;
    final Set<UUID> players = ConcurrentHashMap.newKeySet();
    volatile long until = Long.MAX_VALUE;
    Policy(World world, PlayerProtection p) { this.world=world.getUID(); protection=p; }
  }
  boolean locked(World world) { return locked.contains(world.getUID()); }
  void spawn(World world,org.worldgit.core.model.EntitySnapshot entity) throws java.io.IOException {
    boolean previous=ownWrite.get(); ownWrite.set(true);
    try { plugin.bridge().spawnEntity(world,entity); }
    finally { ownWrite.set(previous); }
  }
  boolean locked(String name) { var w=org.bukkit.Bukkit.getWorld(name); return w!=null && locked(w); }
  AutoCloseable lock(World world) {
    if(!locked.add(world.getUID())) throw new IllegalStateException("世界已有進行中的操作");
    return ()->locked.remove(world.getUID());
  }
  void protect(World world, PlayerProtection protection) {
    var id=new ProtectionKey(protection.operation()==null ? UUID.randomUUID() : protection.operation(),world.getUID());
    if(protection.active()) protections.put(id,new Policy(world,protection));
    else {
      var p=protections.computeIfAbsent(id,k->new Policy(world,protection));
      p.until=System.nanoTime()+protection.duration().toNanos();
    }
  }
  void observe(Player player) {
    long now=System.nanoTime();
    var loc=player.getLocation(); var pos=new ChunkPos(loc.getBlockX()>>4,loc.getBlockZ()>>4);
    protections.values().removeIf(p->p.until<now);
    for(var p:protections.values()) if(p.world.equals(player.getWorld().getUID()) && p.protection.chunks().contains(pos)) p.players.add(player.getUniqueId());
  }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void damage(EntityDamageEvent e) {
    if(!(e.getEntity() instanceof Player player)) { if(locked(e.getEntity().getWorld()) && !ownWrite.get()) e.setCancelled(true); return; }
    var cause=switch(e.getCause()) { case FALL->PlayerProtection.Damage.FALL; case SUFFOCATION->PlayerProtection.Damage.SUFFOCATION; case DROWNING->PlayerProtection.Damage.DROWNING; default->null; };
    if(cause==null) return;
    if(protectedFrom(player,cause)) e.setCancelled(true);
  }
  boolean protectedFrom(Player player,PlayerProtection.Damage cause) {
    observe(player);
    for(var p:protections.values()) if(p.until>=System.nanoTime() && p.players.contains(player.getUniqueId()) && p.protection.causes().contains(cause)) return true;
    return false;
  }
  @EventHandler public void move(PlayerMoveEvent e) { observe(e.getPlayer()); }
  @EventHandler public void join(PlayerJoinEvent e) { observe(e.getPlayer()); }
  @EventHandler public void quit(PlayerQuitEvent e) { protections.values().forEach(p->p.players.remove(e.getPlayer().getUniqueId())); }
  private boolean blocked(Block b) { return locked(b.getWorld()); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void place(BlockPlaceEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void breaking(BlockBreakEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interact(PlayerInteractEvent e) { if(locked(e.getPlayer().getWorld())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interactEntity(PlayerInteractEntityEvent e) { if(locked(e.getPlayer().getWorld())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void armorStand(PlayerArmorStandManipulateEvent e) { if(locked(e.getPlayer().getWorld())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drop(PlayerDropItemEvent e) { if(locked(e.getPlayer().getWorld())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void pickup(EntityPickupItemEvent e) { if(locked(e.getEntity().getWorld())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void spawn(CreatureSpawnEvent e) { if(locked(e.getEntity().getWorld()) && !ownWrite.get()) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void spawn(ItemSpawnEvent e) { if(locked(e.getEntity().getWorld()) && !ownWrite.get()) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void transform(EntityTransformEvent e) { if(locked(e.getEntity().getWorld())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void breed(EntityBreedEvent e) { if(locked(e.getEntity().getWorld())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void bucketFill(PlayerBucketFillEvent e) { if(locked(e.getPlayer().getWorld())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void bucketEmpty(PlayerBucketEmptyEvent e) { if(locked(e.getPlayer().getWorld())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void click(InventoryClickEvent e) { if(locked(e.getWhoClicked().getWorld())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drag(InventoryDragEvent e) { if(locked(e.getWhoClicked().getWorld())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void dispense(BlockDispenseEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void open(InventoryOpenEvent e) { if(locked(e.getPlayer().getWorld())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void transfer(InventoryMoveItemEvent e) { if(!locked.isEmpty()) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void piston(BlockPistonExtendEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void piston(BlockPistonRetractEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void flow(BlockFromToEvent e) { if(blocked(e.getBlock())||blocked(e.getToBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void physics(BlockPhysicsEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST) public void redstone(BlockRedstoneEvent e) { if(blocked(e.getBlock())) e.setNewCurrent(e.getOldCurrent()); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(BlockExplodeEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(EntityExplodeEvent e) { if(locked(e.getLocation().getWorld())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void change(EntityChangeBlockEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void grow(BlockGrowEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void spread(BlockSpreadEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void form(BlockFormEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void fade(BlockFadeEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void burn(BlockBurnEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void ignite(BlockIgniteEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void fertilize(BlockFertilizeEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void death(EntityDeathEvent e) { if(locked(e.getEntity().getWorld())) { e.getDrops().clear(); e.setDroppedExp(0); } }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void command(PlayerCommandPreprocessEvent e) { if(!locked.isEmpty() && !allowedCommand(e.getMessage())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void command(ServerCommandEvent e) { if(!locked.isEmpty() && !allowedCommand(e.getCommand())) e.setCancelled(true); }
  private boolean allowedCommand(String text) {
    String cmd=text.replaceFirst("^/","").split(" ",2)[0].toLowerCase(Locale.ROOT);
    return Set.of("wg","worldgit","stop","list","tps","mspt","say","msg","tell").contains(cmd);
  }
}
