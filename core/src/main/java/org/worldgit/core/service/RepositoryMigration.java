package org.worldgit.core.service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.worldgit.core.anvil.*;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.remote.*;
import org.worldgit.core.store.*;
import org.worldgit.core.operation.OperationProgress;

/** 舊 repo 保留為備份；每個新位置先在同目錄驗證完整複本，再原子發布，可重跑。 */
public final class RepositoryMigration {
  public record Move(DimensionId dimension, Path source, Path destination, String state, String error) {
    public Move(DimensionId dimension, Path source, Path destination, String state) { this(dimension, source, destination, state, null); }
  }
  private RepositoryMigration() {}
  public static List<Move> migrate(WorldLayout layout, DimensionId selected, boolean dryRun) throws IOException {
    var result = new ArrayList<Move>();
    try (var session = WorldSessionLock.acquire(layout)) {
      var tracked = new WorldRepositories(layout).tracked();
      for (var entry : tracked.entrySet()) {
        if (selected != null && !selected.equals(entry.getKey())) continue;
        Path source = entry.getValue(), destination = layout.repository(entry.getKey());
        try {
        if (source.equals(destination)) { result.add(new Move(entry.getKey(), source, destination, "NO_OP")); continue; }
        Path oldRoot = source.getParent();
        if (OperationState.partial(oldRoot) || Files.exists(oldRoot.resolve("merge-state.bin")))
          throw new IOException("舊世界有 PARTIAL／MERGING，請先用舊版本完成恢復或 abort 再 migrate");
        for (String journal : List.of("push-state.yml", "fetch-state.yml", "tag-state.yml")) {
          var state = OperationState.read(oldRoot.resolve(journal));
          if (!state.isEmpty() && !"COMPLETE".equals(state.get("state"))) throw new IOException("請先恢復舊版 " + journal);
        }
        if (dryRun) { result.add(new Move(entry.getKey(), source, destination, "DRY_RUN")); continue; }
        // 單人舊版 root 自己包住來源 repo，先整個保存，避免覆蓋父目錄。
        if (source.startsWith(destination)) {
          Path backup = destination.resolveSibling(".worldgit-legacy");
          if (Files.exists(backup)) throw new IOException("遷移備份已存在；請檢查 .worldgit-legacy");
          Files.move(destination, backup, StandardCopyOption.ATOMIC_MOVE);
          var updated = new TreeMap<DimensionId, Path>();
          tracked.forEach((id, path) -> updated.put(id, path.startsWith(destination) ? backup.resolve(destination.relativize(path)) : path));
          // entrySet 的值可在迭代中替換而不改 key 集合。
          updated.forEach(tracked::put);
          source = backup.resolve(destination.relativize(source)); oldRoot = backup;
        }
        Path temp = destination.resolveSibling(".worldgit-migrating");
        Files.createDirectories(destination.getParent());
        OperationState.write(destination.getParent().resolve(".worldgit-migration.yml"),
            Map.of("source", source.toString(), "destination", destination.toString(), "state", "COPYING"));
        try (var lock = RepoLock.acquire(source)) {
          if (Files.exists(temp)) WorldAssembler.deleteTree(temp);
          copy(source, temp, entry.getKey());
          for (String name : List.of("worldgit.yml"))
            if (Files.exists(oldRoot.resolve(name)) && !Files.exists(temp.resolve(name)))
              Files.copy(oldRoot.resolve(name), temp.resolve(name));
          var stash = OperationState.read(oldRoot.resolve("stash.yml"));
          if (!stash.isEmpty() && stash.get("entries") instanceof List<?> entries) {
            var independentStashes = new ArrayList<Map<String, Object>>();
            for (Object value : entries) {
              if (!(value instanceof Map<?, ?> row) || !(row.get("commits") instanceof Map<?, ?> commits)
                  || !(row.get("bases") instanceof Map<?, ?> bases)) throw new IOException("舊 stash 格式無效");
              String id = entry.getKey().value();
              if (!commits.containsKey(id)) continue;
              if (!bases.containsKey(id)) throw new IOException("舊 stash 缺少基底：" + id);
              var single = new LinkedHashMap<String, Object>();
              for (String key : List.of("id", "time", "message")) single.put(key, row.get(key));
              single.put("commits", Map.of(id, commits.get(id))); single.put("bases", Map.of(id, bases.get(id)));
              independentStashes.add(single);
            }
            OperationState.write(temp.resolve("stash.yml"), Map.of("version", 1, "entries", independentStashes));
          }
          var remotes = RemoteSpec.read(oldRoot);
          if (!remotes.isEmpty()) {
            var independent = new TreeMap<String, RemoteSpec>();
            for (var remote : remotes.entrySet()) independent.put(remote.getKey(),
                new RemoteSpec(remote.getValue().expand(entry.getKey()), new TreeMap<>()));
            RemoteSpec.write(temp, independent);
          }
          try (var original = new JGitStore(source, false); var copied = new JGitStore(temp, false)) {
            if (!original.headState().equals(copied.headState()) || !original.refsByPrefix("refs/").equals(copied.refsByPrefix("refs/")))
              throw new IOException("遷移驗證 HEAD／refs 不一致");
            var commits=original.allCommits();long checked=0;
            for (var commit : commits) {
              OperationProgress.report(entry.getKey(), "migrate-verify", checked++, (long)commits.size(), OperationProgress.Unit.COMMIT);
              if (!copied.readCommit(commit.id()).equals(commit)) throw new IOException("遷移歷史不一致");
              verifyObjects(copied, commit.tree(), new HashSet<>());
            }
          }
          if (Files.exists(destination)) throw new IOException("新位置已存在，拒絕覆蓋：" + destination);
          Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE);
          OperationState.write(destination.getParent().resolve(".worldgit-migration.yml"),
              Map.of("source", source.toString(), "destination", destination.toString(), "state", "COMPLETE"));
          result.add(new Move(entry.getKey(), source, destination, "COMPLETE"));
        }
        } catch (InterruptedIOException cancelled) { throw cancelled; }
        catch (IOException failure) { result.add(new Move(entry.getKey(), source, destination, "FAILED", org.worldgit.core.operation.OperationResult.redact(failure.getMessage()))); }
      }
    }
    return List.copyOf(result);
  }
  private static void verifyObjects(ObjectStore objects, String tree, Set<String> seen) throws IOException {
    if (!seen.add(tree)) return;
    for (var entry : objects.readTree(tree).values()) {
      OperationProgress.check();
      if (entry.kind() == ObjectStore.Kind.TREE) verifyObjects(objects, entry.id(), seen);
      else objects.readBlob(entry.id());
    }
  }
  private static void copy(Path source, Path destination, DimensionId dimension) throws IOException {
    try (var paths = Files.walk(source)) {
      var files = paths.sorted().toList(); long completed = 0;
      for (Path path : files) {
        OperationProgress.report(dimension, "migrate-copy", completed++, (long) files.size(), OperationProgress.Unit.OBJECT);
        if (Files.isSymbolicLink(path)) throw new IOException("repo 包含符號連結，拒絕遷移");
        Path target = destination.resolve(source.relativize(path));
        if (Files.isDirectory(path)) Files.createDirectories(target);
        else if (!path.getFileName().toString().equals("worldgit-operation.lock")) Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
      }
    }
  }
}
