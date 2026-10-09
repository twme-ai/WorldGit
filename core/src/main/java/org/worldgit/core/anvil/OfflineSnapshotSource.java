package org.worldgit.core.anvil;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import org.worldgit.core.capture.*;
import org.worldgit.core.config.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;

/** 離線 candidate 掃描：標頭 + 檔案 nanosecond mtime/size/fileKey；不確定秒窗驗證原始 payload 雜湊。 */
public final class OfflineSnapshotSource implements SnapshotSource {
  private final WorldLayout layout;
  private final WorldLayout.Dimension dimension;
  private final Map<Path, RegionFile> opened = new HashMap<>();
  private final Map<ChunkPos, Path> terrainPaths = new HashMap<>(), entityPaths = new HashMap<>();
  private int reads;
  private EntityTagRegistry registry;

  private final EntityTagRegistry.PackResolver packResolver;

  public OfflineSnapshotSource(WorldLayout layout, WorldLayout.Dimension dimension) {
    this(layout, dimension, null);
  }

  /** packResolver：平台 adapter 提供的模組 datapack tag（可為 null）。 */
  public OfflineSnapshotSource(
      WorldLayout layout, WorldLayout.Dimension dimension, EntityTagRegistry.PackResolver packResolver) {
    this.layout = layout;
    this.dimension = dimension;
    this.packResolver = packResolver;
  }

  @Override
  public DimensionId dimension() {
    return dimension.id();
  }

  @Override
  public int dataVersion() throws IOException {
    return layout.dataVersion();
  }

  private EntityTagRegistry registry() throws IOException {
    if (registry == null) registry = EntityTagRegistry.load(layout.world(), dataVersion(), packResolver);
    return registry;
  }

  @Override
  public String normalizationFingerprint() throws IOException {
    return "normalize-v1-structures-set:" + dataVersion() + ":" + registry().fingerprint();
  }

  @Override
  public List<String> warnings() throws IOException {
    return registry().warnings();
  }

  @Override
  public Map<String, byte[]> worldMetadata() throws IOException {
    return dimension().equals(DimensionId.OVERWORLD) ? layout.worldMetadata() : layout.dimensionMetadata(dimension());
  }

  private RegionFile region(Path path) throws IOException {
    RegionFile r = opened.get(path);
    if (r == null) {
      r = new RegionFile(path);
      opened.put(path, r);
    }
    return r;
  }

  private record Half(String file, int time, int location, String hash) {}

  @Override
  public Scan scan(ScanIndex previous, boolean full) throws IOException {
    close();
    terrainPaths.clear();
    entityPaths.clear();
    reads = 0;
    long now = System.currentTimeMillis() / 1000;
    Map<ChunkPos, Half> terrain = scanFiles(dimension.region(), false, previous, full, now),
        entities = scanFiles(dimension.entities(), true, previous, full, now);
    var stamps = new HashMap<ChunkPos, ScanIndex.Stamp>();
    var candidates = new TreeSet<ChunkPos>();
    for (var entry : terrain.entrySet()) {
      ChunkPos pos = entry.getKey();
      Half t = entry.getValue(), e = entities.getOrDefault(pos, new Half("", 0, 0, ""));
      var stamp =
          new ScanIndex.Stamp(
              t.file, t.time, t.location, t.hash, e.file, e.time, e.location, e.hash);
      stamps.put(pos, stamp);
      var old = previous.chunks().get(pos);
      if (full
          || old == null
          || !old.terrainHash().equals(t.hash)
          || !old.entityHash().equals(e.hash)) candidates.add(pos);
    }
    for (ChunkPos pos : previous.chunks().keySet())
      if (!terrain.containsKey(pos)) candidates.add(pos);
    return new Scan(terrain.keySet(), candidates, stamps, reads, now);
  }

  private Map<ChunkPos, Half> scanFiles(
      Path dir, boolean entity, ScanIndex previous, boolean full, long now) throws IOException {
    var result = new HashMap<ChunkPos, Half>();
    // 一次列出 .mcc，避免 clean status 對數萬個普通 chunk 各做一次 stat。
    var externalFiles = new HashMap<ChunkPos, Path>();
    if (Files.isDirectory(dir))
      try (var files = Files.list(dir)) {
        for (Path file :
            files
                .filter(p -> p.getFileName().toString().matches("c\\.-?\\d+\\.-?\\d+\\.mcc"))
                .toList()) {
          String[] parts = file.getFileName().toString().split("\\.");
          externalFiles.put(
              new ChunkPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2])), file);
        }
      }
    for (Path path : RegionFile.list(dir)) {
      var attrs = Files.readAttributes(path, BasicFileAttributes.class);
      String changeTime = changeTime(path);
      String fingerprint = attrs.lastModifiedTime() + ":" + attrs.size() + ":" + attrs.fileKey() + ":" + changeTime;
      RegionFile r = region(path);
      for (int i = 0; i < 1024; i++)
        if (r.has(i)) {
          org.worldgit.core.operation.OperationProgress.report(dimension(), entity ? "scan-entities" : "scan-terrain", result.size(), null, org.worldgit.core.operation.OperationProgress.Unit.CHUNK);
          ChunkPos pos = r.pos(i);
          (entity ? entityPaths : terrainPaths).put(pos, path);
          var old = previous.chunks().get(pos);
          String oldFile = old == null ? "" : entity ? old.entityFile() : old.terrainFile(),
              oldHash = old == null ? "" : entity ? old.entityHash() : old.terrainHash();
          int oldTime = old == null ? 0 : entity ? old.entityTime() : old.terrainTime(),
              oldLocation = old == null ? 0 : entity ? old.entityLocation() : old.terrainLocation();
          boolean uncertain =
              Integer.toUnsignedLong(r.timestamp(i)) >= Math.min(now, previous.scannedAt());
          // .mcc 可獨立重寫，external 檔的屬性也納入 fingerprint。
          String file = fingerprint;
          Path external = externalFiles.get(pos);
          if (external != null) {
            var a = Files.readAttributes(external, BasicFileAttributes.class);
            String externalChangeTime = changeTime(external);
            file += ":" + a.lastModifiedTime() + ":" + a.size() + ":" + externalChangeTime;
            if (externalChangeTime == null) uncertain = true;
          }
          String hash = oldHash;
          if (full
              || changeTime == null
              || old == null
              || !file.equals(oldFile)
              || r.timestamp(i) != oldTime
              || r.location(i) != oldLocation
              || uncertain) {
            var p = r.payload(i);
            hash = hash(p.compression(), p.bytes());
            reads++;
          }
          result.put(pos, new Half(file, r.timestamp(i), r.location(i), hash));
        }
    }
    return result;
  }

  // 外部工具可能保留 mtime／同秒 timestamp／sector location。Unix ctime 由檔案系統
  // 更新；不支援 ctime 的 provider 保守雜湊 payload，不把 attrs 當完整內容證明。
  private static String changeTime(Path path) throws IOException {
    try { return String.valueOf(Files.getAttribute(path, "unix:ctime")); }
    catch (UnsupportedOperationException | IllegalArgumentException e) { return null; }
  }

  public static String hash(int kind, byte[] data) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update((byte) kind);
      return HexFormat.of().formatHex(md.digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public CompletionStage<Optional<ChunkSnapshot>> snapshot(ChunkPos pos, IgnoreRules rules) {
    try {
      Path path = terrainPaths.get(pos);
      if (path == null) path = dimension.region().resolve(pos.regionName() + ".mca");
      if (!Files.isRegularFile(path) || !region(path).has(pos.regionIndex()))
        return CompletableFuture.completedFuture(Optional.empty());
      Nbt.Compound raw = region(path).read(pos.regionIndex());
      if (!ChunkNormalizer.full(raw)) return CompletableFuture.completedFuture(Optional.empty());
      var entities = new ArrayList<Nbt.Compound>();
      Path ep = entityPaths.get(pos);
      if (ep == null) ep = dimension.entities().resolve(pos.regionName() + ".mca");
      if (Files.isRegularFile(ep) && region(ep).has(pos.regionIndex()))
        for (Object e : region(ep).read(pos.regionIndex()).list("Entities").values())
          entities.add((Nbt.Compound) e);
      return CompletableFuture.completedFuture(
          Optional.of(new ChunkNormalizer(rules, registry()).normalize(pos, raw, entities)));
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override public boolean entityCensusAvailable() { return true; }

  @Override public Set<UUID> unchangedEntityIds(Set<ChunkPos> captured) throws IOException {
    var ids = new HashSet<UUID>();
    for (var e : entityPaths.entrySet()) if (!captured.contains(e.getKey()))
      for (var entity : region(e.getValue()).read(e.getKey().regionIndex()).list("Entities").values())
        org.worldgit.core.capture.PlayerTouchedEntities.collect((Nbt.Compound)entity,ids);
    return ids;
  }

  @Override
  public void close() throws IOException {
    IOException failure = null;
    for (var r : opened.values())
      try {
        r.close();
      } catch (IOException e) {
        failure = e;
      }
    opened.clear();
    if (failure != null) throw failure;
  }
}
