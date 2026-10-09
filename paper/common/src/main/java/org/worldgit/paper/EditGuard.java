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
  EditGuard(WorldGitPlugin plugin) { this.plugin=plugin; }
  void shutdown() {
    for(var entry:tickLeases.entrySet()) {
      var world=org.bukkit.Bukkit.getWorld(entry.getKey());if(world==null)continue;
      for(var hold:entry.getValue().held.entrySet()) if(plugin.platform().ownedHere(world,hold.getKey().x(),hold.getKey().z()))
        try {hold.getValue().close();plugin.bridge().saveChunk(world,hold.getKey().x(),hold.getKey().z());}
        catch(Exception error){plugin.getLogger().severe("Chunk guard cleanup: "+error);}
      for(var entity:plugin.bridge().clearEntityBoundary(world))if(org.bukkit.Bukkit.isOwnedByCurrentRegion(entity))plugin.bridge().restoreEntity(entity);
    }
    tickLeases.clear();locked.clear();chunkLocks.clear();
  }
  private final Map<UUID,Set<ChunkPos>> chunkLocks=new ConcurrentHashMap<>();
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
  private record Deferred(int x,int y,int z,String source) {}
  private final Map<UUID,Set<Deferred>> deferred=new ConcurrentHashMap<>();
  private void defer(Block block,String source) {
    var at=block.getLocation();deferred.computeIfAbsent(at.getWorld().getUID(),id->ConcurrentHashMap.newKeySet()).add(new Deferred(at.getBlockX(),at.getBlockY(),at.getBlockZ(),source));
  }
  private void replay(World world) {
    var updates=deferred.remove(world.getUID());if(updates==null || plugin==null)return;
    for(var update:updates)world.getChunkAtAsync(update.x>>4,update.z>>4,true).thenAccept(chunk->
      plugin.platform().region(world,update.x>>4,update.z>>4,()->plugin.bridge().wakeBoundary(world,update.x,update.y,update.z,update.source))
    ).exceptionally(error->{plugin.getLogger().severe("Boundary update replay: "+error);return null;});
  }
  private record TickLease(Set<ChunkPos> scope,Map<ChunkPos,AutoCloseable> held) {}
  private final Map<UUID,TickLease> tickLeases=new ConcurrentHashMap<>();
  void registerTicks(World world,Set<ChunkPos> scope,Map<ChunkPos,AutoCloseable> held) {tickLeases.put(world.getUID(),new TickLease(scope,held));}
  void unregisterTicks(World world) {tickLeases.remove(world.getUID());}
  void ensureChunkTicks(World world,int x,int z) {
    var lease=tickLeases.get(world.getUID());var pos=new ChunkPos(x,z);
    if(lease==null || !lease.scope.contains(pos) || lease.held.containsKey(pos) || !world.isChunkLoaded(x,z))return;
    var hold=plugin.bridge().lockChunkTicks(world,x,z);
    lease.held.put(pos,hold);
  }
  @EventHandler(priority=EventPriority.LOWEST) public void loaded(org.bukkit.event.world.ChunkLoadEvent event) {
    ensureChunkTicks(event.getWorld(),event.getChunk().getX(),event.getChunk().getZ());
  }
  @EventHandler(priority=EventPriority.LOWEST) public void entitiesLoaded(org.bukkit.event.world.EntitiesLoadEvent event) {
    if(locked(event.getWorld()))for(var entity:event.getEntities())plugin.bridge().guardEntity(entity);
    ensureChunkTicks(event.getWorld(),event.getChunk().getX(),event.getChunk().getZ());
    var pos=new ChunkPos(event.getChunk().getX(),event.getChunk().getZ());
    var lease=tickLeases.get(event.getWorld().getUID());
    if(lease!=null && lease.held.containsKey(pos))plugin.bridge().refreshChunkLock(event.getWorld(),pos.x(),pos.z());
  }
  @EventHandler(priority=EventPriority.LOWEST) public void entityAdded(com.destroystokyo.paper.event.entity.EntityAddToWorldEvent event) {
    if(locked(event.getEntity().getWorld()))plugin.bridge().guardEntity(event.getEntity());
  }
  boolean locked(World world) { return locked.contains(world.getUID()) || chunkLocks.containsKey(world.getUID()); }
  void spawn(World world,org.worldgit.core.model.EntitySnapshot entity) throws java.io.IOException {
    boolean previous=ownWrite.get(); ownWrite.set(true);
    try { plugin.bridge().spawnEntity(world,entity); }
    finally { ownWrite.set(previous); }
  }
  boolean locked(World world,int x,int z) {return locked.contains(world.getUID()) || chunkLocks.getOrDefault(world.getUID(),Set.of()).contains(new ChunkPos(x,z));}
  boolean locked(String name,int x,int z) {var w=org.bukkit.Bukkit.getWorld(name);return w!=null && locked(w,x,z);}
  boolean locked(String name) { var w=org.bukkit.Bukkit.getWorld(name); return w!=null && locked(w); }
  AutoCloseable lock(World world) {
    if(!locked.add(world.getUID())) throw new IllegalStateException("世界已有進行中的操作");
    return ()->{locked.remove(world.getUID());replay(world);};
  }
  AutoCloseable lock(World world,Collection<ChunkPos> chunks) {
    if(chunks.isEmpty()) return lock(world);
    if(locked(world) || chunkLocks.putIfAbsent(world.getUID(),Set.copyOf(chunks))!=null) throw new IllegalStateException("世界已有進行中的操作");
    return ()->{chunkLocks.remove(world.getUID());replay(world);};
  }
  boolean blocked(org.bukkit.Location pos) {
    return locked.contains(pos.getWorld().getUID()) || chunkLocks.getOrDefault(pos.getWorld().getUID(),Set.of())
        .contains(new ChunkPos(pos.getBlockX()>>4,pos.getBlockZ()>>4));
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
    if(!(e.getEntity() instanceof Player player)) { if(blocked(e.getEntity().getLocation()) && !ownWrite.get()) e.setCancelled(true); return; }
    var cause=switch(e.getCause()) { case FALL->PlayerProtection.Damage.FALL; case SUFFOCATION->PlayerProtection.Damage.SUFFOCATION; case DROWNING->PlayerProtection.Damage.DROWNING; default->null; };
    if(cause==null) return;
    if(protectedFrom(player,cause)) e.setCancelled(true);
  }
  boolean protectedFrom(Player player,PlayerProtection.Damage cause) {
    observe(player);
    for(var p:protections.values()) if(p.until>=System.nanoTime() && p.players.contains(player.getUniqueId()) && p.protection.causes().contains(cause)) return true;
    return false;
  }
  @EventHandler public void move(PlayerMoveEvent e) { observe(e.getPlayer()); if(e.getTo()!=null && (blocked(e.getFrom()) || blocked(e.getTo()))) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void entityMove(io.papermc.paper.event.entity.EntityMoveEvent e) { if(blocked(e.getFrom()) || blocked(e.getTo())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void teleport(EntityTeleportEvent e) {if(blocked(e.getFrom()) || e.getTo()!=null && blocked(e.getTo()))e.setCancelled(true);}
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void playerTeleport(PlayerTeleportEvent e) {if(blocked(e.getFrom()) || e.getTo()!=null && blocked(e.getTo()))e.setCancelled(true);}
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void projectile(ProjectileHitEvent e) {if(blocked(e.getEntity().getLocation()) || e.getHitBlock()!=null && blocked(e.getHitBlock()) || e.getHitEntity()!=null && blocked(e.getHitEntity().getLocation()))e.setCancelled(true);}
  @EventHandler public void join(PlayerJoinEvent e) { observe(e.getPlayer()); }
  @EventHandler public void quit(PlayerQuitEvent e) { protections.values().forEach(p->p.players.remove(e.getPlayer().getUniqueId())); }
  private boolean blocked(org.bukkit.inventory.Inventory inventory) {var location=inventory.getLocation();return location!=null && blocked(location);}
  private boolean blocked(Block b) { return blocked(b.getLocation()); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void place(BlockPlaceEvent e) { if(blocked(e.getBlock()) || e instanceof BlockMultiPlaceEvent multi && multi.getReplacedBlockStates().stream().anyMatch(b->blocked(b.getBlock()))) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void breaking(BlockBreakEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interact(PlayerInteractEvent e) { if(blocked(e.getPlayer().getLocation()) || e.getClickedBlock()!=null && (blocked(e.getClickedBlock()) || blocked(e.getClickedBlock().getRelative(e.getBlockFace())))) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interactEntity(PlayerInteractEntityEvent e) { if(blocked(e.getPlayer().getLocation()) || blocked(e.getRightClicked().getLocation())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void armorStand(PlayerArmorStandManipulateEvent e) { if(blocked(e.getPlayer().getLocation()) || blocked(e.getRightClicked().getLocation())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drop(PlayerDropItemEvent e) { if(blocked(e.getPlayer().getLocation())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void pickup(EntityPickupItemEvent e) { if(blocked(e.getEntity().getLocation())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void spawn(EntitySpawnEvent e) { if(blocked(e.getEntity().getLocation()) && !ownWrite.get()) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void spawn(CreatureSpawnEvent e) { if(blocked(e.getEntity().getLocation()) && !ownWrite.get()) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void spawn(ItemSpawnEvent e) { if(blocked(e.getEntity().getLocation()) && !ownWrite.get()) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void transform(EntityTransformEvent e) { if(blocked(e.getEntity().getLocation())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void breed(EntityBreedEvent e) { if(blocked(e.getEntity().getLocation())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void bucketFill(PlayerBucketFillEvent e) { if(blocked(e.getBlock()) || blocked(e.getBlockClicked())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void bucketEmpty(PlayerBucketEmptyEvent e) { if(blocked(e.getBlock()) || blocked(e.getBlockClicked().getRelative(e.getBlockFace()))) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void click(InventoryClickEvent e) { if(blocked(e.getWhoClicked().getLocation()) || blocked(e.getView().getTopInventory())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drag(InventoryDragEvent e) { if(blocked(e.getWhoClicked().getLocation()) || blocked(e.getView().getTopInventory())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void dispense(BlockDispenseEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void open(InventoryOpenEvent e) { if(blocked(e.getPlayer().getLocation()) || blocked(e.getInventory())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void transfer(InventoryMoveItemEvent e) { if(blocked(e.getSource()) || blocked(e.getDestination())) e.setCancelled(true); }
  private boolean pistonBlocked(Block base,org.bukkit.block.BlockFace direction,List<Block> moved) {
    return blocked(base) || blocked(base.getRelative(direction)) || blocked(base.getRelative(direction.getOppositeFace())) || moved.stream().anyMatch(b->blocked(b) || blocked(b.getRelative(direction)) || blocked(b.getRelative(direction.getOppositeFace())));
  }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void piston(BlockPistonExtendEvent e) { if(pistonBlocked(e.getBlock(),e.getDirection(),e.getBlocks())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void piston(BlockPistonRetractEvent e) { if(pistonBlocked(e.getBlock(),e.getDirection(),e.getBlocks())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void flow(BlockFromToEvent e) { if(blocked(e.getBlock())||blocked(e.getToBlock())) {defer(e.getBlock(),null);e.setCancelled(true);} }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void physics(BlockPhysicsEvent e) { if(blocked(e.getBlock())) {defer(e.getBlock(),e.getChangedType().name());e.setCancelled(true);} }
  @EventHandler(priority=EventPriority.HIGHEST) public void redstone(BlockRedstoneEvent e) { if(blocked(e.getBlock())) {if(e.getNewCurrent()!=e.getOldCurrent())defer(e.getBlock(),e.getBlock().getType().name());e.setNewCurrent(e.getOldCurrent());} }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(BlockExplodeEvent e) { if(blocked(e.getBlock()) || e.blockList().stream().anyMatch(this::blocked)) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(EntityExplodeEvent e) { if(blocked(e.getLocation()) || e.blockList().stream().anyMatch(this::blocked)) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void change(EntityChangeBlockEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void grow(BlockGrowEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void spread(BlockSpreadEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void form(BlockFormEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void fade(BlockFadeEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void burn(BlockBurnEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void ignite(BlockIgniteEvent e) { if(blocked(e.getBlock())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void fertilize(BlockFertilizeEvent e) { if(blocked(e.getBlock()) || e.getBlocks().stream().anyMatch(b->blocked(b.getBlock()))) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void death(EntityDeathEvent e) { if(blocked(e.getEntity().getLocation())) { e.getDrops().clear(); e.setDroppedExp(0); } }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void command(PlayerCommandPreprocessEvent e) { if((!locked.isEmpty() || !chunkLocks.isEmpty()) && !allowedCommand(e.getMessage())) e.setCancelled(true); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void command(ServerCommandEvent e) { if((!locked.isEmpty() || !chunkLocks.isEmpty()) && !allowedCommand(e.getCommand())) e.setCancelled(true); }
  private boolean allowedCommand(String text) {
    String cmd=text.replaceFirst("^/","").split(" ",2)[0].toLowerCase(Locale.ROOT);
    return Set.of("wg","worldgit","stop","list","tps","mspt","say","msg","tell").contains(cmd);
  }
}
