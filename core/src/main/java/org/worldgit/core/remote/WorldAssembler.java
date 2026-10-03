package org.worldgit.core.remote;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import org.worldgit.core.anvil.*;
import org.worldgit.core.apply.*;
import org.worldgit.core.config.IgnoreRules;
import org.worldgit.core.model.*;
import org.worldgit.core.service.*;
import org.worldgit.core.store.*;

/** clone/release 共用的逐 region 組裝器；不需要活世界。輸出單人原版布局。 */
public final class WorldAssembler {
  public record Budget(long maxBytes, Duration maxTime) {
    public Budget {
      if (maxBytes < 1 || maxTime.isNegative() || maxTime.isZero())
        throw new IllegalArgumentException("預算必須正數");
    }

    public static Budget defaults() {
      return new Budget(2L * 1024 * 1024 * 1024, Duration.ofMinutes(15));
    }
  }

  public record Result(long bytes, int regions, long elapsedMillis) {}

  private final Budget budget;
  private final long start = System.nanoTime();
  private long bytes;
  private int regions;

  public WorldAssembler(Budget budget) {
    this.budget = budget;
  }

  private void check(long n) throws IOException {
    bytes += n;
    if (bytes > budget.maxBytes()) throw new IOException("世界組裝超過大小預算");
    if (System.nanoTime() - start > budget.maxTime().toNanos()) throw new IOException("世界組裝超過時間預算");
    if (Thread.currentThread().isInterrupted()) throw new IOException("世界組裝取消");
  }

  public static Path dimensionPath(Path world, DimensionId id, int version) {
    if (version >= 4903)
      return world.resolve("dimensions").resolve(id.namespace()).resolve(id.path());
    if (id.equals(DimensionId.OVERWORLD)) return world;
    if (id.value().equals("minecraft:the_nether")) return world.resolve("DIM-1");
    if (id.value().equals("minecraft:the_end")) return world.resolve("DIM1");
    return world.resolve("dimensions").resolve(id.namespace()).resolve(id.path());
  }

  public Result assemble(
      RepositoryGroup group,
      Map<DimensionId, RefStore.Commit> commits,
      Path world,
      Set<DimensionId> selected)
      throws IOException {
    group.validate(commits);
    var main = commits.get(DimensionId.OVERWORLD);
    if (main == null) throw new IOException("clone/export 需要主世界 metadata repo");
    int version = main.metadata().mcDataVersion();
    var objects = group.repos().get(DimensionId.OVERWORLD).objects();
    var level = TreeEditor.find(objects, main.tree(), "world-meta/level.nbt");
    if (level == null) throw new IOException("快照缺少 level.nbt，無法建立可開啟世界");
    var data = Nbt.read(objects.readBlob(level.id()));
    if (data.containsKey("DataPacks"))
      data.put(
          "DataPacks",
          org.worldgit.core.normalize.MetadataNormalizer.portablePacks(data.compound("DataPacks")));
    if (version < 4903 && !data.containsKey("DragonFight"))
      data.put(
          "DragonFight",
          new Nbt.Compound()
              .with("DragonKilled", (byte) 0)
              .with("PreviouslyKilled", (byte) 0)
              .with("NeedsStateScanning", (byte) 1));
    data.put("version", 19133);
    data.put("initialized", (byte) 1);
    data.put(
        "Version",
        new Nbt.Compound()
            .with("Id", version)
            .with("Name", version >= 4903 ? "26.2" : "1.21.11")
            .with("Series", "main")
            .with("Snapshot", (byte) 0));
    if (data.integer("DataVersion", 0) != version)
      throw new IOException("level.dat DataVersion 與 commit 不符");
    if (version < 4903 && !data.containsKey("WorldGenSettings"))
      throw new IOException("快照未保存 WorldGenSettings，無法保證種子還原");
    if (version >= 4903
        && TreeEditor.find(
                objects,
                main.tree(),
                "world-meta/"
                    + DimensionId.OVERWORLD.directoryName()
                    + ".world_gen_settings.dat.nbt")
            == null) throw new IOException("快照缺少主世界生成設定");
    Files.createDirectories(world);
    gzip(world.resolve("level.dat"), new Nbt.Compound().with("Data", data));
    var metadataIds = new TreeSet<>(commits.keySet());
    var manifest = TreeEditor.find(objects, main.tree(), "dimensions");
    if (manifest != null) {
      var m =
          SafeYaml.parse(
              new String(objects.readBlob(manifest.id()), java.nio.charset.StandardCharsets.UTF_8));
      if (m.get("dimensions") instanceof Map<?, ?> dims)
        for (Object id : dims.keySet()) metadataIds.add(new DimensionId((String) id));
    }
    // 所有原維度的生成設定都還原；只選取的維度才建立 region，其他維度由遊戲依種子生成。
    for (var e :
        objects.readTree(TreeEditor.find(objects, main.tree(), "world-meta").id()).values()) {
      check(0);
      if (e.name().equals("worldgit.yml") || e.name().equals("level.nbt")) continue;
      byte[] raw = objects.readBlob(e.id());
      String name = e.name();
      if (name.startsWith("asset.")) {
        String path;
        try {
          path =
              new String(
                  Base64.getUrlDecoder().decode(name.substring(6)),
                  java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
          throw new IOException("資料包路徑無效");
        }
        Path target = world.resolve(path).normalize();
        if (!path.startsWith("datapacks/")
            || path.contains("../")
            || path.contains("\\")
            || !target.startsWith(world.resolve("datapacks"))) throw new IOException("資料包路徑穿越");
        check(raw.length);
        RegionFile.atomicWrite(target, raw);
        continue;
      }
      Path target = null;
      for (var id : metadataIds) {
        String prefix = id.directoryName() + ".";
        if (name.startsWith(prefix)
            && Set.of("game_rules.dat.nbt", "world_border.dat.nbt", "world_gen_settings.dat.nbt")
                .contains(name.substring(prefix.length())))
          target =
              dimensionPath(world, id, version)
                  .resolve("data/minecraft")
                  .resolve(name.substring(prefix.length(), name.length() - 4));
      }
      if (target == null && name.matches("map_\\d+\\.dat\\.nbt"))
        target =
            world
                .resolve(version >= 4903 ? "data/minecraft/maps" : "data")
                .resolve(name.substring(0, name.length() - 4));
      if (target == null
          && Set.of(
                  "scoreboard.dat.nbt",
                  "custom_boss_events.dat.nbt",
                  "game_rules.dat.nbt",
                  "world_border.dat.nbt",
                  "world_gen_settings.dat.nbt")
              .contains(name))
        target =
            world
                .resolve(version >= 4903 ? "data/minecraft" : "data")
                .resolve(name.substring(0, name.length() - 4));
      if (target == null) throw new IOException("不支援的世界 metadata：" + name);
      gzip(target, Nbt.read(raw));
      if (version >= 4903 && name.startsWith(DimensionId.OVERWORLD.directoryName() + ".")) {
        String suffix = name.substring(DimensionId.OVERWORLD.directoryName().length() + 1);
        if (Set.of("game_rules.dat.nbt", "world_border.dat.nbt", "world_gen_settings.dat.nbt")
                .contains(suffix)
            && TreeEditor.find(objects, main.tree(), "world-meta/" + suffix) == null)
          gzip(
              world.resolve("data/minecraft/" + suffix.substring(0, suffix.length() - 4)),
              Nbt.read(raw));
      }
    }
    var included = new TreeSet<>(selected == null ? commits.keySet() : selected);
    for (var id : included) {
      if (!commits.containsKey(id)) throw new IOException("快照缺少指定維度：" + id);
      Files.createDirectories(dimensionPath(world, id, version).resolve("region"));
    }
    if (!included.contains(DimensionId.OVERWORLD))
      Files.createDirectories(
          dimensionPath(world, DimensionId.OVERWORLD, version).resolve("region"));
    var layout = WorldLayout.discover(world);
    var applier = new OfflineApplier(layout);
    try (var lock = WorldSessionLock.acquire(layout)) {
      for (var id : included) {
        var commit = commits.get(id);
        var store = group.repos().get(id).objects();
        DataVersions.requireSame(version, commit.metadata().mcDataVersion());
        for (var region : store.readTree(commit.tree()).values())
          if (region.name().matches("r\\.-?\\d+\\.-?\\d+")
              && region.kind() == ObjectStore.Kind.TREE) {
            check(0);
            String tree = store.writeTree(List.of(region));
            store.flush();
            var plan =
                ApplyPlanner.plan(
                    store,
                    id,
                    null,
                    tree,
                    Scope.all(),
                    new ApplyPlanner.Options(
                        IgnoreRules.none(), IgnoreRules.none(), null, 0, false, false, version));
            applier.apply(plan, lock);
            regions++;
            Path r = dimensionPath(world, id, version);
            check(Files.size(r.resolve("region/" + region.name() + ".mca")));
            Path entities = r.resolve("entities/" + region.name() + ".mca");
            if (Files.exists(entities)) check(Files.size(entities));
            for (var chunk : plan.chunks().values())
              for (String kind : List.of("region", "entities")) {
                Path external =
                    r.resolve(kind)
                        .resolve("c." + chunk.pos().x() + "." + chunk.pos().z() + ".mcc");
                if (Files.isRegularFile(external)) check(Files.size(external));
              }
          }
      }
    }
    Files.deleteIfExists(world.resolve("session.lock"));
    return new Result(bytes, regions, (System.nanoTime() - start) / 1_000_000);
  }

  private void gzip(Path target, Nbt.Compound data) throws IOException {
    var out = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(out)) {
      gzip.write(Nbt.write(data));
    }
    check(out.size());
    RegionFile.atomicWrite(target, out.toByteArray());
  }

  /** 暫存 Anvil 檔，ZIP 逐檔串流至 caller output；不關閉 caller stream。沒有 repo/player/session.lock。 */
  public Result zip(RepositoryGroup group, String revision, OutputStream output, Path tempRoot)
      throws IOException {
    return zip(group, group.resolve(revision), output, tempRoot);
  }

  /** 以已授權並固定的全維度 commit map 匯出，不重新解析可能移動的 tag。 */
  public Result zip(
      RepositoryGroup group,
      Map<DimensionId, RefStore.Commit> commits,
      OutputStream output,
      Path tempRoot)
      throws IOException {
    Files.createDirectories(tempRoot);
    Path temp = Files.createTempDirectory(tempRoot, "release-");
    try {
      var result = assemble(group, commits, temp, null);
      var zip =
          new ZipOutputStream(
              new FilterOutputStream(output) {
                long emitted;

                private void reserve(int n) throws IOException {
                  emitted += n;
                  check(0);
                  if (emitted > budget.maxBytes()) throw new IOException("ZIP 輸出超過大小預算");
                }

                @Override
                public void write(int b) throws IOException {
                  reserve(1);
                  out.write(b);
                }

                @Override
                public void write(byte[] b, int o, int n) throws IOException {
                  reserve(n);
                  out.write(b, o, n);
                }

                @Override
                public void close() throws IOException {
                  flush();
                }
              });
      try {
        try (var files = Files.walk(temp)) {
          for (Path path : files.filter(p -> !p.equals(temp)).sorted().toList()) {
            check(0);
            String name = temp.relativize(path).toString().replace(java.io.File.separatorChar, '/');
            if (name.equals("session.lock")) continue;
            // 空維度也必須保留；遊戲需辨識它並沿用 level.dat 的生成／終界設定。
            if (Files.isDirectory(path)) name += "/";
            var entry = new ZipEntry(name);
            entry.setTime(0);
            zip.putNextEntry(entry);
            if (Files.isRegularFile(path)) {
              try (var in = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) {
                  check(0);
                  zip.write(buffer, 0, n);
                }
              }
            }
            zip.closeEntry();
          }
        }
        zip.finish();
      } finally {
        zip.close();
      }
      return new Result(result.bytes(), result.regions(), (System.nanoTime() - start) / 1_000_000);
    } finally {
      deleteTree(temp);
    }
  }

  public static void deleteTree(Path root) throws IOException {
    if (Files.exists(root))
      try (var files = Files.walk(root)) {
        for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
      }
  }
}
