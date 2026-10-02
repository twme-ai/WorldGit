package org.worldgit.paper;

import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.IllegalPluginAccessException;

/**
 * 只使用 Paper/Folia 都有的排程器（Region／Global／Entity／Async）；Paper 是「只有一個 region」的特例。
 * BukkitScheduler 在 Folia 不能用，所以整個插件不得直接呼叫它。
 */
public final class Platform implements ChunkScheduler {
  private final Plugin plugin;
  private final boolean folia;
  private volatile boolean shuttingDown;

  public Platform(Plugin plugin) {
    this.plugin = plugin;
    boolean f;
    try {
      Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
      f = true;
    } catch (ClassNotFoundException e) {
      f = false;
    }
    folia = f;
  }

  public boolean folia() {
    return folia;
  }

  /** 關閉流程：排程器此時會拒絕新工作，改為在擁有該 chunk 的目前執行緒內聯執行。 */
  public void shuttingDown() {
    shuttingDown = true;
  }

  /** 在擁有該 chunk 的執行緒上執行（Paper 為主執行緒；已在擁有者執行緒時仍排程，避免重入）。 */
  public void region(World world, int chunkX, int chunkZ, Runnable task) {
    if (shuttingDown) {
      if (Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)) task.run();
      else throw new IllegalStateException("關閉中，無法排程到 region " + chunkX + "," + chunkZ);
      return;
    }
    Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, task);
  }

  public void regionDelayed(World world, int chunkX, int chunkZ, long ticks, Runnable task) {
    Bukkit.getRegionScheduler()
        .runDelayed(plugin, world, chunkX, chunkZ, t -> task.run(), Math.max(1, ticks));
  }

  public void global(Runnable task) {
    Bukkit.getGlobalRegionScheduler().execute(plugin, task);
  }

  public void globalDelayed(long ticks, Runnable task) {
    Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), Math.max(1, ticks));
  }

  /** 在實體（玩家）所屬的執行緒執行；實體已移除時 retired 會被呼叫。 */
  public void entity(Entity entity, Runnable task, Runnable retired) {
    if (shuttingDown || !plugin.isEnabled()) {
      if (retired != null) retired.run();
      return;
    }
    try {
      entity.getScheduler().run(plugin, t -> task.run(), retired);
    } catch (IllegalPluginAccessException e) {
      // isEnabled 的檢查與 scheduler 註冊之間仍可能開始 disable。
      if (!shuttingDown && plugin.isEnabled()) throw e;
      if (retired != null) retired.run();
    }
  }

  public void entityDelayed(Entity entity, long ticks, Runnable task, Runnable retired) {
    entity.getScheduler().runDelayed(plugin, t -> task.run(), retired, Math.max(1, ticks));
  }

  public void async(Runnable task) {
    Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
  }

  public void asyncDelayed(long seconds, Runnable task) {
    Bukkit.getAsyncScheduler().runDelayed(plugin, t -> task.run(), seconds, TimeUnit.SECONDS);
  }

  public void asyncRepeating(long initialSeconds, long periodMillis, Runnable task) {
    Bukkit.getAsyncScheduler()
        .runAtFixedRate(
            plugin, t -> task.run(), initialSeconds * 1000, periodMillis, TimeUnit.MILLISECONDS);
  }

  public boolean ownedHere(World world, int chunkX, int chunkZ) {
    return Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);
  }
}
