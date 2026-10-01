package org.worldgit.paper;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.core.model.CommitMetadata;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.service.DimensionRepository;
import org.worldgit.core.service.WorldRepositories;
import org.worldgit.i18n.MessageCatalog;

/**
 * WorldGit Paper/Folia 插件。同一個 jar 跑在 Paper 1.21.11、26.2 與 Folia 兩版：
 * 版本無關的邏輯都在 paper:common，需要 NMS 的三件事由 {@link NmsBridge} 轉接層提供，啟動時依版本載入。
 */
public final class WorldGitPlugin extends JavaPlugin implements Listener {
  private PluginSettings settings;
  private Platform platform;
  private NmsBridge bridge;
  private RepoService repo;
  private FabricLink fabric;
  private AutoCommit autoCommit;
  private final Attribution attribution = new Attribution();
  private final ConcurrentMap<DimensionId, DimensionState> states = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, DimensionId> dimensionByWorld = new ConcurrentHashMap<>();
  private volatile List<World> worlds = List.of();
  private volatile boolean enabledOk;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    try {
      settings = PluginSettings.from(getConfig());
    } catch (IllegalArgumentException e) {
      getLogger().severe("config.yml 無效：" + e.getMessage());
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    Messages.configure(MessageCatalog.withOverrides(getDataFolder().toPath().resolve("lang")), settings.language());
    try { java.nio.file.Files.createDirectories(getDataFolder().toPath().resolve("lang")); }
    catch (IOException e) { getLogger().warning("無法建立語言覆寫目錄：" + e.getMessage()); }
    platform = new Platform(this);
    try {
      bridge = NmsBridges.select(Bukkit.getMinecraftVersion(), getClassLoader());
    } catch (RuntimeException e) {
      getLogger().severe(e.getMessage());
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    repo = new RepoService(this);
    refreshWorlds();
    getServer().getPluginManager().registerEvents(new ChangeListener(this), this);
    getServer().getPluginManager().registerEvents(this, this);
    fabric = new FabricLink(this);
    fabric.register();
    PluginCommand command = Objects.requireNonNull(getCommand("wg"), "plugin.yml 缺少 wg 指令");
    var commands = new Commands(this);
    command.setExecutor(commands);
    command.setTabCompleter(commands);
    hookWorldEdit();
    platform.async(() -> {
      try {
        fabric.palette(Messages.palette(repo.readLocal().palette()));
      } catch (IOException | RuntimeException e) {
        getLogger().warning("讀取 worldgit.yml 失敗，使用預設色票：" + e.getMessage());
      }
    });
    autoCommit = new AutoCommit(this);
    autoCommit.start();
    platform.asyncRepeating(5, settings.pollIntervalTicks() * 50L, this::poll);
    enabledOk = true;
    getLogger().info(
        "WorldGit 已啟用：Minecraft " + bridge.minecraftVersion() + "、" + (platform.folia() ? "Folia" : "Paper")
            + "、Java " + Runtime.version().feature() + "、追蹤世界 " + states.keySet());
  }

  @Override
  public void onDisable() {
    if (!enabledOk) return;
    enabledOk = false;
    platform.shuttingDown();
    if (autoCommit != null) autoCommit.shutdown();
    repo.awaitIdle(); // 等待進行中的背景操作；之後才能在目前執行緒內聯 commit（repo lock 同一時間只有一個持有者）
    if (settings.autoOnShutdown()) shutdownCommit();
    repo.close();
  }

  private void shutdownCommit() {
    if (platform.folia()) {
      getLogger().warning("Folia 上關閉時不執行自動 commit（沒有單一擁有執行緒）；請依賴定時與登出的存檔點。");
      return;
    }
    try {
      var batch =
          repo.commitInline(
              new RepoService.CommitRequest("伺服器關閉前自動存檔點", serverIdentity(), true, status -> true));
      batch.dimensions().forEach((id, o) -> {
        if (!o.success()) getLogger().warning("關閉前 commit " + id + " 失敗：" + o.error());
        else if (o.value().changed()) getLogger().info("關閉前 commit " + id + " → " + o.value().commit().substring(0, 8));
      });
    } catch (IOException | RuntimeException e) {
      // 世界尚未 init 時是正常情況
      getLogger().log(Level.FINE, "關閉前 commit 略過：" + e.getMessage());
    }
  }

  // ---------------------------------------------------------------- 存取

  PluginSettings settings() {
    return settings;
  }

  Platform platform() {
    return platform;
  }

  NmsBridge bridge() {
    return bridge;
  }

  RepoService repo() {
    return repo;
  }

  Attribution attribution() {
    return attribution;
  }

  FabricLink fabric() {
    return fabric;
  }

  boolean hasDirty() {
    return states.values().stream().anyMatch(s -> !s.dirty().chunks().isEmpty());
  }

  /** commit 完成後通知有權限的線上玩家（自動 commit 時顯示一行摘要）。 */
  void notifyCommit(WorldRepositories.Batch<DimensionRepository.CommitResult> batch, boolean auto) {
    var component = Messages.inLocale(getServer().getConsoleSender(), () -> Messages.commitSummary(batch, auto));
    if (component == null) return;
    getServer().getConsoleSender().sendMessage(component);
    for (Player p : getServer().getOnlinePlayers())
      if (p.hasPermission("worldgit.notify")) platform.entity(p, () -> Messages.inLocale(p, () -> p.sendMessage(Messages.commitSummary(batch, auto))), () -> {});
  }

  CommitMetadata.Identity serverIdentity() {
    return new CommitMetadata.Identity(settings.serverName(), settings.serverEmail());
  }

  DimensionState state(DimensionId id, World world) {
    var existing = states.get(id);
    if (existing != null) return existing;
    return states.computeIfAbsent(id, k -> new DimensionState(id, Objects.requireNonNull(world, "世界尚未登錄：" + id)));
  }

  Optional<DimensionId> dimensionOf(World world) {
    return Optional.ofNullable(dimensionByWorld.get(world.getUID()));
  }

  // ---------------------------------------------------------------- 世界與標記

  /** 重建世界對應與狀態；啟動及世界載入/卸載時呼叫（只在全域/主執行緒讀 Bukkit 世界清單）。 */
  private void refreshWorlds() {
    try {
      var mapping = WorldMapper.map();
      var map = new HashMap<UUID, DimensionId>();
      mapping.worlds().forEach((id, world) -> {
        map.put(world.getUID(), id);
        state(id, world);
      });
      dimensionByWorld.clear();
      dimensionByWorld.putAll(map);
      worlds = List.copyOf(mapping.worlds().values());
      if (!mapping.unsupported().isEmpty())
        getLogger().warning("下列世界在磁碟上找不到對應的維度目錄，不會被追蹤：" + mapping.unsupported());
    } catch (IOException e) {
      getLogger().log(Level.WARNING, "無法判斷世界版面：" + e.getMessage());
    }
  }

  @EventHandler
  public void onWorldLoad(WorldLoadEvent e) {
    refreshWorlds();
  }

  @EventHandler
  public void onWorldUnload(WorldUnloadEvent e) {
    refreshWorlds();
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent e) {
    if (autoCommit != null) autoCommit.onQuit(e.getPlayer().getName());
  }

  /** dirty 標記＋作者歸屬（事件與 WorldEdit 的共同入口）。player 可為 null（來源不明或非玩家）。 */
  void touch(World world, int chunkX, int chunkZ, Player player, String cause) {
    DimensionId id = dimensionByWorld.get(world.getUID());
    if (id == null) return;
    var pos = new ChunkPos(chunkX, chunkZ);
    state(id, world).markEvent(pos);
    if (player != null) attribution.record(id, player.getUniqueId(), player.getName(), pos, cause);
  }

  void touchByName(String worldName, int chunkX, int chunkZ, UUID player, String playerName, String cause) {
    World world = Bukkit.getWorld(worldName);
    if (world == null) return;
    DimensionId id = dimensionByWorld.get(world.getUID());
    if (id == null) return;
    var pos = new ChunkPos(chunkX, chunkZ);
    state(id, world).markEvent(pos);
    if (player != null) attribution.record(id, player, playerName == null ? player.toString() : playerName, pos, cause);
  }

  /** 背景普查：讀 unsaved 旗標（volatile）與 chunk holder，不進入任何 region 執行緒。 */
  private void poll() {
    for (World world : worlds) {
      DimensionId id = dimensionByWorld.get(world.getUID());
      if (id == null) continue;
      try {
        state(id, world).refresh(bridge, world);
      } catch (Throwable t) {
        getLogger().log(Level.WARNING, "普查 " + world.getName() + " 失敗", t);
      }
    }
  }

  private void hookWorldEdit() {
    var manager = getServer().getPluginManager();
    boolean fawe = manager.getPlugin("FastAsyncWorldEdit") != null;
    boolean we = manager.getPlugin("WorldEdit") != null;
    if (!fawe && !we) return;
    try {
      WorldEditHook.install(this, fawe);
      getLogger().info("已掛接 " + (fawe ? "FastAsyncWorldEdit" : "WorldEdit") + " 的 EditSessionEvent（作者歸屬與 chunk 標記）");
      if (fawe)
        getLogger().info("FAWE 預設會丟掉第三方 Extent：請在 FAWE config.yml 的 extent.allowed-plugins 加入 org.worldgit.paper，否則 WorldEdit 作者歸屬無法記錄（變動仍由旗標普查偵測）。");
    } catch (Throwable t) {
      getLogger().log(Level.WARNING, "WorldEdit 掛接失敗，改用旗標普查偵測：" + t);
    }
  }

  /** 所有玩家所在維度的目前 chunk 視窗（供 /wg diff 使用）。 */
  static Set<ChunkPos> window(Player player, int radius) {
    int cx = player.getLocation().getBlockX() >> 4, cz = player.getLocation().getBlockZ() >> 4;
    var set = new TreeSet<ChunkPos>();
    for (int x = cx - radius; x <= cx + radius; x++) for (int z = cz - radius; z <= cz + radius; z++) set.add(new ChunkPos(x, z));
    return set;
  }
}
