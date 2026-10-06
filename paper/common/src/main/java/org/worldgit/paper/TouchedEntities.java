package org.worldgit.paper;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerCommandEvent;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.capture.PlayerTouchedEntities;
import org.worldgit.core.service.*;

/** owner 只收集關聯 UUID；sidecar 修改與 capture 在同一 repo queue，絕不在 region 寫檔。 */
final class TouchedEntities implements Listener {
  private final WorldGitPlugin plugin;
  private final Set<UUID> known=ConcurrentHashMap.newKeySet();
  private final Map<UUID,Set<UUID>> beforeInit=new ConcurrentHashMap<>();
  void refresh(WorldMapper.Mapping mapping) throws IOException {
    for(var path:new WorldRepositories(mapping.layout()).tracked().values())known.addAll(PlayerTouchedEntities.read(path));
  }
  TouchedEntities(WorldGitPlugin plugin) {
    this.plugin=plugin;plugin.getServer().getPluginManager().registerEvents(this,plugin);
    plugin.repo().submit(()->{
      for(var mapping:plugin.mappings()) {
        refresh(mapping);
      }
      return null;
    });
  }
  void touch(Entity entity) {
    if(entity==null || entity instanceof Player || plugin.repo().stopping())return;
    Entity root=entity;var visited=new HashSet<UUID>();
    while(root.getVehicle()!=null && visited.add(root.getUniqueId()))root=root.getVehicle();
    var ids=new TreeSet<UUID>();collect(root,ids);
    var mapping=plugin.mapping(entity.getWorld());var dimension=plugin.dimensionOf(entity.getWorld()).orElse(null);
    if(mapping==null || dimension==null || ids.isEmpty())return;
    known.addAll(ids);plugin.touch(entity.getWorld(),entity.getLocation().getBlockX()>>4,entity.getLocation().getBlockZ()>>4,null,"entity-touch");
    // 只傳遞無 Bukkit 物件的 NBT，core touch 同時記錄根與乘客閉包。
    var data=nbt(ids);
    plugin.repo().submit(()->{
      var path=new WorldRepositories(mapping.layout()).tracked().get(dimension);
      if(path!=null)try(var repo=new DimensionRepository(path,dimension,false)) {PlayerTouchedEntities.touch(repo.directory(),data);}
      else beforeInit.computeIfAbsent(mapping.worlds().get(dimension).getUID(),key->ConcurrentHashMap.newKeySet()).addAll(ids);
      return null;
    }).exceptionally(error->{plugin.getLogger().warning("保存觸及實體失敗："+org.worldgit.core.operation.OperationResult.redact(error.getMessage()));return null;});
  }
  /** 由 init 的 source factory 呼叫，此時 core 已持有該維度 repo lock。 */
  void seed(org.bukkit.World world,java.nio.file.Path path) {
    var ids=beforeInit.remove(world.getUID());if(ids==null || ids.isEmpty())return;
    try {PlayerTouchedEntities.touch(path,nbt(new TreeSet<>(ids)));}
    catch(IOException error) {beforeInit.computeIfAbsent(world.getUID(),key->ConcurrentHashMap.newKeySet()).addAll(ids);throw new java.io.UncheckedIOException(error);}
  }
  static Nbt.Compound nbt(SortedSet<UUID> ids) {
    var iter=ids.iterator();var root=uuid(iter.next());var children=new ArrayList<Object>();while(iter.hasNext())children.add(uuid(iter.next()));
    if(!children.isEmpty())root.put("Passengers",new Nbt.ListTag(10,children));return root;
  }
  private static Nbt.Compound uuid(UUID id) {return new Nbt.Compound().with("UUID",new int[]{(int)(id.getMostSignificantBits()>>>32),(int)id.getMostSignificantBits(),(int)(id.getLeastSignificantBits()>>>32),(int)id.getLeastSignificantBits()});}
  private static void collect(Entity entity,Set<UUID> ids) {
    if(!ids.add(entity.getUniqueId()))return;
    for(var passenger:entity.getPassengers())if(!(passenger instanceof Player))collect(passenger,ids);
  }
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void spawn(CreatureSpawnEvent e) {
    if(Set.of(CreatureSpawnEvent.SpawnReason.SPAWNER_EGG,CreatureSpawnEvent.SpawnReason.DISPENSE_EGG,CreatureSpawnEvent.SpawnReason.COMMAND,CreatureSpawnEvent.SpawnReason.EGG).contains(e.getSpawnReason()))touch(e.getEntity());
  }
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void spawn(EntitySpawnEvent e) {
    if(e.getEntity().getEntitySpawnReason()==CreatureSpawnEvent.SpawnReason.COMMAND)touch(e.getEntity());
  }
  @EventHandler(priority=EventPriority.MONITOR) public void added(com.destroystokyo.paper.event.entity.EntityAddToWorldEvent e) {
    if(known.contains(e.getEntity().getUniqueId()))plugin.platform().entity(e.getEntity(),()->touch(e.getEntity()),()->{});
  }
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void place(EntityPlaceEvent e) {touch(e.getEntity());}
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void hanging(HangingPlaceEvent e) {if(e.getPlayer()!=null)touch(e.getEntity());}
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void interact(PlayerInteractEntityEvent e) {touch(e.getRightClicked());}
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void armor(PlayerArmorStandManipulateEvent e) {touch(e.getRightClicked());}
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void leash(PlayerLeashEntityEvent e) {touch(e.getEntity());}
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void unleash(PlayerUnleashEntityEvent e) {touch(e.getEntity());}
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void tame(EntityTameEvent e) {touch(e.getEntity());}
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void dye(SheepDyeWoolEvent e) {touch(e.getEntity());}
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void breed(EntityBreedEvent e) {if(e.getBreeder()!=null) {touch(e.getEntity());touch(e.getMother());touch(e.getFather());}}
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void mount(org.bukkit.event.entity.EntityMountEvent e) {if(e.getEntity() instanceof Player || known.contains(e.getEntity().getUniqueId()) || known.contains(e.getMount().getUniqueId())) {touch(e.getMount());if(!(e.getEntity() instanceof Player))touch(e.getEntity());}}
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void transform(EntityTransformEvent e) {if(known.contains(e.getEntity().getUniqueId()))e.getTransformedEntities().forEach(this::touch);}
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void portal(EntityPortalEvent e) {if(known.contains(e.getEntity().getUniqueId()))plugin.platform().entityDelayed(e.getEntity(),1,()->touch(e.getEntity()),()->{});}
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void teleport(EntityTeleportEvent e) {if(known.contains(e.getEntity().getUniqueId()))plugin.platform().entityDelayed(e.getEntity(),1,()->touch(e.getEntity()),()->{});}
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void command(PlayerCommandPreprocessEvent e) {data(e.getPlayer(),e.getMessage());}
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void command(ServerCommandEvent e) {data(e.getSender(),e.getCommand());}
  private void data(org.bukkit.command.CommandSender sender,String command) {
    String[] words=command.replaceFirst("^/","").split("\\s+");
    int index=-1;
    for(int i=0;i+3<words.length;i++)if(Set.of("data","minecraft:data").contains(words[i]) && Set.of("merge","modify","remove").contains(words[i+1]) && words[i+2].equals("entity")) {index=i+3;break;}
    if(index<0)return;
    try {for(var entity:Bukkit.selectEntities(sender,words[index]))plugin.platform().entity(entity,()->touch(entity),()->{});}
    catch(IllegalArgumentException ignored) { }
  }
}
