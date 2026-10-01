package org.worldgit.paper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.worldgit.core.diff.ChangeKind;
import org.worldgit.core.diff.WorldDiff;
import org.worldgit.core.model.BlockState;
import org.worldgit.protocol.DiffPalette;

/**
 * 沒裝 Fabric 模組的玩家的預覽 fallback（docs/06 §遊戲內、docs/08 §6）：用 {@link BlockDisplay} 發光描邊。
 *
 * <ul>
 *   <li>只對請求的玩家可見：實體 {@code visibleByDefault=false}，再 {@link Player#showEntity}（Paper per-player entity visibility）。
 *   <li>不持久化（{@code persistent=false}）並帶 {@value #TAG} 標籤；不進世界存檔，也不被 WorldGit 的實體快照記錄。
 *   <li>數量上限（{@code show.display-max-entities}）：方塊級 diff 超過上限時改畫區域包圍盒（每個 section 12 條細邊）。
 *   <li>{@code show.display-seconds} 秒後自動移除；{@code /wg clear}、玩家登出、插件停用也會移除。
 * </ul>
 */
final class DisplayFallback {
  static final String TAG = "worldgit_preview";

  record Shown(int entities, boolean boxes, int omitted) {}

  private final WorldGitPlugin plugin;
  private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();

  private static final class Session {
    final long token = System.nanoTime();
    final List<Entity> entities = Collections.synchronizedList(new ArrayList<>());
  }

  DisplayFallback(WorldGitPlugin plugin) {
    this.plugin = plugin;
  }

  private static Color color(DiffPalette palette, ChangeKind kind) {
    return Color.fromRGB(palette.rgb(kind) & 0xFFFFFF);
  }

  private static Material concrete(ChangeKind kind) {
    return switch (kind) {
      case ADDED -> Material.LIME_CONCRETE;
      case REMOVED -> Material.RED_CONCRETE;
      case MODIFIED -> Material.YELLOW_CONCRETE;
      case CONFLICT -> Material.PURPLE_CONCRETE;
    };
  }

  private static BlockData data(BlockState state) {
    try {
      return Bukkit.createBlockData(state.canonical());
    } catch (IllegalArgumentException e) {
      return Bukkit.createBlockData(Material.STONE);
    }
  }

  /** 逐格發光描邊：ADDED/MODIFIED 描 after，REMOVED 描 before（鬼影）。回傳實際顯示數量與被上限省略的數量。 */
  Shown showCells(Player player, WorldDiff diff, DiffPalette palette) {
    var session = begin(player);
    int max = plugin.settings().displayMaxEntities(), shown = 0, omitted = 0;
    var byChunk = new LinkedHashMap<Long, List<Runnable>>();
    World world = player.getWorld();
    for (var section : diff.sections())
      for (var b : section.blocks()) {
        if (shown >= max) { omitted++; continue; }
        shown++;
        int x = b.pos().x(), y = b.pos().y(), z = b.pos().z();
        var kind = b.kind();
        var state = kind == ChangeKind.REMOVED ? b.before() : b.after();
        byChunk.computeIfAbsent(chunkKey(x, z), k -> new ArrayList<>())
            .add(() -> spawn(player, session, new Location(world, x, y, z), data(state), color(palette, kind), 1.004f, 0f));
      }
    run(player, session, world, byChunk);
    return new Shown(shown, false, omitted);
  }

  /** 區域包圍盒：每個變動 section 以 12 條細邊描出（顏色依該 section 的主要變動種類）。 */
  Shown showBoxes(Player player, WorldDiff diff, DiffPalette palette) {
    var session = begin(player);
    int max = plugin.settings().displayMaxEntities(), perBox = 12, shown = 0, omitted = 0;
    var byChunk = new LinkedHashMap<Long, List<Runnable>>();
    World world = player.getWorld();
    for (var section : diff.sections()) {
      if (shown + perBox > max) { omitted++; continue; }
      shown += perBox;
      var c = section.counts();
      ChangeKind kind = c.removed() > c.added() && c.removed() >= c.modified() ? ChangeKind.REMOVED : c.modified() > c.added() ? ChangeKind.MODIFIED : ChangeKind.ADDED;
      int x0 = section.chunk().x() * 16, z0 = section.chunk().z() * 16, y0 = section.sectionY() * 16;
      var bd = Bukkit.createBlockData(concrete(kind));
      var col = color(palette, kind);
      byChunk.computeIfAbsent(chunkKey(x0, z0), k -> new ArrayList<>()).add(() -> {
        float t = 0.12f;
        for (int axis = 0; axis < 3; axis++)
          for (int i = 0; i < 4; i++) {
            float far = 16 - t, lo = (i & 1) * far, hi = (i >> 1) * far;
            float ox = axis == 0 ? 0 : lo, oy = axis == 1 ? 0 : (axis == 0 ? lo : hi), oz = axis == 2 ? 0 : hi;
            // 每條邊：沿 axis 長 16，其餘兩軸厚 t
            var scale = new Vector3f(axis == 0 ? 16 : t, axis == 1 ? 16 : t, axis == 2 ? 16 : t);
            spawnScaled(player, session, new Location(world, x0, y0, z0), bd, col, new Vector3f(ox, oy, oz), scale);
          }
      });
    }
    run(player, session, world, byChunk);
    return new Shown(shown, true, omitted);
  }

  private Session begin(Player player) {
    clear(player);
    var session = new Session();
    sessions.put(player.getUniqueId(), session);
    long seconds = plugin.settings().displaySeconds();
    plugin.platform().asyncDelayed(seconds, () -> {
      var current = sessions.get(player.getUniqueId());
      if (current != null && current.token == session.token) clear(player);
    });
    return session;
  }

  private static long chunkKey(int x, int z) {
    return ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
  }

  private void run(Player player, Session session, World world, Map<Long, List<Runnable>> byChunk) {
    byChunk.forEach((key, tasks) -> {
      int cx = (int) (key >> 32), cz = (int) (long) key;
      plugin.platform().region(world, cx, cz, () -> {
        for (Runnable r : tasks)
          try {
            r.run();
          } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "display fallback 建立實體失敗", e);
            break;
          }
      });
    });
  }

  private void spawn(Player player, Session session, Location at, BlockData data, Color glow, float scale, float unused) {
    float off = -(scale - 1f) / 2f;
    spawnScaled(player, session, at, data, glow, new Vector3f(off, off, off), new Vector3f(scale, scale, scale));
  }

  private void spawnScaled(Player player, Session session, Location at, BlockData data, Color glow, Vector3f translation, Vector3f scale) {
    var display =
        at.getWorld().spawn(at, BlockDisplay.class, e -> {
          e.setVisibleByDefault(false);
          e.setPersistent(false);
          e.addScoreboardTag(TAG);
          e.setBlock(data);
          e.setTransformation(new Transformation(translation, new AxisAngle4f(), scale, new AxisAngle4f()));
          e.setGlowing(true);
          e.setGlowColorOverride(glow);
          e.setBrightness(new Display.Brightness(15, 15));
          e.setViewRange(1.0f);
          e.setShadowRadius(0f);
          e.setInvulnerable(true);
        });
    session.entities.add(display);
    // showEntity 屬於玩家：在玩家所屬的執行緒呼叫（Paper 為主執行緒；Folia 為玩家的 region）。
    plugin.platform().entity(player, () -> {
      if (player.isOnline() && sessions.get(player.getUniqueId()) == session) player.showEntity(plugin, display);
    }, () -> {});
  }

  /** /wg clear、登出：移除這位玩家的所有預覽實體。 */
  void clear(Player player) {
    var session = sessions.remove(player.getUniqueId());
    if (session != null) remove(session);
  }

  int count(Player player) {
    var session = sessions.get(player.getUniqueId());
    return session == null ? 0 : session.entities.size();
  }

  private void remove(Session session) {
    List<Entity> list;
    synchronized (session.entities) {
      list = new ArrayList<>(session.entities);
      session.entities.clear();
    }
    for (Entity e : list)
      try {
        plugin.platform().entity(e, e::remove, () -> {});
      } catch (RuntimeException ex) {
        // 插件停用中排程器可能已拒絕；實體不持久化，伺服器重啟後不會留下。
      }
  }

  /** 插件停用：同步移除（Paper 主執行緒）；Folia 盡力而為。 */
  void clearAll() {
    for (var s : new ArrayList<>(sessions.values())) {
      List<Entity> list;
      synchronized (s.entities) { list = new ArrayList<>(s.entities); s.entities.clear(); }
      for (Entity e : list) try { e.remove(); } catch (RuntimeException ignored) { }
    }
    sessions.clear();
  }
}
