package org.worldgit.paper;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.worldgit.core.merge.*;
import org.worldgit.core.merge.MergeReport.*;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.service.WorldOperations;
import org.worldgit.protocol.DiffPalette;

/** durable state 的唯讀 UI；所有修改仍透過 repo executor／core coordinator。 */
final class MergeUi implements Listener {
  private final WorldGitPlugin plugin;
  private final DisplayFallback outlines;
  private final NamespacedKey toolKey;
  private volatile Map<UUID,MergeState> states=Map.of();
  private volatile long version;
  private volatile boolean stopped;
  private final Map<UUID,BossBar> bars=new ConcurrentHashMap<>();
  private final Map<UUID,String> shown=new ConcurrentHashMap<>();
  private final Map<UUID,Long> clicks=new ConcurrentHashMap<>();
  private volatile DiffPalette palette=DiffPalette.DEFAULT;

  MergeUi(WorldGitPlugin plugin) {
    this.plugin=plugin; outlines=new DisplayFallback(plugin); toolKey=new NamespacedKey(plugin,"merge_tool");
    plugin.getServer().getPluginManager().registerEvents(this,plugin);
    plugin.platform().asyncRepeating(1,1000,this::tick);
  }
  MergeState state() {
    var action=OperationUi.current();
    if(action!=null && action.mapping!=null && action.dimension!=null) {
      var world=action.mapping.worlds().get(action.dimension);return world==null?null:states.get(world.getUID());
    }
    return states.values().stream().findFirst().orElse(null);
  }
  MergeState state(Player p) {return state(p.getWorld());}
  MergeState state(World world) {return world==null?null:states.get(world.getUID());}
  void refresh() {
    if(stopped) return;
    plugin.repo().submit(()->{
      refreshFromRepository(); return null;
    }).exceptionally(e->{ if(!stopped) plugin.getLogger().warning("讀取 MERGING 失敗："+e.getMessage()); return null; });
  }
  /** repo executor 在操作完成前發布 UI 狀態；不能排在定時 fetch 後才更新。 */
  void refreshFromRepository() throws IOException {
    if(stopped) return;
    var next=new HashMap<UUID,MergeState>();
    for(var world:plugin.getServer().getWorlds()) {
      var mapping=plugin.mapping(world);if(mapping==null)continue;
      var id=plugin.dimensionOf(world).orElse(null);if(id==null)continue;
      var path=new org.worldgit.core.service.WorldRepositories(mapping.layout()).tracked().get(id);if(path==null)continue;
      var state=MergeState.read(path.resolve("merge-state.bin"));if(state!=null)next.put(world.getUID(),state);
    }
    states=Map.copyOf(next);
    palette=Messages.palette(plugin.repo().readLocal().palette()); version++; tick();
  }
  private static boolean allowed(Player p) { return p.hasPermission("worldgit.command.conflicts") || p.hasPermission("worldgit.admin"); }
  private List<Region> visible(Player p,MergeState s) {
    var dimension=plugin.dimensionOf(p.getWorld()).orElse(null);
    return s==null ? List.of() : s.regions().stream().filter(r->r.dimension().equals(dimension)).toList();
  }
  private void tick() {
    if(stopped || !plugin.isEnabled()) return;
    long v=version;
    for(var p:plugin.getServer().getOnlinePlayers()) plugin.platform().entity(p,()->{
      if(stopped || v!=version) return;
      var s=state(p); var regions=allowed(p) ? visible(p,s) : List.<Region>of();
      boolean merging=s!=null && allowed(p) && plugin.dimensionOf(p.getWorld()).map(s.dimensions()::containsKey).orElse(false);
      if(merging) {
        var bar=bars.computeIfAbsent(p.getUniqueId(),id->BossBar.bossBar(Component.empty(),0,BossBar.Color.PURPLE,BossBar.Overlay.PROGRESS));
        bar.name(Messages.inLocale(p,()->Messages.line("paper.merge.progress","count",s.remaining())));
        bar.progress(s.regions().isEmpty() ? 1f : 1f-(float)s.remaining()/s.regions().size()); p.showBossBar(bar);
        Region current=at(p,regions);
        if(current!=null) p.sendActionBar(Messages.inLocale(p,()->Messages.line("paper.merge.hint","id",current.id(),"choice",current.choice().name().toLowerCase(Locale.ROOT),"status",status(current))));
      } else { var b=bars.remove(p.getUniqueId()); if(b!=null) p.hideBossBar(b); }
      String key=v+":"+p.getWorld().getUID()+":"+merging;
      if(!key.equals(shown.put(p.getUniqueId(),key))) {
        if(!merging || s.remaining()==0) outlines.clear(p);
        else outlines.showRegions(p,regions,palette);
      }
      plugin.fabric().sendRegions(p,plugin.dimensionOf(p.getWorld()).orElse(DimensionId.OVERWORLD),regions,key);
    },()->{ bars.remove(p.getUniqueId()); shown.remove(p.getUniqueId()); });
  }
  private static String status(Region r) { return r.resolved() ? "resolved" : "unresolved"; }
  static Region at(Player p,List<Region> regions) {
    var l=p.getLocation();
    return regions.stream().filter(r->r.bounds()!=null && r.bounds().contains(l.getBlockX(),l.getBlockY(),l.getBlockZ())).findFirst().orElse(null);
  }
  void tool(Player p) {
    if(!p.hasPermission("worldgit.command.resolve")) { p.sendMessage(Messages.permission("resolve")); return; }
    var item=new ItemStack(Material.COMPASS); var meta=item.getItemMeta();
    meta.displayName(Messages.line("paper.merge.tool-name")); meta.lore(List.of(Messages.line("paper.merge.tool-lore")));
    meta.getPersistentDataContainer().set(toolKey,PersistentDataType.BYTE,(byte)1); item.setItemMeta(meta);
    if(p.getInventory().firstEmpty()<0) { p.sendMessage(plugin.operations().error(Messages.line("paper.merge.inventory-full"))); return; }
    p.getInventory().addItem(item); p.sendMessage(Messages.line("paper.merge.tool-given"));
  }
  @EventHandler(priority=EventPriority.LOWEST)
  public void interact(PlayerInteractEvent e) {
    if(e.getHand()!=EquipmentSlot.HAND || (e.getAction()!=Action.RIGHT_CLICK_AIR && e.getAction()!=Action.RIGHT_CLICK_BLOCK)) return;
    if(e.getItem()==null || !e.getItem().hasItemMeta() || !e.getItem().getItemMeta().getPersistentDataContainer().has(toolKey,PersistentDataType.BYTE)) return;
    e.setCancelled(true); var p=e.getPlayer();
    long now=System.nanoTime(); var prior=clicks.put(p.getUniqueId(),now); if(prior!=null && now-prior<250_000_000L) return;
    boolean resolve=p.isSneaking();
    var action=plugin.operations().begin(p,resolve?"tool.resolve":"tool.select",plugin.mapping(p.getWorld()),plugin.dimensionOf(p.getWorld()).orElse(null));
    OperationUi.within(action,()->{try {
      if(!p.hasPermission("worldgit.command.resolve")) {p.sendMessage(Messages.permission("resolve"));return null;}
      if(plugin.isEditLocked(p.getWorld()) || plugin.repo().applying()) {p.sendMessage(plugin.operations().error(Messages.line("paper.phase5.tool-busy")));return null;}
      var s=state(p);var r=at(p,visible(p,s));
      if(r==null) {action.status=org.worldgit.core.operation.OperationResult.Status.NO_OP;p.sendMessage(Messages.line("paper.merge.outside"));return null;}
      Choice choice=switch(r.choice()) {case OURS->Choice.THEIRS;case THEIRS->Choice.BASE;default->Choice.OURS;};
      plugin.repo().regionOperation(r.dimension(),"conflict #"+r.id(),ops->{
        var current=ops.core().merging();
        if(current==null || !Objects.equals(current.operation(),s.operation()))throw new IOException("合併工作已變更");
        return resolve?ops.core().markResolved(r.id(),false,false):ops.core().selectRegion(r.id(),choice,false,false);
      }).whenComplete((result,error)->feedback(p,result,error));
    }finally {action.release();}return null;});
  }
  void feedback(org.bukkit.command.CommandSender sender,WorldOperations.MergeResult result,Throwable error) {
    if(stopped) return;
    plugin.operations().send(sender,()-> {
      if(error!=null)return Messages.error(error.getCause()==null?error.getMessage():error.getCause().getMessage());
      if(!result.success())return Messages.line("paper.merge.partial","message",result.error());
      return Messages.line("paper.merge.result","state",result.state(),"count",result.merging()==null?0:result.merging().remaining(),"commits",result.commits());
    });
  }

  private static final class Menu implements InventoryHolder {
    final UUID operation; final int page; final List<Region> regions; final World world; Inventory inventory;
    Menu(MergeState s,int page,World world) { operation=s.operation(); this.page=page; regions=s.regions(); this.world=world; }
    public Inventory getInventory() { return inventory; }
  }
  void open(Player p,int requestedPage) {
    var action=OperationUi.current();var world=action!=null && action.mapping!=null && action.dimension!=null?action.mapping.worlds().get(action.dimension):p.getWorld();
    var s=state(world); if(s==null) { p.sendMessage(Messages.line("paper.merge.none")); return; }
    int page=Math.max(0,Math.min(requestedPage,Math.max(0,(s.regions().size()-1)/45)));
    var menu=new Menu(s,page,world); menu.inventory=Bukkit.createInventory(menu,54,Messages.line("paper.merge.gui-title","page",page+1));
    for(int slot=0;slot<45 && page*45+slot<menu.regions.size();slot++) {
      var r=menu.regions.get(page*45+slot); var item=new ItemStack(r.resolved() ? Material.LIME_WOOL : Material.PURPLE_WOOL);
      var meta=item.getItemMeta(); meta.displayName(Messages.line("paper.merge.region","id",r.id(),"count",r.blockCount()));
      long hints=s.dimensions().get(r.dimension()).report().updateShapes().stream().filter(c->r.bounds()!=null && r.bounds().contains(c.x(),c.y(),c.z())).count();
      meta.lore(List.of(Messages.line("paper.merge.coords","dimension",r.dimension(),"bounds",r.bounds()),
          Messages.line("paper.merge.authors","ours",String.join(", ",r.oursAuthors()),"theirs",String.join(", ",r.theirsAuthors())),
          Messages.line("paper.merge.region-status","choice",r.choice().name().toLowerCase(Locale.ROOT),"status",status(r)),
          Messages.line("paper.merge.warnings","redstone",r.redstone(),"count",hints),Messages.line("paper.merge.teleport")));
      item.setItemMeta(meta); menu.inventory.setItem(slot,item);
    }
    for(int slot:new int[]{45,53}) { var item=new ItemStack(Material.ARROW); var meta=item.getItemMeta(); meta.displayName(Messages.line(slot==45 ? "paper.merge.previous" : "paper.merge.next")); item.setItemMeta(meta); menu.inventory.setItem(slot,item); }
    p.openInventory(menu.inventory);
  }
  @EventHandler public void click(InventoryClickEvent e) {
    if(!(e.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
    e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p))return;
    int slot=e.getRawSlot(),index=menu.page*45+slot;
    boolean page=slot==45 || slot==53;
    if(!page && (slot<0 || slot>=45 || index>=menu.regions.size()))return;
    var action=plugin.operations().begin(p,page?"conflicts.page":"conflicts.teleport",plugin.mapping(menu.world),plugin.dimensionOf(menu.world).orElse(null));
    OperationUi.within(action,()->{try {
      if(!allowed(p)) {p.sendMessage(Messages.permission("conflicts"));return null;}
      var current=state(menu.world);
      if(current==null || !menu.operation.equals(current.operation())) {p.closeInventory();p.sendMessage(plugin.operations().error(Messages.line("paper.phase5.merge-stale")));return null;}
      if(page) {open(p,menu.page+(slot==45?-1:1));return null;}
      var r=menu.regions.get(index);var b=r.bounds();
      if(b==null) {action.status=org.worldgit.core.operation.OperationResult.Status.NO_OP;p.sendMessage(Messages.line("paper.merge.metadata"));return null;}
      var world=plugin.mapping(menu.world).worlds().get(r.dimension());
      if(world==null) {p.sendMessage(plugin.operations().error(Messages.line("paper.phase5.world-missing")));return null;}
      p.closeInventory();action.summary.put("region",r.id());action.retain();
      try {
        p.teleportAsync(new Location(world,(b.minX()+b.maxX())/2.0+.5,Math.min(world.getMaxHeight()-2,b.maxY()+2),(b.minZ()+b.maxZ())/2.0+.5))
            .whenComplete((ok,error)->OperationUi.within(action,()->{
              if(error!=null || !Boolean.TRUE.equals(ok)) {action.failed(error==null?new IOException("teleport failed"):error);plugin.operations().send(p,()->Messages.line("paper.merge.teleport-failed"));}
              action.release();return null;
            }));
      }catch(RuntimeException failure) {action.failed(failure);action.release();}
    }finally {action.release();}return null;});
  }
  @EventHandler public void drag(InventoryDragEvent e) { if(e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true); }
  @EventHandler public void quit(PlayerQuitEvent e) { outlines.clear(e.getPlayer()); shown.remove(e.getPlayer().getUniqueId()); bars.remove(e.getPlayer().getUniqueId()); clicks.remove(e.getPlayer().getUniqueId()); }
  void shutdown() { stopped=true; outlines.clearAll(); bars.clear(); shown.clear(); }
}
