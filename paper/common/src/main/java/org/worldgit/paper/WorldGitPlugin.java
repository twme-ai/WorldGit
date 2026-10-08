package org.worldgit.paper;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
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
  private EditGuard edits;
  private FabricLink fabric;
  private DisplayFallback displays;
  private MergeUi merges;
  private AutoCommit autoCommit;
  private RemoteCommands remote;
  private CommentDisplays comments;
  private CommandSuggestions suggestions;
  private OperationUi operations;
  private IgnoreUi ignore;
  private TouchedEntities touched;
  private volatile Map<UUID, WorldMapper.Mapping> mappings = Map.of();
  private boolean gitConflictLogged;
  private boolean gitRegistered;
  private org.worldgit.platform.remote.RemoteSettings remoteSettings;
  private OfflineShutdownCommit offlineShutdown;
  private final ConcurrentMap<UUID,Attribution> attributions = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, DimensionState> states = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, DimensionId> dimensionByWorld = new ConcurrentHashMap<>();
  private volatile List<World> worlds = List.of();
  private volatile boolean enabledOk;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    try {
      settings = PluginSettings.from(getConfig());
      remoteSettings = RemoteConfig.from(getConfig());
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
    edits = new EditGuard(this);
    getServer().getPluginManager().registerEvents(edits,this);
    operations = new OperationUi(this);
    Messages.ui = operations;
    repo = new RepoService(this);
    refreshWorlds();
    getServer().getPluginManager().registerEvents(new ChangeListener(this), this);
    getServer().getPluginManager().registerEvents(this, this);
    fabric = new FabricLink(this);
    fabric.register();
    displays = new DisplayFallback(this);
    merges = new MergeUi(this);
    comments = new CommentDisplays(this);
    remote = new RemoteCommands(this,remoteSettings);
    merges.refresh();
    ignore = new IgnoreUi(this);
    touched = new TouchedEntities(this);
    suggestions = CommandSuggestions.forPlugin(this);
    var commands = new Commands(this);
    getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
      var registrar = event.registrar();
      registrar.register(new CommandTree(commands, suggestions).build().build(), "WorldGit", List.of("worldgit"));
      registrar.register(new CommandTree(commands, suggestions).build("worldgit:git").build(), "WorldGit", List.of());
      if (getConfig().getBoolean("aliases.wgit", true)) registrar.register(new CommandTree(commands, suggestions).build("wgit").build(), "WorldGit", List.of());
      if (getConfig().getBoolean("aliases.git", true)) {
        if (registrar.getDispatcher().getRoot().getChild("git") == null) {
          registrar.register(new CommandTree(commands, suggestions).build("git").build(), "WorldGit", List.of());
          gitRegistered=true;
        }
        else if (!gitConflictLogged) { gitConflictLogged = true; getLogger().info("/git 已由其他插件註冊；請用 /wg 或 /worldgit:git"); }
      }
    });
    hookWorldEdit();
    hookAxiom();
    repo.submit(() -> {
      try {
        var root=mapping().layout().repositoryRoot();
        var journal=org.worldgit.core.service.OperationState.read(root.resolve("apply-state.yml"));
        if("APPLYING".equals(journal.get("state"))) {
          journal.put("state","PARTIAL"); journal.put("error","上次套用未完成，可能因插件停用或伺服器中斷");
          org.worldgit.core.service.OperationState.write(root.resolve("apply-state.yml"),journal);
        }
        if(org.worldgit.core.service.OperationState.partial(root)) getLogger().warning("世界為 PARTIAL；請用 /wg switch <目標> --force 或 /wg reset --hard 恢復，commit 已阻擋");
        fabric.palette(Messages.palette(repo.readLocal().palette()));
      } catch (IOException | RuntimeException e) {
        getLogger().warning("讀取 worldgit.yml 失敗，使用預設色票：" + e.getMessage());
      }
      return null;
    });
    if (platform.folia() && settings.autoOnShutdown()) {
      offlineShutdown = new OfflineShutdownCommit(this);
      offlineShutdown.register();
    }
    autoCommit = new AutoCommit(this);
    autoCommit.start();
    platform.asyncRepeating(5, settings.pollIntervalTicks() * 50L, this::poll);
    enabledOk = true;
    try { remote.start(); } catch(IOException e) {
      getLogger().severe("遠端通知啟動失敗（請檢查 bind、port、secret 權限）");
      getServer().getPluginManager().disablePlugin(this); return;
    }

    getLogger().info(
        "WorldGit 已啟用：Minecraft " + bridge.minecraftVersion() + "、" + (platform.folia() ? "Folia" : "Paper")
            + "、Java " + Runtime.version().feature() + "、追蹤世界 " + states.keySet());
  }

  @Override
  public void onDisable() {
    if (!enabledOk) return;
    enabledOk = false;
    if(operations!=null) operations.close();
    if(suggestions!=null) suggestions.close();
    if(remote!=null) remote.close();
    if(comments!=null) comments.shutdown();
    boolean wasApplying=repo.applying();
    repo.shutdownApply();
    edits.shutdown();
    if(merges!=null) merges.shutdown();
    if (displays != null) displays.clearAll();
    platform.shuttingDown();
    if (autoCommit != null) autoCommit.shutdown();
    if(!wasApplying) repo.awaitIdle(); // 等待進行中的背景操作；之後才能在目前執行緒內聯 commit（repo lock 同一時間只有一個持有者）
    if (!wasApplying && settings.autoOnShutdown()) shutdownCommit();
    if (offlineShutdown != null) offlineShutdown.prepare(!wasApplying && settings.autoOnShutdown());
    repo.close();
  }

  private void shutdownCommit() {
    if (platform.folia()) {
      // Folia：disable 時沒有單一擁有執行緒；改由 OfflineShutdownCommit 在世界存檔完成後以離線路徑提交（JVM 關閉鉤子）。
      return;
    }
    for(var mapping:mappings()) {
    var action=operations.begin(getServer().getConsoleSender(),"shutdown.commit",mapping,null);
    var last=new java.util.concurrent.atomic.AtomicLong();
    var finished=new java.util.concurrent.atomic.AtomicBoolean();
    OperationUi.within(action,()->{try(var progress=new org.worldgit.core.operation.OperationProgress(action.id,action.operation,event->{
      long now=System.currentTimeMillis();
      if(!finished.get() && now-last.get()>=1000) {
        last.set(now);
        getLogger().info(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(OperationUi.progressText(event)));
      }
    })) {
      if(new org.worldgit.core.service.WorldRepositories(mapping.layout()).tracked().isEmpty()) {
        action.status=org.worldgit.core.operation.OperationResult.Status.NO_OP;
        return null;
      }
      var batch =
          repo.commitInline(
              new RepoService.CommitRequest("伺服器關閉前自動存檔點", serverIdentity(), true, status -> true));
      batch.dimensions().forEach((id, o) -> {
        if (!o.success()) getLogger().warning("關閉前 commit " + id + " 失敗：" + o.error());
        else if (o.value().changed()) getLogger().info("關閉前 commit " + id + " → " + o.value().commit().substring(0, 8));
      });
      action.observe(batch);
    } catch (IOException | RuntimeException e) {
      // 世界尚未 init 時是正常情況
      getLogger().log(Level.FINE, "關閉前 commit 略過：" + e.getMessage());
      action.failed(e);
    } finally {
      finished.set(true);
      var result=new org.worldgit.core.operation.OperationResult(action.id,action.operation,action.status,null,action.summary,(System.nanoTime()-action.started)/1_000_000,List.of(),action.error);
      getServer().getConsoleSender().sendMessage(operations.completion(result));
      if(action.error!=null)getLogger().warning(action.error.text().replace("\n"," | "));
      action.release();
    }return null;});
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

  EditGuard edits() { return edits; }

  /** 第三方插件在寫入前可查詢（WE/FAWE 也使用同一政策）。 */
  public boolean isEditLocked(World world) { return edits!=null && edits.locked(world); }

  RepoService repo() {
    return repo;
  }

  Attribution attribution() {
    try {return attribution(mapping());}catch(IOException error){throw new IllegalStateException(error);}
  }
  Attribution attribution(WorldMapper.Mapping mapping) {
    var world=mapping.worlds().getOrDefault(DimensionId.OVERWORLD,mapping.worlds().values().iterator().next());
    return attributions.computeIfAbsent(world.getUID(),id->new Attribution());
  }
  boolean hasAttribution() {return attributions.values().stream().anyMatch(a->!a.peek().empty());}

  FabricLink fabric() {
    return fabric;
  }

  MergeUi merges() { return merges; }
  RemoteCommands remote() { return remote; }
  CommandSuggestions suggestions() { return suggestions; }
  CommentDisplays comments() { return comments; }
  World world(DimensionId dimension) {
    return worlds.stream().filter(w->dimension.equals(dimensionByWorld.get(w.getUID()))).findFirst().orElse(null);
  }

  DisplayFallback displays() {
    return displays;
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
    World target = world == null ? world(id) : world;
    return states.computeIfAbsent(Objects.requireNonNull(target).getUID(), k -> new DimensionState(id, target));
  }

  OperationUi operations() { return operations; }
  IgnoreUi ignore() { return ignore; }
  TouchedEntities touched() { return touched; }
  WorldMapper.Mapping mapping() throws IOException {
    var action = OperationUi.current();
    if (action != null && action.mapping != null) return action.mapping;
    World first = getServer().getWorlds().getFirst();
    var mapping = mappings.get(first.getUID());
    if (mapping == null) throw new IOException("世界尚未登錄");
    return mapping;
  }
  WorldMapper.Mapping mapping(World world) { return mappings.get(world.getUID()); }
  Collection<WorldMapper.Mapping> mappings() {return mappings.values().stream().distinct().toList();}

  Optional<DimensionId> dimensionOf(World world) {
    return Optional.ofNullable(dimensionByWorld.get(world.getUID()));
  }

  // ---------------------------------------------------------------- 世界與標記

  /** 重建世界對應與狀態；啟動及世界載入/卸載時呼叫（只在全域/主執行緒讀 Bukkit 世界清單）。 */
  private void refreshWorlds() {
    try {
      var map = new HashMap<UUID, DimensionId>();
      var groups = new HashMap<UUID, WorldMapper.Mapping>();
      for (World anchor : getServer().getWorlds()) {
        if (groups.containsKey(anchor.getUID())) continue;
        var mapping = WorldMapper.map(anchor);
        mapping.worlds().forEach((id, world) -> {
          map.put(world.getUID(), id); groups.put(world.getUID(), mapping); state(id, world);
        });
      }
      mappings = Map.copyOf(groups);
      dimensionByWorld.clear();
      dimensionByWorld.putAll(map);
      worlds = getServer().getWorlds().stream().filter(w -> map.containsKey(w.getUID())).toList();
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
    if (displays != null) displays.clear(e.getPlayer());
    if (remote != null) remote.quit(e.getPlayer());
    if (autoCommit != null) autoCommit.onQuit(e.getPlayer().getName());
  }

  /** requires 會隱藏未授權節點；直接輸入舊入口時仍保留既有的 i18n 權限訊息。僅檢查入口，參數全由 Brigadier 解析。 */
  @EventHandler(ignoreCancelled = true)
  public void onCommandPermission(org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
    String[] words = event.getMessage().split(" +", 3);
    if (words.length < 2 || !Set.of("/wg", "/worldgit", "/worldgit:wg", "/worldgit:worldgit", "/git", "/wgit", "/worldgit:git", "/worldgit:wgit").contains(words[0].toLowerCase(Locale.ROOT))) return;
    if(words[0].equalsIgnoreCase("/git") && !gitRegistered)return;
    String sub = words[1].toLowerCase(Locale.ROOT);
    var player = event.getPlayer();
    if (!CommandTree.SUBS.contains(sub) || sub.equals("help")) return;
    boolean allowed = sub.equals("debug") ? player.hasPermission("worldgit.debug")
        : player.hasPermission("worldgit.command." + CommandTree.permission(sub)) || player.hasPermission("worldgit.admin");
    if (!allowed) {
      event.setCancelled(true);
      var action=operations.begin(player,sub,mapping(player.getWorld()),dimensionOf(player.getWorld()).orElse(null));
      OperationUi.within(action,()->{Messages.inLocale(player,()->player.sendMessage(sub.equals("debug")?Messages.debugPermission():Messages.permission(CommandTree.permission(sub))));action.status=org.worldgit.core.operation.OperationResult.Status.FAILED;action.release();return null;});
    }
  }

  /** dirty 標記＋作者歸屬（事件與 WorldEdit 的共同入口）。player 可為 null（來源不明或非玩家）。 */
  void touch(World world, int chunkX, int chunkZ, Player player, String cause) {
    DimensionId id = dimensionByWorld.get(world.getUID());
    if (id == null) return;
    var pos = new ChunkPos(chunkX, chunkZ);
    state(id, world).markEvent(pos);
    if (player != null) attribution(mapping(world)).record(id, player.getUniqueId(), player.getName(), pos, cause);
  }

  void touchByName(String worldName, int chunkX, int chunkZ, UUID player, String playerName, String cause) {
    World world = Bukkit.getWorld(worldName);
    if (world == null) return;
    DimensionId id = dimensionByWorld.get(world.getUID());
    if (id == null) return;
    var pos = new ChunkPos(chunkX, chunkZ);
    state(id, world).markEvent(pos);
    if (player != null) attribution(mapping(world)).record(id, player, playerName == null ? player.toString() : playerName, pos, cause);
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

  private java.util.function.Consumer<World> axiomBarrier = world -> {};
  void axiomBarrier(java.util.function.Consumer<World> barrier) { axiomBarrier = barrier; }
  void quiesceAxiom(World world) { axiomBarrier.accept(world); }
  private void hookAxiom() {
    if (platform.folia()) return;
    var axiom = getServer().getPluginManager().getPlugin("AxiomPaper");
    if (axiom == null || !axiom.isEnabled()) return;
    try {
      AxiomHook.install(this, axiom);
      getLogger().info("已掛接 AxiomPaper（編輯鎖、作者歸屬、觸及實體）");
    } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
      // No unsafe fallback: direct section writes and queued bypass edits escape ordinary guards.
      axiomBarrier = world -> { if (axiom.isEnabled()) throw new IllegalStateException("Unsupported AxiomPaper API; WorldGit apply refused", error); };
      getLogger().warning("AxiomPaper API 不相容；停用 AxiomPaper 後才能套用：" + error);
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
