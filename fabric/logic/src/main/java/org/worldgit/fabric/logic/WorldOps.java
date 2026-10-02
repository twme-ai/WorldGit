package org.worldgit.fabric.logic;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.worldgit.core.anvil.WorldLayout;
import org.worldgit.core.capture.ScanIndex;
import org.worldgit.core.capture.SnapshotSource;
import org.worldgit.core.config.WorldGitConfig;
import org.worldgit.core.diff.*;
import org.worldgit.core.model.*;
import org.worldgit.core.service.DimensionRepository;
import org.worldgit.core.service.WorldRepositories;
import org.worldgit.core.store.RefStore;

/**
 * 線上端（Fabric、之後也可給其他線上平台）的世界級操作：每個維度一個 repo，共用 snapshot UUID，
 * 逐維度回報成功／沒變動／失敗。與 {@link WorldRepositories} 的差別是 commit 來源為 MOD，
 * 並可帶入 auto 旗標與多人作者歸屬（CLI 版沒有這些）。所有方法是同步阻塞的，呼叫端（repo executor）
 * 負責排程，絕不可在伺服器執行緒呼叫。
 */
public final class WorldOps {
  /** 一次 commit 的身分與歸屬。contributions 以維度分組，只含該維度被改的 chunk。 */
  public record Context(
      CommitMetadata.Identity author,
      CommitMetadata.Identity committer,
      boolean auto,
      Map<DimensionId, List<CommitMetadata.Contribution>> contributions) {
    public Context {
      Objects.requireNonNull(author);
      Objects.requireNonNull(committer);
      contributions = Map.copyOf(contributions);
    }

    public static Context of(CommitMetadata.Identity who) {
      return new Context(who, who, false, Map.of());
    }
  }

  /** 世界還沒有任何已 init 的維度（指令層把它翻成可翻譯的錯誤）。 */
  public static final class NotInitializedException extends IOException {
    public NotInitializedException() {
      super("世界尚未 init");
    }
  }

  public record LogRow(
      UUID snapshot,
      Instant time,
      String author,
      String message,
      boolean auto,
      SortedMap<DimensionId, String> commits) {}

  private final WorldLayout layout;
  private final Function<WorldLayout.Dimension, SnapshotSource> sources;
  private final WorldRepositories repositories;

  public WorldOps(WorldLayout layout, Function<WorldLayout.Dimension, SnapshotSource> sources) {
    this.layout = layout;
    this.sources = Objects.requireNonNull(sources);
    this.repositories = new WorldRepositories(layout, sources);
  }

  public WorldLayout layout() {
    return layout;
  }

  public Path root() {
    return repositories.root();
  }

  public SortedMap<DimensionId, Path> tracked() {
    return repositories.tracked();
  }

  public boolean initialized() {
    return !tracked().isEmpty();
  }

  public WorldGitConfig.Local local() throws IOException {
    return WorldGitConfig.readLocal(root().resolve("worldgit.yml"));
  }

  private SortedMap<DimensionId, Path> selectTracked(DimensionId selected) throws IOException {
    var tracked = new TreeMap<>(tracked());
    if (selected != null) {
      Path path = tracked.get(selected);
      if (path == null) throw new IOException("維度尚未 init：" + selected);
      tracked.clear();
      tracked.put(selected, path);
    }
    if (tracked.isEmpty()) throw new NotInitializedException();
    return tracked;
  }

  /**
   * 對尚未 init 的維度建立 repo 並做第一次完整快照；已 init 的維度略過（回報 value=null、error=null），
   * 因此世界新生成的維度（例如第一次進地獄之後）可以再執行一次補上。
   */
  public WorldRepositories.Batch<DimensionRepository.CommitResult> init(
      DimensionId selected,
      String template,
      WorldGitConfig.Track track,
      CommitMetadata.Identity author,
      String message)
      throws IOException {
    UUID snapshot = UUID.randomUUID();
    var result = new TreeMap<DimensionId, WorldRepositories.Outcome<DimensionRepository.CommitResult>>();
    var candidates = new TreeMap<DimensionId, Path>();
    layout
        .dimensions()
        .keySet()
        .forEach(id -> candidates.put(id, root().resolve(id.directoryName())));
    if (selected != null) {
      if (!candidates.containsKey(selected)) throw new IOException("找不到維度：" + selected);
      candidates.keySet().retainAll(Set.of(selected));
    }
    var tracked = tracked();
    var fresh = new TreeMap<DimensionId, Path>();
    for (var e : candidates.entrySet()) {
      if (tracked.containsKey(e.getKey()))
        result.put(e.getKey(), new WorldRepositories.Outcome<>(null, null));
      else fresh.put(e.getKey(), e.getValue());
    }
    for (var e : fresh.entrySet())
      try (var repo = new DimensionRepository(e.getValue(), e.getKey(), true)) {
        repo.initialize(template, track);
      } catch (Exception ex) {
        result.put(e.getKey(), new WorldRepositories.Outcome<>(null, error(ex)));
      }
    // 主世界的 dimensions 清單需要包含這次新增的所有維度。
    var manifest = new TreeMap<>(repositories.manifest());
    for (var id : fresh.keySet()) if (!id.equals(DimensionId.OVERWORLD)) manifest.put(id, "../" + id.directoryName());
    for (var e : fresh.entrySet())
      if (!result.containsKey(e.getKey()))
        try (var repo = new DimensionRepository(e.getValue(), e.getKey(), false);
            var source = sources.apply(layout.dimensions().get(e.getKey()))) {
          var meta =
              metadata(
                  source,
                  author,
                  author,
                  message,
                  snapshot,
                  e.getKey(),
                  false,
                  List.of());
          result.put(
              e.getKey(),
              new WorldRepositories.Outcome<>(repo.commit(source, manifest, meta, tolerance()), null));
        } catch (Exception ex) {
          result.put(e.getKey(), new WorldRepositories.Outcome<>(null, error(ex)));
        }
    pinGroup(snapshot, result);
    return new WorldRepositories.Batch<>(snapshot, result);
  }

  public WorldRepositories.Batch<DimensionRepository.CommitResult> commit(
      DimensionId selected, String message, Context context) throws IOException {
    UUID snapshot = UUID.randomUUID();
    double tolerance = tolerance();
    var result = new TreeMap<DimensionId, WorldRepositories.Outcome<DimensionRepository.CommitResult>>();
    var manifest = repositories.manifest();
    for (var e : selectTracked(selected).entrySet())
      try (var repo = new DimensionRepository(e.getValue(), e.getKey(), false);
          var source = sources.apply(layout.dimensions().get(e.getKey()))) {
        var meta =
            metadata(
                source,
                context.author(),
                context.committer(),
                message,
                snapshot,
                e.getKey(),
                context.auto(),
                context.contributions().getOrDefault(e.getKey(), List.of()));
        result.put(
            e.getKey(),
            new WorldRepositories.Outcome<>(repo.commit(source, manifest, meta, tolerance), null));
      } catch (Exception ex) {
        result.put(e.getKey(), new WorldRepositories.Outcome<>(null, error(ex)));
      }
    pinGroup(snapshot, result);
    return new WorldRepositories.Batch<>(snapshot, result);
  }

  public WorldRepositories.Batch<DimensionRepository.Status> status(
      DimensionId selected, boolean full) throws IOException {
    double tolerance = tolerance();
    var result = new TreeMap<DimensionId, WorldRepositories.Outcome<DimensionRepository.Status>>();
    var manifest = repositories.manifest();
    for (var e : selectTracked(selected).entrySet())
      try (var repo = new DimensionRepository(e.getValue(), e.getKey(), false);
          var source = sources.apply(layout.dimensions().get(e.getKey()))) {
        result.put(
            e.getKey(),
            new WorldRepositories.Outcome<>(repo.status(source, manifest, tolerance, full), null));
      } catch (Exception ex) {
        result.put(e.getKey(), new WorldRepositories.Outcome<>(null, error(ex)));
      }
    return new WorldRepositories.Batch<>(null, result);
  }

  /**
   * 單一維度的 diff：0 個 revision = HEAD → 工作世界；1 個 = 該 commit → 工作世界；2 個 = commit → commit。
   * window 不為 null 時只展開那些 chunk 的方塊（Detail.BLOCKS）。
   */
  public WorldDiff diff(
      DimensionId dimension,
      List<String> revisions,
      DiffEngine.Detail detail,
      Set<ChunkPos> window)
      throws IOException {
    Path path = tracked().get(dimension);
    if (path == null) throw new IOException("維度尚未 init：" + dimension);
    double tolerance = tolerance();
    try (var repo = new DimensionRepository(path, dimension, false)) {
      var engine = new DiffEngine(repo.objects());
      if (revisions.size() == 2) {
        String a = repo.refs().readCommit(repo.refs().resolve(revisions.get(0))).tree();
        String b = repo.refs().readCommit(repo.refs().resolve(revisions.get(1))).tree();
        return engine.compare(dimension, a, b, tolerance, detail, window);
      }
      try (var source = sources.apply(layout.dimensions().get(dimension))) {
        repo.status(source, repositories.manifest(), tolerance, false);
      }
      String tree = ScanIndex.read(path.resolve("worldgit.index")).tree();
      String before = revisions.isEmpty() ? repo.refs().head() : repo.refs().resolve(revisions.getFirst());
      String beforeTree = before == null ? null : repo.refs().readCommit(before).tree();
      return engine.compare(dimension, beforeTree, tree, tolerance, detail, window);
    }
  }

  /** 唯讀 preview 的方向：工作世界 → 目標 commit；可在解碼前裁切 chunk。 */
  public WorldDiff revisionDiff(DimensionId dimension, String revision, DiffEngine.Detail detail, Set<ChunkPos> window) throws IOException {
    Path path=tracked().get(dimension);
    if(path==null) throw new NotInitializedException();
    try(var repo=new DimensionRepository(path,dimension,false);
        var source=sources.apply(layout.dimensions().get(dimension))) {
      String working=repo.workingTree(source,repositories.manifest(),tolerance());
      String target=repo.refs().readCommit(repo.refs().resolve(revision)).tree();
      return new DiffEngine(repo.objects()).compare(dimension,working,target,tolerance(),detail,window);
    }
  }
  private void pinGroup(UUID snapshot, SortedMap<DimensionId,WorldRepositories.Outcome<DimensionRepository.CommitResult>> result) throws IOException {
    if(result.values().stream().anyMatch(e->!e.success())) return;
    for(var entry:tracked().entrySet()) try(var repo=new DimensionRepository(entry.getValue(),entry.getKey(),false)) {
      String head=repo.refs().head();
      if(head!=null) repo.refs().updateRef("refs/worldgit/groups/"+snapshot,null,head);
    }
  }

  /** 依 snapshot 分組的歷史，新的在前。 */
  public List<LogRow> log(DimensionId selected, int limit) throws IOException {
    var groups = new HashMap<UUID, List<RefStore.Commit>>();
    for (var e : selectTracked(selected).entrySet())
      try (var repo = new DimensionRepository(e.getValue(), e.getKey(), false)) {
        for (var c : repo.log(limit))
          groups.computeIfAbsent(c.metadata().snapshot(), k -> new ArrayList<>()).add(c);
      }
    var rows = new ArrayList<LogRow>();
    for (var e : groups.entrySet()) {
      var commits = e.getValue();
      commits.sort(Comparator.comparing(c -> c.metadata().dimension()));
      var first = commits.getFirst().metadata();
      var map = new TreeMap<DimensionId, String>();
      commits.forEach(c -> map.put(c.metadata().dimension(), c.id()));
      rows.add(
          new LogRow(
              e.getKey(),
              commits.stream().map(c -> c.metadata().time()).max(Comparator.naturalOrder()).orElseThrow(),
              first.author().name(),
              first.message(),
              first.auto(),
              map));
    }
    rows.sort(Comparator.comparing(LogRow::time).reversed());
    return rows.subList(0, Math.min(limit, rows.size()));
  }

  private double tolerance() throws IOException {
    return local().entityTolerance();
  }

  private CommitMetadata metadata(
      SnapshotSource source,
      CommitMetadata.Identity author,
      CommitMetadata.Identity committer,
      String message,
      UUID snapshot,
      DimensionId dimension,
      boolean auto,
      List<CommitMetadata.Contribution> contributions)
      throws IOException {
    return new CommitMetadata(
        author,
        committer,
        message,
        Instant.now(),
        source.dataVersion(),
        dimension,
        CommitMetadata.Source.MOD,
        auto,
        snapshot,
        contributions);
  }

  public static String error(Exception ex) {
    return ex.getMessage() == null || ex.getMessage().isBlank()
        ? ex.getClass().getSimpleName()
        : ex.getMessage();
  }
}
