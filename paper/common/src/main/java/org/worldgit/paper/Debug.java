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
      case "fill" -> fill(sender, args);
      case "release" -> release(sender);
      case "probe" -> probe(sender, args);
      case "measurements" -> plugin.repo().measurements().forEach(m -> sender.sendMessage(Component.text(m.toString())));
      default -> throw new IllegalArgumentException("未知的 debug 子指令");
    }
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
