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
  private volatile MergeState state;
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
  MergeState state() { return state; }
  void refresh() {
    if(stopped) return;
    plugin.repo().submit(()->{
      refreshFromRepository(); return null;
    }).exceptionally(e->{ if(!stopped) plugin.getLogger().warning("讀取 MERGING 失敗："+e.getMessage()); return null; });
  }
  /** repo executor 在操作完成前發布 UI 狀態；不能排在定時 fetch 後才更新。 */
  void refreshFromRepository() throws IOException {
    if(stopped) return;
    state=MergeState.read(WorldMapper.map().layout().repositoryRoot().resolve("merge-state.bin"));
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
      var s=state; var regions=allowed(p) ? visible(p,s) : List.<Region>of();
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
    if(p.getInventory().firstEmpty()<0) { p.sendMessage(Messages.line("paper.merge.inventory-full")); return; }
    p.getInventory().addItem(item); p.sendMessage(Messages.line("paper.merge.tool-given"));
  }
  @EventHandler(priority=EventPriority.LOWEST)
  public void interact(PlayerInteractEvent e) {
    if(e.getHand()!=EquipmentSlot.HAND || (e.getAction()!=Action.RIGHT_CLICK_AIR && e.getAction()!=Action.RIGHT_CLICK_BLOCK)) return;
    if(e.getItem()==null || !e.getItem().hasItemMeta() || !e.getItem().getItemMeta().getPersistentDataContainer().has(toolKey,PersistentDataType.BYTE)) return;
    e.setCancelled(true); var p=e.getPlayer();
    if(!p.hasPermission("worldgit.command.resolve")) { p.sendMessage(Messages.permission("resolve")); return; }
    if(plugin.isEditLocked(p.getWorld()) || plugin.repo().applying()) return;
    long now=System.nanoTime(); var prior=clicks.put(p.getUniqueId(),now); if(prior!=null && now-prior<250_000_000L) return;
    var s=state; var r=at(p,visible(p,s)); if(r==null) { p.sendMessage(Messages.line("paper.merge.outside")); return; }
    boolean resolve=p.isSneaking();
    Choice choice=switch(r.choice()) { case OURS->Choice.THEIRS; case THEIRS->Choice.BASE; default->Choice.OURS; };
    plugin.repo().regionOperation("conflict #"+r.id(),ops->{
      // queued tool actions must still refer to the same durable operation
      var current=ops.core().merging();
      if(current==null || !Objects.equals(current.operation(),s.operation())) throw new IOException("合併工作已變更");
      return resolve ? ops.core().markResolved(r.id(),false,false) : ops.core().selectRegion(r.id(),choice,false,false);
    }).whenComplete((result,error)->feedback(p,result,error));
  }
  void feedback(org.bukkit.command.CommandSender sender,WorldOperations.MergeResult result,Throwable error) {
    if(stopped) return;
    Runnable work=()->Messages.inLocale(sender,()->{
      if(error!=null) sender.sendMessage(Messages.error(error.getCause()==null ? error.getMessage() : error.getCause().getMessage()));
      else if(!result.success()) sender.sendMessage(Messages.line("paper.merge.partial","message",result.error()));
      else sender.sendMessage(Messages.line("paper.merge.result","state",result.state(),"count",result.merging()==null ? 0 : result.merging().remaining(),"commits",result.commits()));
    });
    if(sender instanceof Player p) plugin.platform().entity(p,work,()->{}); else work.run();
  }
  private static final class Menu implements InventoryHolder {
    final UUID operation; final int page; final List<Region> regions; Inventory inventory;
    Menu(MergeState s,int page) { operation=s.operation(); this.page=page; regions=s.regions(); }
    public Inventory getInventory() { return inventory; }
  }
  void open(Player p,int requestedPage) {
    var s=state; if(s==null) { p.sendMessage(Messages.line("paper.merge.none")); return; }
    int page=Math.max(0,Math.min(requestedPage,Math.max(0,(s.regions().size()-1)/45)));
    var menu=new Menu(s,page); menu.inventory=Bukkit.createInventory(menu,54,Messages.line("paper.merge.gui-title","page",page+1));
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
    e.setCancelled(true); if(!(e.getWhoClicked() instanceof Player p) || !allowed(p)) return;
    if(state==null || !menu.operation.equals(state.operation())) { p.closeInventory(); return; }
    int slot=e.getRawSlot(); if(slot==45 || slot==53) { open(p,menu.page+(slot==45 ? -1 : 1)); return; }
    int index=menu.page*45+slot; if(slot<0 || slot>=45 || index>=menu.regions.size()) return;
    var r=menu.regions.get(index); var b=r.bounds(); if(b==null) { p.sendMessage(Messages.line("paper.merge.metadata")); return; }
    var world=plugin.world(r.dimension()); if(world==null) return;
    p.closeInventory();
    // 目的地在 bbox 上方，teleportAsync 負責 Folia 載入與跨 region 傳送。
    p.teleportAsync(new Location(world,(b.minX()+b.maxX())/2.0+.5,Math.min(world.getMaxHeight()-2,b.maxY()+2),(b.minZ()+b.maxZ())/2.0+.5))
        .thenAccept(ok->{ if(!ok) plugin.platform().entity(p,()->p.sendMessage(Messages.line("paper.merge.teleport-failed")),()->{}); });
  }
  @EventHandler public void drag(InventoryDragEvent e) { if(e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true); }
  @EventHandler public void quit(PlayerQuitEvent e) { outlines.clear(e.getPlayer()); shown.remove(e.getPlayer().getUniqueId()); bars.remove(e.getPlayer().getUniqueId()); clicks.remove(e.getPlayer().getUniqueId()); }
  void shutdown() { stopped=true; outlines.clearAll(); bars.clear(); shown.clear(); }
}
