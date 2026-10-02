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

  void run(CommandSender sender, String[] args) {
    if (args.length == 0) throw new IllegalArgumentException("debug fill <邊長> [方塊] | release | probe start|stop | measurements");
    switch (args[0]) {
      case "apply" -> {
        var q=plugin.repo().activeQueue();
        sender.sendMessage(Component.text(q==null ? "WGAPPLY idle" : "WGAPPLY sections="+q.sections.get()+" tickets="+q.tickets.get()+" peak="+q.peak.get()));
      }
      case "sample" -> sample(sender,args);
      case "entities" -> entities(sender,args);
      case "freeze" -> freeze(sender,args);
      case "protection" -> protection(sender,args);
      case "fill" -> fill(sender, args);
      case "release" -> release(sender);
      case "probe" -> probe(sender, args);
      case "measurements" -> plugin.repo().measurements().forEach(m -> sender.sendMessage(Component.text(m.toString())));
      default -> throw new IllegalArgumentException("未知的 debug 子指令");
    }
  }

  private AutoCloseable fixtureFreeze;
  /** Folia 沒有 /tick；驗收用同一套全域 freeze，操作期間的引用計數不會解除它。 */
  private void freeze(CommandSender sender,String[] args) {
    if(args.length!=2 || !Set.of("on","off").contains(args[1])) throw new IllegalArgumentException("debug freeze on|off");
    plugin.platform().global(()->{
      try {
        if(args[1].equals("on")) {
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
  private void entities(CommandSender sender,String[] args) {
    if(args.length!=5) throw new IllegalArgumentException("debug entities overworld|nether <cx> <cz> seed|solo|clear|inspect");
    var environment=switch(args[1]) { case "overworld"->World.Environment.NORMAL; case "nether"->World.Environment.NETHER; default->throw new IllegalArgumentException("維度無效"); };
    var world=Bukkit.getWorlds().stream().filter(w->w.getEnvironment()==environment).findFirst().orElseThrow();
    int cx=Integer.parseInt(args[2]),cz=Integer.parseInt(args[3]); String action=args[4];
    if(!Set.of("seed","solo","clear","inspect").contains(action)) throw new IllegalArgumentException("動作無效");
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

  private void protection(CommandSender sender,String[] args) {
    var player=Objects.requireNonNull(Bukkit.getPlayerExact(args[1]));
    plugin.platform().entity(player,()->{
      for(var cause:List.of(org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL,org.bukkit.event.entity.EntityDamageEvent.DamageCause.SUFFOCATION,org.bukkit.event.entity.EntityDamageEvent.DamageCause.DROWNING,org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK)) {
        var event=new org.bukkit.event.entity.EntityDamageEvent(player,cause,4);
        Bukkit.getPluginManager().callEvent(event);
        sender.sendMessage(Component.text("WGPROTECT cause="+cause+" cancelled="+event.isCancelled()));
      }
    },()->{});
  }
  private void sample(CommandSender sender,String[] args) {
    int x=Integer.parseInt(args[1]),z=Integer.parseInt(args[2]),sy=Integer.parseInt(args[3]);
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
  private void fill(CommandSender sender, String[] args) {
    int side = Integer.parseInt(args[1]);
    if (side < 1 || side > 128) throw new IllegalArgumentException("邊長必須是 1–128 chunk");
    Material material = args.length > 2 ? Objects.requireNonNull(Material.matchMaterial(args[2]), "方塊名稱無效") : Material.GOLD_BLOCK;
    World world = Bukkit.getWorlds().getFirst();
    ticketWorld = world;
    int cx0 = (world.getSpawnLocation().getBlockX() >> 4) - side / 2, cz0 = (world.getSpawnLocation().getBlockZ() >> 4) - side / 2;
    int total = args.length > 3 ? Integer.parseInt(args[3]) : side * side;
    if (total < 1 || total > side * side) throw new IllegalArgumentException("chunk 數必須是 1–邊長平方");
    var done = new AtomicInteger();
    var next = new AtomicInteger();
    var inflight = new AtomicInteger();
    long t0 = System.nanoTime();
    int y = world.getMinHeight() + 8;
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
                world.getBlockAt(cx * 16 + 8, y, cz * 16 + 8).setType(material, false);
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

  private void probe(CommandSender sender, String[] args) {
    if (args.length < 2) throw new IllegalArgumentException("debug probe start|stop");
    if (args[1].equals("start")) {
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
