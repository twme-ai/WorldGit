package org.worldgit.paper;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

/**
 * 開發／驗收用工具（權限 worldgit.debug，預設只有主控台）：製造大量 dirty chunk，並量測 tick 間隔。
 * 這些不是玩家功能；放在插件內是因為「commit 1 千／1 萬個 chunk 的主執行緒阻塞」必須在真正的 tick 迴圈裡量測。
 */
final class Debug {
  private final WorldGitPlugin plugin;
  private final List<long[]> tickets = Collections.synchronizedList(new ArrayList<>());
  private volatile World ticketWorld;

  private volatile boolean probing;
  private final ConcurrentLinkedQueue<Long> gaps = new ConcurrentLinkedQueue<>();
  private volatile long lastTick;

  Debug(WorldGitPlugin plugin) {
    this.plugin = plugin;
  }

  void run(io.papermc.paper.command.brigadier.CommandSourceStack source, CommandRequest request)
      throws com.mojang.brigadier.exceptions.CommandSyntaxException {
    var sender = source.getSender();
    org.bukkit.entity.Player player = null;
    if (request.values().containsKey("player")) player = request.value("player", io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver.class).resolve(source).getFirst();
    io.papermc.paper.math.BlockPosition position = null;
    if (request.values().containsKey("position")) {
      var pos = request.value("position", io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver.class).resolve(source);
      position = pos;
    }
    try {
      String mode = request.command().substring("debug.".length());
      switch (mode) {
        case "apply" -> {
          var q = plugin.repo().activeQueue();
          sender.sendMessage(Component.text(q == null ? "WGAPPLY idle" : "WGAPPLY sections=" + q.sections.get() + " tickets=" + q.tickets.get() + " peak=" + q.peak.get()));
        }
        case "sample" -> sample(sender, request.number("cx", 0), request.number("cz", 0), request.number("sy", 0));
        case "freeze.on", "freeze.off" -> freeze(sender, mode.endsWith(".on"));
        case "protection" -> protection(sender, player);
        case "comment-camera" -> {
          var target = player;
          var world = plugin.world(org.worldgit.core.model.DimensionId.OVERWORLD);
          plugin.platform().entity(target, () -> {
            target.setAllowFlight(true); target.setFlying(true); target.setGravity(false);
            target.teleportAsync(new org.bukkit.Location(world, 8.5, 224, -5.5, 0, 0)).whenComplete((ok, error) ->
                plugin.platform().global(() -> sender.sendMessage(Component.text("WGCOMMENTCAM success=" + (error == null && Boolean.TRUE.equals(ok))))));
          }, () -> {});
        }
        case "comment-teleport" -> {
          var target = player;
          var world = Objects.requireNonNull(plugin.world(new org.worldgit.core.model.DimensionId(request.value("dimension", net.kyori.adventure.key.Key.class).asString())));
          plugin.platform().entity(target, () -> target.teleportAsync(new org.bukkit.Location(world, 8.5, 224, 8.5)).whenComplete((ok, error) ->
              plugin.platform().global(() -> sender.sendMessage(Component.text("WGCOMMENTTP success=" + (error == null && Boolean.TRUE.equals(ok)))))), () -> {});
        }
        case "comment-displays" -> {
          int x = request.number("cx", 0), z = request.number("cz", 0);
          var world = plugin.world(org.worldgit.core.model.DimensionId.OVERWORLD);
          plugin.platform().region(world, x, z, () -> {
            var ids = java.util.Arrays.stream(world.getChunkAt(x, z).getEntities()).filter(e -> e instanceof org.bukkit.entity.TextDisplay && e.getScoreboardTags().contains(CommentDisplays.TAG)).map(org.bukkit.entity.Entity::getEntityId).sorted().toList();
            plugin.platform().global(() -> sender.sendMessage(Component.text("WGCOMMENTS chunk=" + x + "," + z + " entities=" + ids.size() + " ids=" + ids)));
          });
        }
        case "fill" -> fill(sender, request.number("side", 1), request.text("material", "gold_block"), request.number("total", request.number("side", 1) * request.number("side", 1)), request.number("offset", 8));
        case "fixture-container" -> fixtureContainer(sender, position, request.text("material"));
        case "fixture-clean-items" -> fixtureCleanItems(sender);
        case "merge-tool.cycle", "merge-tool.resolve" -> mergeTool(sender, player, mode.endsWith(".resolve"));
        case "merge-gui" -> mergeGui(sender, player, request.number("slot", 0));
        case "release" -> release(sender);
        case "probe.start", "probe.stop" -> probe(sender, mode.endsWith(".start"));
        case "measurements" -> plugin.repo().measurements().forEach(m -> sender.sendMessage(Component.text(m.toString())));
        default -> {
          if (mode.startsWith("entities.")) {
            var parts = mode.split("[.]");
            entities(sender, parts[1].equals("overworld") ? World.Environment.NORMAL : World.Environment.NETHER,
                request.number("cx", 0), request.number("cz", 0), parts[2]);
          } else throw new IllegalArgumentException(mode);
        }
      }
    } catch (RuntimeException e) { sender.sendMessage(Messages.inLocale(sender, () -> Messages.error(String.valueOf(e.getMessage())))); }
  }

  /** Folia 的 vanilla /data、全域 /kill 不可用；驗收 fixture 走真正 owner。 */
  private void fixtureContainer(CommandSender sender, io.papermc.paper.math.BlockPosition position, String block) {
    int x = position.blockX(), y = position.blockY(), z = position.blockZ();
    var material = Objects.requireNonNull(Material.matchMaterial(block));
    var world=Bukkit.getWorlds().getFirst();
    plugin.platform().region(world,x>>4,z>>4,()->{
      try {
        var container=(org.bukkit.block.Container)world.getBlockAt(x,y,z).getState();
        container.getSnapshotInventory().clear();
        container.getSnapshotInventory().setItem(0,new org.bukkit.inventory.ItemStack(material,4));
        if(!container.update(true,false)) throw new IllegalStateException("container update failed");
        sender.sendMessage(Component.text("WGCONTAINER done"));
      } catch(RuntimeException e) { sender.sendMessage(Messages.error(e.toString())); }
    });
  }
  private void fixtureCleanItems(CommandSender sender) {
    var world=Bukkit.getWorlds().getFirst();
    var remaining=new AtomicInteger(2);
    for(int cx=0;cx<=1;cx++) {
      final int chunk=cx;
      plugin.platform().region(world,chunk,0,()->{
        for(var entity:world.getChunkAt(chunk,0).getEntities()) if(entity instanceof org.bukkit.entity.Item) entity.remove();
        if(remaining.decrementAndGet()==0) sender.sendMessage(Component.text("WGITEMS done"));
      });
    }
  }

  /** 驗收模擬真正 Bukkit 右鍵事件，仍走物品 PDC／權限／owner／core barrier。 */
  private void mergeTool(CommandSender sender, org.bukkit.entity.Player p, boolean resolve) {
    plugin.platform().entity(p,()->{
      var item=Arrays.stream(p.getInventory().getContents()).filter(Objects::nonNull).filter(i->i.getType()==Material.COMPASS).findFirst().orElseThrow();
      boolean sneaking=p.isSneaking(); p.setSneaking(resolve);
      try { Bukkit.getPluginManager().callEvent(new org.bukkit.event.player.PlayerInteractEvent(p,org.bukkit.event.block.Action.RIGHT_CLICK_AIR,item,null,org.bukkit.block.BlockFace.SELF,org.bukkit.inventory.EquipmentSlot.HAND)); }
      finally { p.setSneaking(sneaking); }
      sender.sendMessage(Component.text("WGMERGETOOL event delivered"));
    },()->{});
  }
  private void mergeGui(CommandSender sender, org.bukkit.entity.Player p, int slot) {
    plugin.platform().entity(p,()->{
      var view=p.getOpenInventory();
      if(slot<0 || slot>=view.getTopInventory().getSize()) throw new IllegalArgumentException("slot 超出 GUI");
      Bukkit.getPluginManager().callEvent(new org.bukkit.event.inventory.InventoryClickEvent(view,org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,slot,org.bukkit.event.inventory.ClickType.LEFT,org.bukkit.event.inventory.InventoryAction.PICKUP_ALL));
      sender.sendMessage(Component.text("WGMERGEGUI event delivered"));
    },()->{});
  }

  private AutoCloseable fixtureFreeze;
  /** Folia 沒有 /tick；驗收用同一套全域 freeze，操作期間的引用計數不會解除它。 */
  private void freeze(CommandSender sender, boolean on) {
    plugin.platform().global(()->{
      try {
        if(on) {
          if(fixtureFreeze==null) fixtureFreeze=plugin.edits().freeze(Bukkit.getWorlds().getFirst());
        } else if(fixtureFreeze!=null) {
          fixtureFreeze.close(); fixtureFreeze=null;
        }
        sender.sendMessage(Component.text("WGFREEZE "+(fixtureFreeze==null ? "restored" : "frozen")));
      } catch(Exception e) { sender.sendMessage(Component.text("WGFREEZE failed "+e)); }
    });
  }

  private static final List<UUID> ENTITY_IDS=List.of(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff"),UUID.fromString("cccccccc-dddd-eeee-ffff-111111111111"));
  /** 受控跨維度／巢狀乘客場景，任何讀寫都在載入完成的 chunk owner。 */
  private void entities(CommandSender sender, World.Environment environment, int cx, int cz, String action) {
    var world = Bukkit.getWorlds().stream().filter(w -> w.getEnvironment() == environment).findFirst().orElseThrow();
    if(plugin.edits().locked(world) && !action.equals("inspect")) throw new IllegalArgumentException("世界已鎖定");
    var queue=new ApplyQueue(plugin);
    queue.run(List.of(new ApplyQueue.Task(world,new org.worldgit.core.model.ChunkPos(cx,cz),(w,c)->{
      if(action.equals("seed") || action.equals("solo")) {
        var root=entityData(ENTITY_IDS.getFirst(),"cow");
        root.put("Pos",new org.worldgit.core.anvil.Nbt.ListTag(6,List.of(cx*16d+8,70d,cz*16d+8)));
        if(action.equals("seed")) {
          var pig=entityData(ENTITY_IDS.get(1),"pig");
          pig.put("Passengers",new org.worldgit.core.anvil.Nbt.ListTag(10,List.of(entityData(ENTITY_IDS.get(2),"chicken"))));
          root.put("Passengers",new org.worldgit.core.anvil.Nbt.ListTag(10,List.of(pig)));
        }
        plugin.edits().spawn(w,new org.worldgit.core.model.EntitySnapshot(ENTITY_IDS.getFirst(),root));
      } else if(action.equals("clear")) plugin.bridge().removeEntities(w,cx,cz,new HashSet<>(ENTITY_IDS));
      else { var raw=plugin.bridge().copy(w,cx,cz); if(raw!=null) for(var entity:raw.entities()) inspectEntity(sender,entity,0); }
      return java.util.concurrent.CompletableFuture.completedFuture(null);
    })),org.worldgit.platform.ApplyBudget.WITH_PLAYERS,true).whenComplete((v,e)->sender.sendMessage(Component.text(e==null ? "WGENTITY complete "+action : "WGENTITY failed "+e)));
  }
  private static org.worldgit.core.anvil.Nbt.Compound entityData(UUID id,String type) {
    return new org.worldgit.core.anvil.Nbt.Compound().with("id","minecraft:"+type)
      .with("UUID",new int[]{(int)(id.getMostSignificantBits()>>32),(int)id.getMostSignificantBits(),(int)(id.getLeastSignificantBits()>>32),(int)id.getLeastSignificantBits()})
      .with("NoAI",(byte)1).with("NoGravity",(byte)1).with("Invulnerable",(byte)1).with("PersistenceRequired",(byte)1);
  }
  private static void inspectEntity(CommandSender sender,org.worldgit.core.anvil.Nbt.Compound data,int depth) {
    if(data.get("UUID") instanceof int[] a && a.length==4) {
      var id=new UUID(((long)a[0]<<32)|(a[1]&0xffffffffL),((long)a[2]<<32)|(a[3]&0xffffffffL));
      if(ENTITY_IDS.contains(id)) sender.sendMessage(Component.text("WGENTITY uuid="+id+" depth="+depth+" pos="+data.list("Pos").values()));
    }
    for(Object child:data.list("Passengers").values()) inspectEntity(sender,(org.worldgit.core.anvil.Nbt.Compound)child,depth+1);
  }

  private void protection(CommandSender sender, org.bukkit.entity.Player player) {
    plugin.platform().entity(player,()->{
      // 用 DamageSource 版建構子（三參數版已標記 removal）；DamageType 對應各 DamageCause
      var cases=new java.util.LinkedHashMap<org.bukkit.event.entity.EntityDamageEvent.DamageCause,org.bukkit.damage.DamageType>();
      cases.put(org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL,org.bukkit.damage.DamageType.FALL);
      cases.put(org.bukkit.event.entity.EntityDamageEvent.DamageCause.SUFFOCATION,org.bukkit.damage.DamageType.IN_WALL);
      cases.put(org.bukkit.event.entity.EntityDamageEvent.DamageCause.DROWNING,org.bukkit.damage.DamageType.DROWN);
      cases.put(org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK,org.bukkit.damage.DamageType.GENERIC);
      for(var entry:cases.entrySet()) {
        var cause=entry.getKey();
        var event=new org.bukkit.event.entity.EntityDamageEvent(player,cause,org.bukkit.damage.DamageSource.builder(entry.getValue()).build(),4);
        Bukkit.getPluginManager().callEvent(event);
        sender.sendMessage(Component.text("WGPROTECT cause="+cause+" cancelled="+event.isCancelled()));
      }
    },()->{});
  }
  private void sample(CommandSender sender, int x, int z, int sy) {
    World world=Bukkit.getWorlds().getFirst();
    plugin.platform().region(world,x,z,()->{
      try {
        var raw=plugin.bridge().copy(world,x,z);
        int blockLight=world.getBlockAt(x*16+4,sy*16+4,z*16+4).getLightFromBlocks();
        int skyLight=world.getBlockAt(x*16+4,sy*16+5,z*16+4).getLightFromSky();
        plugin.platform().async(()->{
          try {
            var n=raw.terrain(); org.worldgit.core.model.Section section=org.worldgit.core.model.Section.air();
            for(var v:n.list("sections").values()) { var sec=(org.worldgit.core.anvil.Nbt.Compound)v; if(sec.integer("Y",0)==sy) section=org.worldgit.core.apply.ChunkNbt.blocks(sec); }
            var hash=java.security.MessageDigest.getInstance("SHA-256");
            for(int i=0;i<4096;i++) hash.update((section.block(i).canonical()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            sender.sendMessage(Component.text("WGSAMPLE x="+x+" z="+z+" sy="+sy+" hash="+HexFormat.of().formatHex(hash.digest())+" blockLight="+blockLight+" skyLight="+skyLight));
          } catch(Exception e) { sender.sendMessage(Component.text("WGSAMPLE failed "+e)); }
        });
      } catch(Exception e) { sender.sendMessage(Component.text("WGSAMPLE failed "+e)); }
    });
  }

  /** 以主世界出生點為中心，在 side×side 個 chunk 各放一格方塊（不觸發任何 Bukkit 事件，只靠旗標普查偵測）。 */
  private void fill(CommandSender sender, int side, String block, int total, int offset) {
    Material material = Objects.requireNonNull(Material.matchMaterial(block), "方塊名稱無效");
    World world = Bukkit.getWorlds().getFirst();
    ticketWorld = world;
    int cx0 = (world.getSpawnLocation().getBlockX() >> 4) - side / 2, cz0 = (world.getSpawnLocation().getBlockZ() >> 4) - side / 2;
    if (total < 1 || total > side * side) throw new IllegalArgumentException("chunk 數必須是 1–邊長平方");
    var done = new AtomicInteger();
    var next = new AtomicInteger();
    var inflight = new AtomicInteger();
    long t0 = System.nanoTime();
    int y = world.getMinHeight() + 8;
    if(offset<0 || offset>15) throw new IllegalArgumentException("local X 必須為 0..15");
    Runnable[] pump = new Runnable[1];
    pump[0] =
        () -> {
          while (inflight.get() < 24) {
            int i = next.getAndIncrement();
            if (i >= total) return;
            int cx = cx0 + i % side, cz = cz0 + i / side;
            inflight.incrementAndGet();
            world.getChunkAtAsync(cx, cz, true).whenComplete((chunk, error) -> {
              if (error != null) {
                plugin.getLogger().log(Level.WARNING, "debug fill 載入 chunk 失敗", error);
                inflight.decrementAndGet();
                pump[0].run();
                return;
              }
              plugin.platform().region(world, cx, cz, () -> {
                world.addPluginChunkTicket(cx, cz, plugin);
                tickets.add(new long[] {cx, cz});
                world.getBlockAt(cx * 16 + offset, y, cz * 16 + 8).setType(material, false);
                inflight.decrementAndGet();
                if (done.incrementAndGet() == total) sender.sendMessage(Component.text("debug fill 完成：" + total + " chunk，耗時 " + (System.nanoTime() - t0) / 1_000_000 + " ms"));
                pump[0].run();
              });
            });
          }
        };
    pump[0].run();
    sender.sendMessage(Component.text("debug fill 開始：" + total + " chunk"));
  }

  private void release(CommandSender sender) {
    World world = ticketWorld;
    if (world == null) return;
    int n = 0;
    synchronized (tickets) {
      for (long[] t : tickets) {
        int cx = (int) t[0], cz = (int) t[1];
        plugin.platform().region(world, cx, cz, () -> world.removePluginChunkTicket(cx, cz, plugin));
        n++;
      }
      tickets.clear();
    }
    sender.sendMessage(Component.text("已釋放 " + n + " 個 chunk ticket"));
  }

  private void probe(CommandSender sender, boolean start) {
    if (start) {
      gaps.clear();
      probing = true;
      lastTick = System.nanoTime();
      World world = Bukkit.getWorlds().getFirst();
      int cx = world.getSpawnLocation().getBlockX() >> 4, cz = world.getSpawnLocation().getBlockZ() >> 4;
      loop(world, cx, cz);
      sender.sendMessage(Component.text("probe 開始"));
    } else {
      probing = false;
      var list = new ArrayList<>(gaps);
      Collections.sort(list);
      if (list.isEmpty()) {
        sender.sendMessage(Component.text("probe：沒有資料"));
        return;
      }
      double mean = list.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
      long over100 = list.stream().filter(g -> g > 100_000_000L).count();
      long over200 = list.stream().filter(g -> g > 200_000_000L).count();
      sender.sendMessage(Component.text(String.format(
          "probe ticks=%d meanGapMs=%.2f p50=%.1f p95=%.1f p99=%.1f maxMs=%.1f gapsOver100ms=%d gapsOver200ms=%d tpsEstimate=%.2f",
          list.size(), mean, list.get((int) (list.size() * 0.5)) / 1e6, list.get((int) (list.size() * 0.95)) / 1e6,
          list.get(Math.min(list.size() - 1, (int) (list.size() * 0.99))) / 1e6, list.getLast() / 1e6, over100, over200, 1000.0 / mean)));
    }
  }

  /** 每 tick 在出生點所屬的 region 執行緒上執行一次，記錄與上一次的間隔：tick 被阻塞時間隔會拉長。 */
  private void loop(World world, int cx, int cz) {
    if (!probing) return;
    long now = System.nanoTime();
    gaps.add(now - lastTick);
    lastTick = now;
    plugin.platform().regionDelayed(world, cx, cz, 1, () -> loop(world, cx, cz));
  }
}
