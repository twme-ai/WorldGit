package org.worldgit.core.remote;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.worldgit.core.model.*;
import org.worldgit.core.service.*;
import org.worldgit.core.store.*;

/** 沒有世界資料夾也可使用的維度組鎖、revision 與 tag 入口。 */
public final class RepositoryGroup implements AutoCloseable {
  private final Path root;
  private final boolean owner;
  private final SortedMap<DimensionId, DimensionRepository> repos = new TreeMap<>();

  public RepositoryGroup(Path root, Map<DimensionId, Path> paths) throws IOException {
    this.root = paths.size() == 1 ? paths.values().iterator().next() : root;
    owner = true;
    try {
      for (var e : new TreeMap<>(paths).entrySet())
        repos.put(e.getKey(), new DimensionRepository(e.getValue(), e.getKey(), false));
    } catch (Exception e) {
      close();
      throw e;
    }
    if (repos.isEmpty()) {
      close();
      throw new IOException("世界沒有 repo");
    }
    try {
      if (repos.size() == 1) recoverTags();
    } catch (IOException ex) {
      close();
      throw ex;
    }
  }

  public Path root() {
    return root;
  }

  public SortedMap<DimensionId, DimensionRepository> repos() {
    return Collections.unmodifiableSortedMap(repos);
  }

  public SortedMap<DimensionId, String> heads() throws IOException {
    var r = new TreeMap<DimensionId, String>();
    for (var e : repos.entrySet()) r.put(e.getKey(), e.getValue().refs().head());
    return r;
  }

  public SortedMap<DimensionId, RefStore.Commit> resolve(String revision) throws IOException {
    var result = new TreeMap<DimensionId, RefStore.Commit>();
    for (var e : repos.entrySet()) {
      var refs = e.getValue().refs();
      result.put(e.getKey(), refs.readCommit(refs.resolve(revision)));
    }
    validate(result); return result;
  }

  private RepositoryGroup(DimensionRepository repository) {
    owner = false; root = repository.directory(); repos.put(repository.dimension(), repository);
  }

  RepositoryGroup dimension(DimensionId dimension) { return new RepositoryGroup(repos.get(dimension)); }

  public void validate(Map<DimensionId, RefStore.Commit> commits) throws IOException {
    if (!commits.keySet().equals(repos.keySet())) throw new IOException("快照必須涵蓋已開啟的維度組");
    for (var e : commits.entrySet()) if (!e.getValue().metadata().dimension().equals(e.getKey()))
      throw new IOException("commit 維度不符");
  }

  @FunctionalInterface
  public interface GroupPin {
    String resolve(DimensionId dimension, UUID snapshot) throws IOException;
  }

  /** 舊 API 的唯讀相容入口；只驗證 commit 所屬維度，UUID 不再構成約束。 */
  public static void validateSnapshot(Map<DimensionId, RefStore.Commit> commits, GroupPin pins)
      throws IOException {
    if (commits.isEmpty()) throw new IOException("快照清單不可空");
    for (var e : commits.entrySet()) if (!e.getValue().metadata().dimension().equals(e.getKey()))
      throw new IOException("commit 維度不符");
  }



  public SortedSet<String> tags() throws IOException {
    var names = new TreeSet<String>();
    for (var repo : repos.values())
      for (String ref : repo.refs().refsByPrefix("refs/tags/").keySet())
        names.add(ref.substring(10));
    return names;
  }

  public void tag(
      String name,
      String revision,
      String message,
      CommitMetadata.Identity author,
      boolean delete,
      boolean dryRun)
      throws IOException {
    if (repos.size() > 1) {
      for (var id : repos.keySet()) dimension(id).tag(name, revision, message, author, delete, dryRun);
      return;
    }
    for (var repo : repos.values()) repo.requireLegacyComplete();
    JGitStore.validateBranch(name);
    var targets = delete ? null : resolve(revision == null ? "HEAD" : revision);
    for (var r : repos.values()) {
      boolean exists = r.refs().refsByPrefix("refs/tags/").containsKey("refs/tags/" + name);
      if (exists != delete) throw new IOException(delete ? "某維度缺少 tag" : "tag 已存在");
    }
    if (dryRun) return;
    var changes = new ArrayList<Map<String, Object>>();
    String ref = "refs/tags/" + name;
    for (var e : repos.entrySet()) {
      var refs = e.getValue().refs();
      String old = refs.refsByPrefix("refs/tags/").get(ref);
      String target =
          delete ? null : refs.tagObject(name, targets.get(e.getKey()).id(), message, author);
      var row = new LinkedHashMap<String, Object>();
      row.put("dimension", e.getKey().value());
      row.put("ref", ref);
      row.put("old", old);
      row.put("target", target);
      changes.add(row);
    }
    var journal = new LinkedHashMap<String, Object>();
    journal.put("state", "PUBLISHING");
    journal.put("changes", changes);
    OperationState.write(root.resolve("tag-state.yml"), journal);
    try {
      for (var c : changes)
        repos
            .get(new DimensionId((String) c.get("dimension")))
            .refs()
            .updateRef(ref, (String) c.get("old"), (String) c.get("target"));
      journal.put("state", "COMPLETE");
      OperationState.write(root.resolve("tag-state.yml"), journal);
    } catch (IOException ex) {
      try {
        recoverTags();
      } catch (IOException rollback) {
        ex.addSuppressed(rollback);
      }
      throw ex;
    }
  }

  private void recoverTags() throws IOException {
    Path path = root.resolve("tag-state.yml");
    var journal = OperationState.read(path);
    if (journal.isEmpty() || "COMPLETE".equals(journal.get("state"))) return;
    if (!(journal.get("changes") instanceof List<?> changes))
      throw new IOException("tag journal 無效");
    // 先檢查 journal 中的 lease，第三方改動時不能覆寫。
    for (Object item : changes) {
      if (!(item instanceof Map<?, ?> c)) throw new IOException("tag journal 無效");
      var repo = repos.get(new DimensionId((String) c.get("dimension")));
      if (repo == null) throw new IOException("tag 恢復缺少維度");
      String current = repo.refs().refsByPrefix("refs/tags/").get((String) c.get("ref"));
      if (!Objects.equals(current, c.get("old")) && !Objects.equals(current, c.get("target")))
        throw new IOException("tag 恢復遭並行更新阻擋");
    }
    for (Object item : changes) {
      var c = (Map<?, ?>) item;
      var refs = repos.get(new DimensionId((String) c.get("dimension"))).refs();
      String ref = (String) c.get("ref");
      String current = refs.refsByPrefix("refs/tags/").get(ref);
      if (!Objects.equals(current, c.get("old")))
        refs.updateRef(ref, current, (String) c.get("old"));
    }
    journal.put("state", "COMPLETE");
    journal.put("recovered", true);
    OperationState.write(path, journal);
  }

  @Override
  public void close() throws IOException {
    if (!owner) return;
    IOException error = null;
    for (var r : repos.values())
      try {
        r.close();
      } catch (IOException e) {
        error = e;
      }

    if (error != null) throw error;
  }
}
