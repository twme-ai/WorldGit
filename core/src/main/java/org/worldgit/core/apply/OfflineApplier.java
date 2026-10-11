package org.worldgit.core.apply;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.worldgit.core.anvil.*;
import org.worldgit.core.config.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;

/** 僅限持有 session.lock 的離線寫回；一次僅開一個 region writer。操作狀態由 WorldOperations 管理。 */
public final class OfflineApplier {
  private final WorldLayout layout;
  private final EntityTagRegistry.PackResolver packs;
  private final org.worldgit.core.version.NbtUpgrader suppliedDfu;

  public OfflineApplier(WorldLayout layout) {
    this(layout, null);
  }

  public OfflineApplier(WorldLayout layout, EntityTagRegistry.PackResolver packs) {
    this(layout, packs, null);
  }

  OfflineApplier(WorldLayout layout, EntityTagRegistry.PackResolver packs,
      org.worldgit.core.version.NbtUpgrader suppliedDfu) {
    this.layout = layout;
    this.packs = packs;
    this.suppliedDfu = suppliedDfu;
  }

  /** 原版只在載入 chunk 時升級；patch 前也必須升級尚未載入的整筆資料。 */
  private final class DiskUpgrade implements AutoCloseable {
    private org.worldgit.core.version.NbtUpgrader dfu = suppliedDfu;
    private final int target;

    DiskUpgrade(int target) { this.target = target; }

    Nbt.Compound upgrade(String type, Nbt.Compound raw) throws IOException {
      if (raw == null) return null;
      int from = raw.integer("DataVersion", target);
      if (from == target) return raw;
      if (from > target) throw new IOException("磁碟 DataVersion 比目標世界新；請先完整降級世界");
      if (dfu == null) dfu = new org.worldgit.core.version.DfuProcess(
          org.worldgit.core.version.MinecraftVersion.byDataVersion(target));
      var upgraded = dfu.upgrade(type, raw, from, target);
      upgraded.put("DataVersion", target);
      return upgraded;
    }

    @Override public void close() throws IOException {
      if (dfu != null && dfu != suppliedDfu) dfu.close();
    }
  }

  public void apply(ApplyPlan plan) throws IOException {
    try (var lock = WorldSessionLock.acquire(layout)) {
      apply(plan, lock);
    }
  }

  public void apply(ApplyPlan plan, WorldSessionLock lock) throws IOException {
    applyAll(List.of(plan), lock);
  }

  /** 全維度先移除舊 UUID，再生成目標，避免跨維度移動被稍後的來源維度移除。 */
  public void applyAll(Collection<ApplyPlan> plans, WorldSessionLock lock) throws IOException {
    lock.requireActive(layout);
    var targetIds = new HashSet<UUID>();
    var dragonTargets = new HashMap<Path, byte[]>();
    for (var plan : plans) {
      DataVersions.requireSame(plan.dataVersion(), layout.dataVersion());
      if (!layout.dimensions().containsKey(plan.dimension()))
        throw new IOException("找不到套用維度：" + plan.dimension());
      validateMetadata(plan);
      for (var entry : plan.worldMeta().entrySet())
        if ("data/minecraft/ender_dragon_fight.dat".equals(SavedData.relativePath(entry.getKey()))) {
          Path path = metadataPath(entry.getKey());
          if (dragonTargets.containsKey(path)
              && !Arrays.equals(dragonTargets.get(path), entry.getValue()))
            throw new IOException("主世界與終界的 DragonFight 目標不一致；請先合併世界設定");
          dragonTargets.put(path, entry.getValue());
        }
      for (var op : plan.entities())
        if (op.target() != null) collectUniqueIds(op.target().data(), targetIds);
    }
    EntitySemantics semantics =
        plans.stream()
                .anyMatch(p -> p.chunks().values().stream().anyMatch(ApplyPlan.ChunkOp::delete))
            ? EntityTagRegistry.load(layout.world(), layout.dataVersion(), packs)
            : EntitySemantics.OFFLINE;
    int timestamp = (int) (System.currentTimeMillis() / 1000);
    try (var upgrade = new DiskUpgrade(layout.dataVersion())) {
      for (var plan : plans) applyTerrain(plan, timestamp, upgrade);
      applyEntities(plans, timestamp, semantics, upgrade);
    }
    for (var plan : plans) {
      var rules = IgnoreRules.parse(plan.ignoreRules());
      for (var entry : plan.worldMeta().entrySet())
        applyMeta(entry.getKey(), entry.getValue(), rules);
    }
  }

  private void applyTerrain(ApplyPlan plan, int timestamp, DiskUpgrade upgrade) throws IOException {
    var dim = layout.dimensions().get(plan.dimension());
    var rules = IgnoreRules.parse(plan.ignoreRules());
    var byRegion = new TreeMap<String, List<ApplyPlan.ChunkOp>>();
    for (var op : plan.chunks().values())
      byRegion.computeIfAbsent(op.pos().regionName(), k -> new ArrayList<>()).add(op);
    long completed = 0;
    for (var entry : byRegion.entrySet()) {
      org.worldgit.core.operation.OperationProgress.report(plan.dimension(), "apply-terrain", completed++, (long)byRegion.size(), org.worldgit.core.operation.OperationProgress.Unit.OBJECT);
      Path terrain = dim.region().resolve(entry.getKey() + ".mca");
      try (var writer = new RegionWriter(terrain);
          var poi = new RegionWriter(dim.poi().resolve(entry.getKey() + ".mca"))) {
        for (var op : entry.getValue()) {
          if (op.delete()) writer.delete(op.pos().regionIndex());
          else
            writer.write(
                op.pos().regionIndex(),
                ChunkNbt.apply(upgrade.upgrade("CHUNK", writer.read(op.pos().regionIndex())),
                    op, plan.dataVersion(), rules),
                timestamp);
          // POI 只刪除變更 chunk；空的不存在檔案不會被建立。
          poi.delete(op.pos().regionIndex());
        }
      }
    }
  }

  private void applyEntities(Collection<ApplyPlan> plans, int timestamp, EntitySemantics semantics,
      DiskUpgrade upgrade)
      throws IOException {
    var ids = new HashSet<UUID>();
    var deleted = new HashMap<DimensionId, Set<ChunkPos>>();
    var rulesByDimension = new HashMap<DimensionId, IgnoreRules>();
    for (var plan : plans) {
      rulesByDimension.put(plan.dimension(), IgnoreRules.parse(plan.ignoreRules()));
      for (var op : plan.entities()) {
        ids.add(op.uuid());
        if (op.target() != null) collectIds(op.target().data(), ids);
      }
      for (var op : plan.chunks().values())
        if (op.delete())
          deleted.computeIfAbsent(plan.dimension(), k -> new HashSet<>()).add(op.pos());
    }
    if (ids.isEmpty() && deleted.isEmpty()) return;
    var old = new HashMap<UUID, Nbt.Compound>();
    // 必須先完整移除，再寫目標；跨 chunk 與 passenger 的 UUID 都納入 barrier。
    for (var dim : layout.dimensions().values()) {
      if (!rulesByDimension.containsKey(dim.id())) continue;
      var removedChunks = deleted.getOrDefault(dim.id(), Set.of());
      var repo = new org.worldgit.core.service.WorldRepositories(layout).tracked().get(dim.id());
      boolean touchedOnly = repo != null && WorldGitConfig.readRepo(repo.resolve("worldgit-repo.yml")).entities() == WorldGitConfig.Entities.PLAYER_TOUCHED;
      if (ids.isEmpty() && removedChunks.isEmpty()) continue;
      var rules = rulesByDimension.getOrDefault(dim.id(), IgnoreRules.none());
      for (Path path : RegionFile.list(dim.entities()))
        try (var writer = new RegionWriter(path)) {
          for (int i = 0; i < 1024; i++)
            if (writer.has(i)) {
              org.worldgit.core.operation.OperationProgress.check();
              var root = upgrade.upgrade("ENTITY_CHUNK", writer.read(i));
              var before = root.list("Entities");
              var after = new ArrayList<Object>();
              boolean changed = false;
              for (Object value : before.values()) {
                var entity = (Nbt.Compound) value;
                var copy = Nbt.copy(entity);
                boolean remove = removeIds(copy, ids, old);
                if (removedChunks.contains(writer.pos(i))
                    && !touchedOnly && !rules.ignoredEntity(entity, semantics)) remove = true;
                if (!remove) after.add(copy);
                changed |= remove || !Nbt.equal(entity, copy);
              }
              if (changed) {
                root.put("Entities", new Nbt.ListTag(10, after));
                writer.write(i, root, timestamp);
              }
            }
        }
    }
    if (plans.stream().anyMatch(plan -> plan.dataVersion() <= 4189))
      for (var previous : List.copyOf(old.values())) indexPreviousEntities(previous, old);
    for (var plan : plans) putEntities(plan, old, timestamp, upgrade);
  }

  private static void indexPreviousEntities(Nbt.Compound entity, Map<UUID, Nbt.Compound> old) {
    old.putIfAbsent(EntityNormalizer.uuid(entity), entity);
    for (Object passenger : entity.list("Passengers").values())
      indexPreviousEntities((Nbt.Compound) passenger, old);
  }

  private void putEntities(ApplyPlan plan, Map<UUID, Nbt.Compound> old, int timestamp,
      DiskUpgrade upgrade)
      throws IOException {
    var dim = layout.dimensions().get(plan.dimension());
    var rules = IgnoreRules.parse(plan.ignoreRules());
    var puts = new TreeMap<ChunkPos, List<Nbt.Compound>>();
    for (var op : plan.entities())
      if (op.target() != null) {
        var entity = op.target().data();
        var previous = old.get(op.uuid());
        if (previous != null)
          previous.forEach(
              (k, v) -> {
                if (rules.ignoredField(entity.string("id"), k, false)) entity.put(k, Nbt.copy(v));
              });
        org.worldgit.core.version.VanillaEntityDefaults.materialize(entity, plan.dataVersion(), old);
        // passenger 的位置在正規化時略去，實際生成時補母實體位置。
        passengerPositions(entity, entity.list("Pos"));
        puts.computeIfAbsent(op.targetChunk(), k -> new ArrayList<>()).add(entity);
      }
    var regions = new TreeMap<String, List<ChunkPos>>();
    for (var pos : puts.keySet())
      regions.computeIfAbsent(pos.regionName(), k -> new ArrayList<>()).add(pos);
    for (var entry : regions.entrySet())
      try (var writer = new RegionWriter(dim.entities().resolve(entry.getKey() + ".mca"))) {
        for (var pos : entry.getValue()) {
          var root = upgrade.upgrade("ENTITY_CHUNK", writer.read(pos.regionIndex()));
          if (root == null)
            root =
                new Nbt.Compound()
                    .with("DataVersion", plan.dataVersion())
                    .with("Position", new int[] {pos.x(), pos.z()});
          var entities = new ArrayList<Object>(root.list("Entities").values());
          entities.addAll(puts.get(pos));
          root.put("Entities", new Nbt.ListTag(10, entities));
          writer.write(pos.regionIndex(), root, timestamp);
        }
      }
  }

  private static void collectIds(Nbt.Compound e, Set<UUID> ids) throws IOException {
    try {
      ids.add(EntityNormalizer.uuid(e));
    } catch (IllegalArgumentException ex) {
      throw new IOException("實體 UUID 無效", ex);
    }
    for (Object p : e.list("Passengers").values()) collectIds((Nbt.Compound) p, ids);
  }

  private static void collectUniqueIds(Nbt.Compound e, Set<UUID> ids) throws IOException {
    UUID uuid;
    try {
      uuid = EntityNormalizer.uuid(e);
    } catch (IllegalArgumentException ex) {
      throw new IOException("實體 UUID 無效", ex);
    }
    if (!ids.add(uuid)) throw new IOException("目標快照跨維度包含重複 UUID：" + uuid);
    for (Object p : e.list("Passengers").values()) collectUniqueIds((Nbt.Compound) p, ids);
  }

  private static boolean removeIds(Nbt.Compound e, Set<UUID> ids, Map<UUID, Nbt.Compound> old)
      throws IOException {
    UUID uuid;
    try {
      uuid = EntityNormalizer.uuid(e);
    } catch (IllegalArgumentException ex) {
      throw new IOException("實體 UUID 無效", ex);
    }
    if (ids.contains(uuid)) {
      old.putIfAbsent(uuid, Nbt.copy(e));
      return true;
    }
    var passengers = new ArrayList<Object>();
    for (Object p : e.list("Passengers").values())
      if (!removeIds((Nbt.Compound) p, ids, old)) passengers.add(p);
    if (e.containsKey("Passengers")) e.put("Passengers", new Nbt.ListTag(10, passengers));
    return false;
  }

  private static void passengerPositions(Nbt.Compound e, Nbt.ListTag position) {
    e.putIfAbsent("Pos", Nbt.copy(position));
    for (Object p : e.list("Passengers").values()) passengerPositions((Nbt.Compound) p, position);
  }

  public void validateMetadata(ApplyPlan plan) throws IOException {
    if (!plan.dimension().equals(DimensionId.OVERWORLD))
      for (String name : plan.worldMeta().keySet())
        if (!name.startsWith(plan.dimension().directoryName() + ".")) throw new IOException("非主世界只能套用自己的 dimension-meta");
    for (var entry : plan.worldMeta().entrySet()) {
      metadataPath(entry.getKey());
      if (entry.getKey().equals("level.nbt") && entry.getValue() != null) {
        var target = Nbt.read(entry.getValue());
        var current = WorldLayout.readGzip(layout.world().resolve("level.dat")).compound("Data");
        if (target.containsKey("DataPacks")
            && !Nbt.equal(
                MetadataNormalizer.portablePacks(target.compound("DataPacks")),
                MetadataNormalizer.portablePacks(current.compound("DataPacks"))))
          throw new IOException("DataPacks 清單不同；請先手動安裝一致的資料包（不在 pull/switch 中遷移）。");
      }
    }
  }

  private void applyMeta(String name, byte[] bytes, IgnoreRules rules) throws IOException {
    if (SavedData.transientEntry(name)) return;
    Path path = metadataPath(name);
    if (name.startsWith("asset.")) {
      if (bytes == null) Files.deleteIfExists(path);
      else RegionFile.atomicWrite(path, bytes);
    } else if (name.equals("level.nbt")) {
      if (bytes == null) throw new IOException("不可刪除 level.dat");
      var root = WorldLayout.readGzip(path);
      var data = root.compound("Data");
      var target = Nbt.read(bytes);
      // 只取正式 capture 的設定欄位；未追蹤的玩家、時間、天氣與其他欄位保留。
      for (String key : LEVEL_FIELDS)
        if (!key.equals("DataVersion")
            && !key.equals("DataPacks")
            && !rules.ignoredField("worldgit:level", key, false)) {
          if (target.containsKey(key)) data.put(key, Nbt.copy(target.get(key)));
          else data.remove(key);
        }
      writeGzip(path, root);
      var end = layout.dimensions().get(new DimensionId("minecraft:the_end"));
      if (layout.dataVersion() < org.worldgit.core.version.MinecraftVersion.NAMESPACED_STORAGE
          && target.containsKey("DragonFight")
          && end != null
          && end.directory().getFileName().toString().equals("DIM1")) {
        Path endLevel = end.directory().getParent().resolve("level.dat");
        if (Files.isRegularFile(endLevel)) {
          var endRoot = WorldLayout.readGzip(endLevel);
          endRoot.compound("Data").put("DragonFight", Nbt.copy(target.get("DragonFight")));
          writeGzip(endLevel, endRoot);
        }
      }
    } else if (bytes == null) Files.deleteIfExists(path);
    else {
      var target = Nbt.read(bytes);
      if (Files.isRegularFile(path)) {
        var current = WorldLayout.readGzip(path);
        String type = MetadataNormalizer.type(name);
        current.forEach(
            (k, v) -> {
              if (type != null && rules.ignoredField(type, k, false)) target.put(k, Nbt.copy(v));
            });
      }
      if (name.contains("saved.")) SavedData.materialize(path, target,
          Files.isRegularFile(path) ? WorldLayout.readGzip(path) : new Nbt.Compound());
      writeGzip(path, target);
    }
  }

  public static final List<String> LEVEL_FIELDS =
      List.of(
          "DataVersion",
          "DataPacks",
          "WorldGenSettings",
          "DragonFight",
          "CustomBossEvents",
          "ScheduledEvents",
          "GameType",
          "allowCommands",
          "SpawnX",
          "SpawnY",
          "SpawnZ",
          "SpawnAngle",
          "spawn",
          "GameRules",
          "game_rules",
          "difficulty_settings",
          "hardcore",
          "Difficulty",
          "DifficultyLocked",
          "BorderCenterX",
          "BorderCenterZ",
          "BorderSize",
          "BorderSafeZone",
          "BorderWarningBlocks",
          "BorderWarningTime",
          "BorderDamagePerBlock",
          "LevelName");

  private Path metadataPath(String name) throws IOException {
    if (name.endsWith(".nbt")) {
      if (name.startsWith("saved.")) return SavedData.path(layout.world(), name.substring(6, name.length() - 4));
      for (var dim : layout.dimensions().values()) {
        String prefix = dim.id().directoryName() + ".saved.";
        if (name.startsWith(prefix)) return SavedData.path(dim.directory(), name.substring(prefix.length(), name.length() - 4));
      }
      String endPrefix = "minecraft.the_end.saved.";
      if (layout.dataVersion() >= org.worldgit.core.version.MinecraftVersion.NAMESPACED_STORAGE
          && name.startsWith(endPrefix)
          && "data/minecraft/ender_dragon_fight.dat".equals(SavedData.relativePath(name))) {
        Path directory = layout.world().resolve("dimensions/minecraft/the_end");
        for (Path parent = directory; parent != null && parent.startsWith(layout.world()); parent = parent.getParent())
          if (Files.isSymbolicLink(parent)) throw new IOException("saved-data 路徑包含符號連結");
        return SavedData.path(directory,
            name.substring(endPrefix.length(), name.length() - 4));
      }
    }
    if (name.equals("level.nbt")) return layout.world().resolve("level.dat");
    if (name.startsWith("asset.")) {
      String relative;
      try {
        relative =
            new String(
                Base64.getUrlDecoder().decode(name.substring(6)),
                java.nio.charset.StandardCharsets.UTF_8);
      } catch (IllegalArgumentException e) {
        throw new IOException("asset 路徑無效");
      }
      if (!relative.startsWith("datapacks/") || relative.contains("\\") || relative.contains(":"))
        throw new IOException("asset 路徑無效");
      Path target = layout.world().resolve(relative).normalize();
      if (!target.startsWith(layout.world().resolve("datapacks"))
          || relative.contains("../")
          || Path.of(relative).isAbsolute() || WorldLayout.excluded(Path.of(relative))) throw new IOException("asset 路徑穿越");
      for (Path parent = target;
          parent != null && parent.startsWith(layout.world());
          parent = parent.getParent())
        if (Files.isSymbolicLink(parent)) throw new IOException("asset 路徑包含符號連結");
      return target;
    }
    if (name.matches("map_\\d+\\.dat\\.nbt")) {
      if(layout.dataVersion()>=org.worldgit.core.version.MinecraftVersion.NAMESPACED_STORAGE)return layout.world().resolve("data/minecraft/maps/"+name.substring(4,name.length()-4));
      Path data = layout.world().resolve("data");
      if (Files.isDirectory(data))
        try (var files = Files.walk(data)) {
          var found =
              files
                  .filter(
                      p -> p.getFileName().toString().equals(name.substring(0, name.length() - 4)))
                  .findFirst();
          if (found.isPresent()) return found.get();
        }
      return layout
          .world()
          .resolve(layout.dataVersion() >= org.worldgit.core.version.MinecraftVersion.NAMESPACED_STORAGE ? "data/minecraft/maps/" : "data/")
          .resolve(name.substring(0, name.length() - 4));
    }
    for (var dim : layout.dimensions().values()) {
      String prefix = dim.id().directoryName() + ".";
      if (name.startsWith(prefix) && META_DAT.contains(name.substring(prefix.length())))
        return dim.directory()
            .resolve("data/minecraft/" + name.substring(prefix.length(), name.length() - 4));
    }
    if (META_DAT.contains(name))
      return layout
          .world()
          .resolve(layout.dataVersion() >= org.worldgit.core.version.MinecraftVersion.NAMESPACED_STORAGE ? "data/minecraft/" : "data/")
          .resolve(name.substring(0, name.length() - 4));
    throw new IOException("不支援的 world-meta 檔案：" + name);
  }

  private static final Set<String> META_DAT =
      Set.of(
          "scoreboard.dat.nbt",
          "game_rules.dat.nbt",
          "world_border.dat.nbt",
          "world_gen_settings.dat.nbt",
          "custom_boss_events.dat.nbt");

  private static void writeGzip(Path path, Nbt.Compound nbt) throws IOException {
    var buffer = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(buffer)) {
      gzip.write(Nbt.write(nbt));
    }
    RegionFile.atomicWrite(path, buffer.toByteArray());
  }
}
