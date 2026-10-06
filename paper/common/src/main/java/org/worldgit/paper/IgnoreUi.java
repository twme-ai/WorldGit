package org.worldgit.paper;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.worldgit.core.config.*;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.service.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.capture.PlayerTouchedEntities;

/** 確認綁執行者、維度、原規則與 HEAD；GUI 與聊天修改走同一 preview／確認入口。 */
final class IgnoreUi implements Listener {
  record Pending(String code,WorldMapper.Mapping mapping,DimensionId dimension,String before,String head,IgnoreEditor.Document proposed,long expires) {}
  private final WorldGitPlugin plugin;
  private final Map<String,Pending> pending=new ConcurrentHashMap<>();
  private final Map<UUID,Menu> chat=new ConcurrentHashMap<>();
  IgnoreUi(WorldGitPlugin plugin) {this.plugin=plugin;plugin.getServer().getPluginManager().registerEvents(this,plugin);}
  private static String key(CommandSender sender) {return sender instanceof Player p?p.getUniqueId().toString():"console";}
  private static boolean allowed(CommandSender sender) {return sender.hasPermission("worldgit.command.ignore") || sender.hasPermission("worldgit.admin");}
  void run(CommandSender sender,CommandRequest request) {
    if(!allowed(sender))throw new IllegalArgumentException("worldgit.command.ignore");
    var action=Objects.requireNonNull(OperationUi.current());
    String selector=request.text("text");
    Nbt.Compound targeted=null;
    if(request.command().startsWith("ignore.test") && selector==null) {
      if(!(sender instanceof Player p))throw new IllegalArgumentException("test 需要 selector");
      if(request.command().equals("ignore.test.hand"))selector="field "+p.getInventory().getItemInMainHand().getType().getKey()+" components";
      else {
        var entity=p.getTargetEntity(6);
        if(entity!=null) {
          var loc=entity.getLocation();selector="entity "+entity.getType().getKey()+" "+loc.getX()+","+loc.getY()+","+loc.getZ();
          var raw=plugin.bridge().copy(entity.getWorld(),loc.getBlockX()>>4,loc.getBlockZ()>>4);
          try {if(raw!=null)for(var n:raw.entities()) {targeted=find(n,entity.getUniqueId());if(targeted!=null)break;}}catch(IOException error) {throw new IllegalArgumentException("無法複製實體",error);}
        } else {var block=p.getTargetBlockExact(6);if(block==null)throw new IllegalArgumentException("準星沒有方塊／實體");selector="block "+block.getX()+","+block.getY()+","+block.getZ();}
      }
    }
    String test=selector;Nbt.Compound entity=targeted;
    plugin.repo().submit(()->{
      var path=new WorldRepositories(action.mapping.layout()).tracked().get(action.dimension);
      if(path==null)throw new IOException("維度尚未 init："+action.dimension);
      try(var repo=new DimensionRepository(path,action.dimension,false)) {
        var document=IgnoreEditor.read(repo.ignorePath());
        if(request.text("beforeIgnore")!=null && !request.text("beforeIgnore").equals(document.text()))throw new IOException("ignore GUI 已過期；請重新開啟");
        var semantics=EntityTagRegistry.load(action.mapping.layout().world(),action.mapping.layout().dataVersion());
        String mode=request.command().substring(7);
        switch(mode) {
          case "confirm" -> {
            var proposal=pending.get(key(sender));
            if(proposal==null || !proposal.code().equals(request.text("code")) || proposal.expires()<System.currentTimeMillis()
                || !proposal.dimension().equals(action.dimension) || !proposal.mapping().layout().world().equals(action.mapping.layout().world())
                || !proposal.before().equals(document.text()) || !Objects.equals(proposal.head(),repo.refs().head()))throw new IOException("ignore preview 已過期；請重新預覽");
            IgnoreEditor.write(repo,proposal.proposed());pending.remove(key(sender),proposal);
            plugin.operations().send(sender,()->Messages.line("paper.phase5.ignore-written"));return null;
          }
          case "list", "gui" -> {
            if(mode.equals("gui") && sender instanceof Player p) {
              action.retain();plugin.platform().entity(p,()->OperationUi.within(action,()->{try {Messages.inLocale(p,()->open(p,action.mapping,action.dimension,document,0));}finally {action.release();}return null;}),action::release);
            } else for(var line:document.entries())plugin.operations().send(sender,()->Component.text(line.number()+" "+line.text()));
            return document.entries();
          }
          case "check" -> {plugin.operations().send(sender,()->Messages.line("paper.phase5.ignore-valid","count",document.lines().size()));return document.entries();}
          case "test", "test.target", "test.hand" -> {
            var result=entity==null?IgnoreEditor.test(document,test,semantics):IgnoreEditor.testEntity(document,entity,semantics);
            plugin.operations().send(sender,()->Messages.line("paper.phase5.ignore-test","excluded",result.excluded(),"line",result.line(),"rule",result.rule()));return result;
          }
          default -> {
            if(org.worldgit.core.merge.MergeState.read(path.resolve("merge-state.bin"))!=null)throw new IOException("MERGING 期間禁止修改 .wgignore");
            var proposed=switch(mode) {
              case "add" -> document.add(request.text("text"));
              case "remove" -> document.remove(request.number("line",0));
              case "move" -> document.move(request.number("line",0),request.number("destination",0));
              case "enable", "disable" -> document.enabled(request.number("line",0),mode.equals("enable"));
              case "preview" -> document;
              default -> throw new IOException("ignore mode");
            };
            var preview=IgnoreEditor.preview(repo,proposed,semantics);
            plugin.operations().send(sender,()->Messages.line("paper.phase5.ignore-preview","blocks",preview.blocks(),"bes",preview.blockEntities(),"entities",preview.entities(),"biomes",preview.biomeSamples(),"fields",preview.metadataFields(),"samples",preview.examples()));
            if(!mode.equals("preview")) {
              var proposal=new Pending(UUID.randomUUID().toString().substring(0,8),action.mapping,action.dimension,document.text(),repo.refs().head(),proposed,System.currentTimeMillis()+120000);
              pending.put(key(sender),proposal);
              plugin.operations().send(sender,()->Messages.line("paper.phase5.ignore-confirm","code",proposal.code()).clickEvent(ClickEvent.runCommand("/wg ignore --world "+CommandArguments.quote(action.mapping.worlds().get(action.dimension).getName())+" --dimension "+action.dimension+" confirm "+proposal.code())));
            }
            return preview;
          }
        }
      }
    }).exceptionally(error->{plugin.operations().send(sender,()->Messages.error(error.getCause()==null?error.getMessage():error.getCause().getMessage()));return null;});
  }
  private static Nbt.Compound find(Nbt.Compound n,UUID id) {
    var ids=new HashSet<UUID>();PlayerTouchedEntities.collect(n,ids);
    if(!ids.contains(id))return null;
    if(org.worldgit.core.normalize.EntityNormalizer.uuid(n).equals(id))return n;
    for(var child:n.list("Passengers").values()) {var found=find((Nbt.Compound)child,id);if(found!=null)return found;}return null;
  }
  private static final class Menu implements InventoryHolder {
    final WorldMapper.Mapping mapping;final DimensionId dimension;final IgnoreEditor.Document document;final int page;
    Inventory inventory;
    Menu(WorldMapper.Mapping mapping,DimensionId dimension,IgnoreEditor.Document document,int page) {this.mapping=mapping;this.dimension=dimension;this.document=document;this.page=page;}
    public Inventory getInventory(){return inventory;}
  }
  private void open(Player p,WorldMapper.Mapping mapping,DimensionId dimension,IgnoreEditor.Document document,int requested) {
    if(!allowed(p)) {p.sendMessage(Messages.permission("ignore"));return;}
    int page=Math.max(0,Math.min(requested,Math.max(0,(document.lines().size()-1)/45)));
    var menu=new Menu(mapping,dimension,document,page);
    menu.inventory=Bukkit.createInventory(menu,54,Messages.text("paper.phase5.ignore-gui","page",page+1));
    for(var line:document.entries().stream().skip((long)page*45).limit(45).toList()) {
      var item=new ItemStack(line.rule()?line.enabled()?Material.PAPER:Material.GRAY_DYE:Material.MAP);
      var meta=item.getItemMeta();meta.displayName(Component.text(line.number()+" "+line.text()));meta.lore(List.of(Messages.text("paper.phase5.ignore-controls")));item.setItemMeta(meta);menu.inventory.setItem((line.number()-1)%45,item);
    }
    button(menu,45,Material.ARROW,"paper.merge.previous");button(menu,49,Material.WRITABLE_BOOK,"paper.phase5.ignore-add");button(menu,53,Material.ARROW,"paper.merge.next");p.openInventory(menu.inventory);
  }
  private static void button(Menu menu,int slot,Material material,String key) {var item=new ItemStack(material);var meta=item.getItemMeta();meta.displayName(Messages.text(key));item.setItemMeta(meta);menu.inventory.setItem(slot,item);}
  private void edit(Player p,Menu menu,CommandRequest request) {
    var action=plugin.operations().begin(p,request.command(),menu.mapping,menu.dimension);
    var values=new HashMap<>(request.values());values.put("beforeIgnore",menu.document.text());
    OperationUi.within(action,()->{try {run(p,new CommandRequest(request.command(),values,request.flags()));}catch(RuntimeException error){action.failed(error);plugin.operations().send(p,()->Messages.error(error.getMessage()));}finally{action.release();}return null;});
  }
  @EventHandler public void click(InventoryClickEvent event) {
    if(!(event.getView().getTopInventory().getHolder() instanceof Menu menu))return;
    event.setCancelled(true);if(!(event.getWhoClicked() instanceof Player p))return;
    int slot=event.getRawSlot();
    if(slot==45 || slot==53) {local(p,menu,"ignore.gui.page",()->open(p,menu.mapping,menu.dimension,menu.document,menu.page+(slot==45?-1:1)));return;}
    if(slot==49) {local(p,menu,"ignore.gui.input",()->{chat.put(p.getUniqueId(),menu);p.closeInventory();p.sendMessage(Messages.line("paper.phase5.ignore-chat"));});return;}
    if(slot<0 || slot>=45)return;int line=menu.page*45+slot+1;if(line>menu.document.lines().size())return;
    var values=new HashMap<String,Object>();values.put("line",line);String mode;
    if(event.isShiftClick()) {mode="move";values.put("destination",Math.max(1,line-1));}
    else if(event.isRightClick())mode=menu.document.entries().get(line-1).enabled()?"disable":"enable";
    else mode="remove";
    p.closeInventory();edit(p,menu,new CommandRequest("ignore."+mode,values,Set.of()));
  }
  private void local(Player p,Menu menu,String operation,Runnable work) {
    var action=plugin.operations().begin(p,operation,menu.mapping,menu.dimension);
    OperationUi.within(action,()->{try {if(!allowed(p))p.sendMessage(Messages.permission("ignore"));else work.run();}catch(RuntimeException error) {action.failed(error);plugin.operations().send(p,()->Messages.error(error.getMessage()));}finally{action.release();}return null;});
  }
  @EventHandler public void drag(InventoryDragEvent e) {if(e.getView().getTopInventory().getHolder() instanceof Menu)e.setCancelled(true);}
  @EventHandler public void input(io.papermc.paper.event.player.AsyncChatEvent event) {
    var menu=chat.remove(event.getPlayer().getUniqueId());if(menu==null)return;event.setCancelled(true);
    String text=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.message());
    plugin.platform().entity(event.getPlayer(),()->edit(event.getPlayer(),menu,new CommandRequest("ignore.add",Map.of("text",text),Set.of())),()->{});
  }
  @EventHandler public void quit(PlayerQuitEvent e) {pending.remove(key(e.getPlayer()));chat.remove(e.getPlayer().getUniqueId());}
}
