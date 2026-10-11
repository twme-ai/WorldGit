package org.worldgit.core.version;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.worldgit.core.anvil.*;
import org.worldgit.core.config.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;
import org.worldgit.core.remote.*;
import org.worldgit.core.service.*;
import org.worldgit.core.store.*;

/** 離線整個世界的版本遷移；先轉換與組裝到暫存目錄，再替換、發布 commit。 */
public final class WorldVersionMigration {
  public record Result(
      Map<DimensionId, String> commits,
      Map<DimensionId, ConversionReport> reports,
      boolean dryRun) {}

  private WorldVersionMigration() {}

  /** Recover without reading level.dat: a crash may have moved it into the backup already. */
  public static boolean recover(Path supplied) throws IOException {
    Path world = supplied.toAbsolutePath().normalize();
    if (!Files.exists(world.resolve(".worldgit/version-state.yml"))
        && Files.exists(world.resolve("world/.worldgit/version-state.yml")))
      world = world.resolve("world");
    Path journalPath = world.resolve(".worldgit/version-state.yml");
    var journal = OperationState.read(journalPath);
    if (!"PREPARED".equals(journal.get("state"))) return false;
    Path stage = Path.of(String.valueOf(journal.get("stage"))).toAbsolutePath().normalize(),
        cache = MojangRuntime.cache().toAbsolutePath().normalize();
    if (!stage.getParent().equals(cache)
        || !stage.getFileName().toString().matches("migration-[0-9a-f-]{36}"))
      throw new IOException("復原 journal 的暫存路徑無效");
    var roots =
        List.of(
            world,
            world.resolveSibling(world.getFileName() + "_nether"),
            world.resolveSibling(world.getFileName() + "_the_end"),
            stage);
    if (!(journal.get("moves") instanceof List<?> moves)
        || !(journal.get("before") instanceof List<?> before)
        || !(journal.get("commits") instanceof Map<?, ?> candidates))
      throw new IOException("復原 journal 缺少移動或 HEAD 紀錄");
    var checked = new ArrayList<Path[]>();
    var lockPaths = new TreeSet<Path>();
    lockPaths.add(world.resolve("session.lock"));
    for (Object value : moves) {
      if (!(value instanceof Map<?, ?> row)) throw new IOException("復原移動無效");
      checked.add(
          new Path[] {recoveryPath(row.get("from"), roots), recoveryPath(row.get("to"), roots)});
    }
    for (Object value : before) {
      if (!(value instanceof Map<?, ?> row)) throw new IOException("復原 HEAD 無效");
      Path repo = recoveryPath(row.get("repository"), roots);
      if (!repo.getFileName().toString().equals(".worldgit") || repo.startsWith(stage))
        throw new IOException("復原 repo 路徑無效");
      Path parent = repo.getParent();
      if (parent.getFileName().toString().equals("DIM1")
          || parent.getFileName().toString().equals("DIM-1")) parent = parent.getParent();
      lockPaths.add(parent.resolve("session.lock"));
    }
    try (var session = WorldSessionLock.acquire(lockPaths)) {
      for (int i = checked.size() - 1; i >= 0; i--) {
        var m = checked.get(i);
        if (Files.exists(m[1])) {
          if (Files.exists(m[0])) throw new IOException("復原目的地已存在；保留兩份資料以供檢查：" + m[0]);
          Files.createDirectories(m[0].getParent());
          Files.move(m[1], m[0], StandardCopyOption.ATOMIC_MOVE);
        } else if (!Files.exists(m[0])) throw new IOException("復原的兩份資料都不存在：" + m[0]);
      }
      for (Object value : before) {
        var row = (Map<?, ?>) value;
        var dimension = new DimensionId(String.valueOf(row.get("dimension")));
        var prior =
            new RefStore.Head(
                String.valueOf(row.get("commit")),
                String.valueOf(row.get("branch")).isEmpty()
                    ? null
                    : String.valueOf(row.get("branch")));
        try (var repo =
            new DimensionRepository(
                Path.of(String.valueOf(row.get("repository"))), dimension, false)) {
          var actual = repo.refs().headState();
          if (!actual.commit().equals(prior.commit())) {
            if (!actual.commit().equals(candidates.get(dimension.value())))
              throw new IOException("HEAD 已被其他操作變更；不覆寫：" + dimension);
            if (prior.branch() != null)
              repo.refs()
                  .updateRef("refs/heads/" + prior.branch(), actual.commit(), prior.commit());
            else repo.refs().checkout(actual, prior);
          }
          repo.invalidateIndex();
        }
      }
      journal.put("state", "ROLLED_BACK");
      OperationState.write(journalPath, journal);
      deleteTree(stage);
      return true;
    }
  }

  private static Path recoveryPath(Object value, List<Path> roots) throws IOException {
    Path path = Path.of(String.valueOf(value)).toAbsolutePath().normalize();
    if (roots.stream().noneMatch(path::startsWith) || roots.contains(path))
      throw new IOException("復原路徑超出世界／暫存目錄：" + path);
    for (Path p = path; p != null; p = p.getParent())
      if (Files.isSymbolicLink(p)) throw new IOException("復原路徑不可含符號連結：" + p);
    return path;
  }

  public static Result run(
      WorldLayout layout,
      MinecraftVersion target,
      CommitMetadata.Identity author,
      boolean accepted,
      boolean dryRun)
      throws IOException {
    int from = layout.dataVersion();
    if (from == target.dataVersion()) return new Result(Map.of(), Map.of(), dryRun);
    Path journalPath = layout.repositoryRoot().resolve("version-state.yml");
    var previous = OperationState.read(journalPath);
    if ("PREPARED".equals(previous.get("state")))
      throw new IOException("版本遷移未完成；先 wgit migrate --recover-version");
    var journal = new LinkedHashMap<String, Object>();
    var worlds = new WorldRepositories(layout);
    var paths = worlds.tracked();
    if (!paths.keySet().containsAll(layout.dimensions().keySet()))
      throw new IOException("整個世界版本遷移需要先 init 所有既有維度，避免遺漏地形");
    var reports = new TreeMap<DimensionId, ConversionReport>();
    var published = new TreeMap<DimensionId, String>();
    Path stage = MojangRuntime.cache().resolve("migration-" + UUID.randomUUID());
    Files.createDirectories(stage);
    var moves = new ArrayList<Path[]>();
    var priorHeads = new TreeMap<DimensionId, RefStore.Head>();
    var repoMoves = new ArrayList<Path[]>();
    try (var session = WorldSessionLock.acquire(layout);
        var group = new RepositoryGroup(layout.repositoryRoot(), paths)) {
      var output = new TreeMap<DimensionId, RefStore.Commit>();
      var history = new TreeMap<DimensionId, RefStore.Commit>();
      UUID snapshot = UUID.randomUUID();
      for (var e : group.repos().entrySet()) {
        var repo = e.getValue();
        repo.requireLegacyComplete();
        if (OperationState.partial(repo.directory())
            || Files.exists(repo.directory().resolve("merge-state.bin")))
          throw new IOException("PARTIAL／MERGING 必須先恢復");
        var head = repo.refs().readCommit(repo.refs().head());
        priorHeads.put(e.getKey(), repo.refs().headState());
        if (head.metadata().mcDataVersion() != from)
          throw new IOException("HEAD 與世界版本不同；請先 commit 記錄 Minecraft 外部版本變更");
        try (var source = new OfflineSnapshotSource(layout, layout.dimensions().get(e.getKey()))) {
          var status = repo.status(source, worlds.manifest(), 2, true);
          if (!status.diff().empty() || !WorldOperations.untracked(repo.directory()).isEmpty())
            throw new IOException("版本遷移需要乾淨世界；請先 commit 或 stash " + e.getKey());
        }
        var converted =
            VersionConversions.convert(
                repo.objects(),
                head.tree(),
                e.getKey(),
                head.metadata().mcDataVersion(),
                target.dataVersion(),
                repo.directory().resolve("worldgit.yml"),
                true);
        // 套用整個世界也涵蓋未追蹤的生物與排除區域；它們不被加入歷史，但其降級損失要確認。
        String full = fullTree(layout, repo);
        SnapshotConverter.Result all;
        try (var converter = new SnapshotConverter(from, target.dataVersion(),
            repo.directory().resolve("worldgit.yml"), null, true)) {
          all = converter.convert(repo.objects(), full, e.getKey());
        }
        reports.put(e.getKey(), all.report());
        var metadata =
            new CommitMetadata(
                    author,
                    author,
                    all.report().label(),
                    Instant.now(),
                    target.dataVersion(),
                    e.getKey(),
                    CommitMetadata.Source.CLI,
                    false,
                    snapshot,
                    List.of())
                .withVersionChange(all.report());
        output.put(
            e.getKey(), new RefStore.Commit(head.id(), all.tree(), List.of(head.id()), metadata));
        history.put(
            e.getKey(),
            new RefStore.Commit(head.id(), converted.tree(), List.of(head.id()), metadata));
      }
      var playerReport =
          PlayerDataMigration.prepare(
              layout.world(),
              stage.resolve("player-output"),
              from,
              target.dataVersion(),
              paths.get(DimensionId.OVERWORLD).resolve("worldgit.yml"));
      var collected = new ConversionReport.Collector();
      collected.merge(reports.get(DimensionId.OVERWORLD));
      collected.merge(playerReport);
      var combined = collected.report(from, target.dataVersion(), playerReport.rules());
      reports.put(DimensionId.OVERWORLD, combined);
      for (var map : List.of(history, output)) {
        var c = map.get(DimensionId.OVERWORLD);
        map.put(
            DimensionId.OVERWORLD,
            new RefStore.Commit(
                c.id(), c.tree(), c.parents(), c.metadata().withVersionChange(combined)));
      }
      if (!dryRun) for (var report : reports.values()) report.requireAccepted(accepted);
      if (dryRun) return new Result(Map.of(), reports, true);
      new WorldAssembler(WorldAssembler.Budget.defaults())
          .assemble(group, output, stage.resolve("world"), null);
      Path assembled = stage.resolve("world"), backup = stage.resolve("backup");
      restoreMigrationMetadata(group, output, assembled, target.dataVersion());
      Files.createDirectories(backup);
      // The physical level includes runtime fields; the history uses the target capture policy.
      // Time/weather and their 26.x saved-data equivalents stay outside every history tree.
      var stagedLayout = WorldLayout.discover(assembled);
      for (var e : history.entrySet()) {
        var repo = group.repos().get(e.getKey());
        var c = e.getValue();
        var editor = new TreeEditor(repo.objects(), c.tree());
        try (var source =
            new OfflineSnapshotSource(stagedLayout, stagedLayout.dimensions().get(e.getKey()))) {
          var metadata =
              MetadataNormalizer.normalize(
                  source.worldMetadata(), IgnoreRules.parse(Files.readString(repo.ignorePath())));
          if (e.getKey().equals(DimensionId.OVERWORLD))
            metadata.put("worldgit.yml", Files.readAllBytes(repo.configPath()));
          editor.replaceTree(
              e.getKey().equals(DimensionId.OVERWORLD) ? "world-meta" : "dimension-meta", metadata);
        }
        String tree = editor.write();
        repo.objects().flush();
        e.setValue(new RefStore.Commit(c.id(), tree, c.parents(), c.metadata()));
      }
      // 在第一筆世界寫入前把候選 commit pin 到操作 refs；journal 記錄每個移動供復原。
      UUID op = UUID.randomUUID();
      for (var e : history.entrySet()) {
        var repo = group.repos().get(e.getKey());
        var c = e.getValue();
        String id = repo.refs().createCommit(c.tree(), c.id(), c.metadata());
        repo.refs().updateRef("refs/worldgit/versions/" + op + "/candidate", null, id);
        published.put(e.getKey(), id);
      }
      journal.put(
          "before",
          priorHeads.entrySet().stream()
              .map(
                  e ->
                      Map.of(
                          "dimension",
                          e.getKey().value(),
                          "repository",
                          paths.get(e.getKey()).toString(),
                          "commit",
                          e.getValue().commit(),
                          "branch",
                          e.getValue().branch() == null ? "" : e.getValue().branch()))
              .toList());
      journal.put("state", "PREPARED");
      journal.put("from", from);
      journal.put("to", target.dataVersion());
      journal.put("stage", stage.toString());
      journal.put(
          "commits",
          published.entrySet().stream()
              .collect(
                  java.util.stream.Collectors.toMap(e -> e.getKey().value(), Map.Entry::getValue)));
      OperationState.write(layout.repositoryRoot().resolve("version-state.yml"), journal);
      for (var e : layout.dimensions().entrySet()) {
        Path old = e.getValue().directory(),
            next = WorldAssembler.dimensionPath(layout.world(), e.getKey(), target.dataVersion());
        for (String name : List.of("region", "entities", "poi", "data"))
          if ((!name.equals("data") || !old.equals(layout.world()))
              && Files.exists(old.resolve(name)))
            move(
                old.resolve(name),
                backup.resolve(e.getKey().directoryName()).resolve(name),
                moves,
                journal,
                journalPath);
        Path built = WorldAssembler.dimensionPath(assembled, e.getKey(), target.dataVersion());
        for (String name : List.of("region", "entities", "data"))
          if ((!name.equals("data") || !built.equals(assembled))
              && Files.exists(built.resolve(name)))
            move(built.resolve(name), next.resolve(name), moves, journal, journalPath);
        if (!e.getKey().equals(DimensionId.OVERWORLD) && !old.equals(next))
          repoMoves.add(new Path[] {paths.get(e.getKey()), next.resolve(".worldgit")});
      }
      moveUngeneratedDragon(layout, assembled, backup, target.dataVersion(), moves, journal, journalPath);
      // 世界根目錄 data 可能同時是舊主世界 data，前面的 move 已處理。
      if (Files.exists(layout.world().resolve("data")))
        move(
            layout.world().resolve("data"),
            backup.resolve("shared-data"),
            moves,
            journal,
            journalPath);
      if (Files.exists(assembled.resolve("data")))
        move(
            assembled.resolve("data"), layout.world().resolve("data"), moves, journal, journalPath);
      move(
          layout.world().resolve("level.dat"),
          backup.resolve("level.dat"),
          moves,
          journal,
          journalPath);
      move(
          assembled.resolve("level.dat"),
          layout.world().resolve("level.dat"),
          moves,
          journal,
          journalPath);
      for (String name : PlayerDataMigration.ROOTS) {
        if (Files.exists(layout.world().resolve(name)))
          move(
              layout.world().resolve(name),
              backup.resolve("player-" + name),
              moves,
              journal,
              journalPath);
        if (Files.exists(stage.resolve("player-output").resolve(name)))
          move(
              stage.resolve("player-output").resolve(name),
              layout.world().resolve(name),
              moves,
              journal,
              journalPath);
      }
      for (var e : group.repos().entrySet()) {
        var prior = priorHeads.get(e.getKey());
        if (prior.branch() != null)
          e.getValue()
              .refs()
              .updateRef("refs/heads/" + prior.branch(), prior.commit(), published.get(e.getKey()));
        else
          e.getValue().refs().checkout(prior, new RefStore.Head(published.get(e.getKey()), null));
        e.getValue().invalidateIndex();
      }
      // Linux 原子搬移在所有 object／HEAD 寫入完成後進行，session.lock 與 repo lock 仍持有。
      for (var m : repoMoves) move(m[0], m[1], moves, journal, journalPath);
      journal.put(
          "moves",
          moves.stream().map(m -> Map.of("from", m[0].toString(), "to", m[1].toString())).toList());
      journal.put("state", "COMPLETE");
      OperationState.write(layout.repositoryRoot().resolve("version-state.yml"), journal);
    } catch (IOException | RuntimeException failure) {
      for (int i = moves.size() - 1; i >= 0; i--)
        try {
          var m = moves.get(i);
          if (Files.exists(m[1]) && !Files.exists(m[0])) {
            Files.createDirectories(m[0].getParent());
            Files.move(m[1], m[0], StandardCopyOption.ATOMIC_MOVE);
          }
        } catch (IOException rollback) {
          failure.addSuppressed(rollback);
        }
      // 部分 HEAD 若已發布，CAS 回原位置；候選 pin 仍可供檢查。
      for (var e : priorHeads.entrySet())
        if (published.containsKey(e.getKey()))
          try (var repo = new DimensionRepository(paths.get(e.getKey()), e.getKey(), false)) {
            var actual = repo.refs().headState();
            if (actual.commit().equals(published.get(e.getKey()))) {
              if (e.getValue().branch() != null)
                repo.refs()
                    .updateRef(
                        "refs/heads/" + e.getValue().branch(),
                        actual.commit(),
                        e.getValue().commit());
              else repo.refs().checkout(actual, e.getValue());
            }
          } catch (IOException rollback) {
            failure.addSuppressed(rollback);
          }
      if (!journal.isEmpty() && failure.getSuppressed().length == 0) {
        journal.put("state", "ROLLED_BACK");
        OperationState.write(journalPath, journal);
        deleteTree(stage);
      }
      throw failure;
    } finally {
      if (dryRun || moves.isEmpty()) deleteTree(stage);
    }
    deleteTree(stage);
    return new Result(published, reports, false);
  }

  /** DragonFight can exist without End terrain, so the dimension move loop cannot carry it. */
  static void moveUngeneratedDragon(
      WorldLayout layout, Path assembled, Path backup, int target,
      List<Path[]> moves, Map<String, Object> journal, Path journalPath) throws IOException {
    var end = new DimensionId("minecraft:the_end");
    if (layout.dimensions().containsKey(end)) return;
    String relative = "data/minecraft/ender_dragon_fight.dat";
    Path original = WorldAssembler.dimensionPath(layout.world(), end, layout.dataVersion()).resolve(relative);
    Path built = WorldAssembler.dimensionPath(assembled, end, target).resolve(relative);
    Path destination = WorldAssembler.dimensionPath(layout.world(), end, target).resolve(relative);
    for (Path file : List.of(original, destination))
      for (Path parent = file; parent != null && parent.startsWith(layout.world()); parent = parent.getParent())
        if (Files.isSymbolicLink(parent)) throw new IOException("saved-data 路徑包含符號連結");
    if (Files.exists(original))
      move(original, backup.resolve("ungenerated-end/ender_dragon_fight.dat"), moves, journal, journalPath);
    if (Files.exists(built))
      move(built, destination, moves, journal, journalPath);
  }

  private static void move(
      Path from, Path to, List<Path[]> moves, Map<String, Object> journal, Path journalPath)
      throws IOException {
    Files.createDirectories(to.getParent());
    if (Files.exists(to)) throw new IOException("版本遷移目的地已有資料：" + to);
    moves.add(new Path[] {from, to});
    journal.put(
        "moves",
        moves.stream().map(m -> Map.of("from", m[0].toString(), "to", m[1].toString())).toList());
    OperationState.write(journalPath, journal);
    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
  }

  private static String fullTree(WorldLayout layout, DimensionRepository repo) throws IOException {
    var editor = new TreeEditor(repo.objects(), null);
    try (var source =
        new OfflineSnapshotSource(layout, layout.dimensions().get(repo.dimension()))) {
      var scan = source.scan(org.worldgit.core.capture.ScanIndex.empty(), true);
      for (var pos : new TreeSet<>(scan.present())) {
        var s = source.snapshot(pos, IgnoreRules.none()).toCompletableFuture().join();
        if (s.isPresent()) editor.replaceTree(pos.treePath(), SnapshotCodec.chunkFiles(s.get()));
      }
      var rawMetadata = new TreeMap<>(source.worldMetadata());
      if (repo.dimension().equals(DimensionId.OVERWORLD))
        rawMetadata.put(
            "level.nbt",
            Nbt.write(WorldLayout.readGzip(layout.world().resolve("level.dat")).compound("Data")));
      // 全世界的實體資料遷移不能因舊版歷史只追蹤一部分 saved-data 而刪掉其他檔案。
      rawMetadata.putAll(
          SavedData.captureForMigration(
              layout.dimensions().get(repo.dimension()).directory(),
              repo.dimension().equals(DimensionId.OVERWORLD)
                      && layout.dimensions().get(repo.dimension()).directory().equals(layout.world())
                  ? ""
                  : repo.dimension().directoryName() + "."));
      if (repo.dimension().equals(DimensionId.OVERWORLD))
        rawMetadata.putAll(SavedData.captureForMigration(layout.world(), ""));
      var meta = rawMetadata;
      editor.replaceTree(
          repo.dimension().equals(DimensionId.OVERWORLD) ? "world-meta" : "dimension-meta", meta);
    }
    String tree = editor.write();
    repo.objects().flush();
    return tree;
  }

  /** 僅在尚未發布的遷移世界寫入目前磁碟狀態；一般 assemble／apply 仍排除暫態資料。 */
  private static void restoreMigrationMetadata(RepositoryGroup group,
      Map<DimensionId, RefStore.Commit> commits, Path world, int version) throws IOException {
    for (var commit : commits.entrySet()) {
      var store = group.repos().get(commit.getKey()).objects();
      var entry = TreeEditor.find(store, commit.getValue().tree(),
          commit.getKey().equals(DimensionId.OVERWORLD) ? "world-meta" : "dimension-meta");
      if (entry == null) continue;
      for (var file : store.readTree(entry.id()).values()) {
        String relative = SavedData.relativePath(file.name());
        if (relative == null) continue;
        DimensionId owner = commit.getKey();
        int saved = file.name().lastIndexOf("saved.");
        if (saved > 0) {
          String prefix = file.name().substring(0, saved - 1);
          int separator = prefix.indexOf('.');
          if (separator < 0) throw new IOException("遷移 metadata 維度名稱無效");
          owner = new DimensionId(prefix.substring(0, separator) + ":" + prefix.substring(separator + 1));
        }
        Path directory = owner.equals(DimensionId.OVERWORLD) && saved == 0
            ? world : WorldAssembler.dimensionPath(world, owner, version);
        Path target = SavedData.path(directory, file.name().substring(saved + 6, file.name().length() - 4));
        Files.createDirectories(target.getParent());
        try (var zipped = new java.util.zip.GZIPOutputStream(Files.newOutputStream(target))) {
          var raw = Nbt.read(store.readBlob(file.id()));
          zipped.write(Nbt.write(SavedData.materialize(target, raw, raw)));
        }
      }
    }
  }

  public static void deleteTree(Path path) throws IOException {
    if (!Files.exists(path)) return;
    try (var walk = Files.walk(path)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
    }
  }
}
