package org.worldgit.core.service;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.worldgit.core.anvil.*;
import org.worldgit.core.config.*;
import org.worldgit.core.model.*;
import org.worldgit.core.store.*;

/** 各維度 repo 獨立；批次 id 僅用於回報，保留每個維度已成功的 commit。 */
public final class WorldRepositories {
  public record Outcome<T>(T value, String error) {
    public boolean success() {
      return error == null;
    }
  }

  public record Batch<T>(UUID snapshot, SortedMap<DimensionId, Outcome<T>> dimensions) {
    public Batch {
      dimensions = Collections.unmodifiableSortedMap(new TreeMap<>(dimensions));
    }

    public boolean success() {
      return dimensions.values().stream().allMatch(Outcome::success);
    }
  }

  private final WorldLayout layout;
  private final java.util.function.Function<
          WorldLayout.Dimension, org.worldgit.core.capture.SnapshotSource>
      sourceFactory;

  public WorldRepositories(WorldLayout layout) {
    this(layout, dim -> new OfflineSnapshotSource(layout, dim));
  }

  public WorldRepositories(
      WorldLayout layout,
      java.util.function.Function<WorldLayout.Dimension, org.worldgit.core.capture.SnapshotSource>
          sourceFactory) {
    this.layout = layout;
    this.sourceFactory = java.util.Objects.requireNonNull(sourceFactory);
  }

  public Path root() {
    return layout.repositoryRoot();
  }

  public SortedMap<DimensionId, Path> tracked() {
    var result = new TreeMap<DimensionId, Path>();
    layout
        .dimensions()
        .forEach(
            (id, dim) -> {
              Path path = layout.repository(id);
              if (!Files.isRegularFile(path.resolve("HEAD")))
                for (Path legacy : layout.legacyRepositories(id))
                  if (Files.isRegularFile(legacy.resolve("HEAD"))) { path = legacy; break; }
              if (Files.isRegularFile(path.resolve("HEAD"))) result.put(id, path);
            });
    return result;
  }

  public record Initializable(DimensionId dimension, Path directory, Path repository, boolean initialized) {}

  public List<Initializable> initializable() {
    var tracked = tracked();
    return layout.dimensions().values().stream().map(d ->
        new Initializable(d.id(), d.directory(), layout.repository(d.id()), tracked.containsKey(d.id()))).toList();
  }

  public Map<DimensionId, String> manifest() throws IOException {
    Path retained = root().resolve("dimension-manifest.yml");
    if (Files.exists(retained)) {
      var document = OperationState.read(retained);
      if (!(document.get("dimensions") instanceof Map<?, ?> map) || map.size() > 32)
        throw new IOException("維度宣告清單無效");
      var result = new TreeMap<DimensionId, String>();
      for (var e : map.entrySet()) {
        if (!(e.getKey() instanceof String id) || !(e.getValue() instanceof String path))
          throw new IOException("維度宣告清單型別無效");
        var dimension = new DimensionId(id);
        if (!path.equals("../" + dimension.directoryName())) throw new IOException("維度宣告路徑無效");
        result.put(dimension, path);
      }
      return result;
    }
    var m = new TreeMap<DimensionId, String>();
    // 宣告可發現的維度，不表示它已 init，也不要求相同分支／snapshot。
    // 主世界先 init、其他維度稍後 init/push 時，不必再修改主世界歷史才能 clone。
    layout.dimensions().keySet().forEach(id -> {
      if (!id.equals(DimensionId.OVERWORLD)) m.put(id, "../" + id.directoryName());
    });
    return m;
  }

  private SortedMap<DimensionId, Path> select(DimensionId selected, boolean init)
      throws IOException {
    var result = init ? new TreeMap<DimensionId, Path>() : tracked();
    if (init) {
      var existing = tracked();
      layout.dimensions().forEach((id, d) -> result.put(id, existing.getOrDefault(id, layout.repository(id))));
    }
    if (selected != null) {
      Path path = result.get(selected);
      if (path == null) throw new IOException("找不到" + (init ? "" : "已 init 的") + "維度：" + selected);
      result.clear();
      result.put(selected, path);
    }
    if (result.isEmpty()) throw new IOException("世界尚未 init");
    return result;
  }

  public Batch<DimensionRepository.CommitResult> init(
      DimensionId selected,
      String template,
      WorldGitConfig.Track track,
      CommitMetadata.Identity author)
      throws IOException {
    return initDimensions(Set.of(selected == null ? layout.currentDimension() : selected), template, track, author);
  }

  public Batch<DimensionRepository.CommitResult> initAll(String template, WorldGitConfig.Track track, CommitMetadata.Identity author) throws IOException {
    return initDimensions(layout.dimensions().keySet(), template, track, author);
  }

  public Batch<DimensionRepository.CommitResult> initAll(String template, WorldGitConfig.Track track, CommitMetadata.Identity author, WorldGitConfig.Entities entities) throws IOException {
    for (var dimension : layout.dimensions().keySet()) {
      Path path = layout.repository(dimension);
      if (Files.exists(path.resolve("HEAD"))) throw new IOException("維度已 init：" + dimension);
      WorldGitConfig.write(path.resolve("worldgit-repo.yml"), WorldGitConfig.write(new WorldGitConfig.Repo(track, entities)));
    }
    return initAll(template, track, author);
  }

  public Batch<DimensionRepository.CommitResult> initDimensions(Collection<DimensionId> dimensions, String template, WorldGitConfig.Track track, CommitMetadata.Identity author) throws IOException {
    return initDimensions(dimensions, template, track,
        (source, id) -> metadata(author, "初始化世界", UUID.randomUUID(), id, false, List.of()), 2);
  }

  /** 線上平台沿同一個明確選取入口 init，保留自己的 MOD／PLUGIN 身分與本機 tolerance。 */
  @FunctionalInterface
  public interface InitialMetadata {
    CommitMetadata create(org.worldgit.core.capture.SnapshotSource source, DimensionId dimension) throws IOException;
  }

  public Batch<DimensionRepository.CommitResult> initDimensions(Collection<DimensionId> dimensions, String template, WorldGitConfig.Track track,
      InitialMetadata metadataFactory,
      double tolerance) throws IOException {
    UUID snapshot = UUID.randomUUID();
    var result = new TreeMap<DimensionId, Outcome<DimensionRepository.CommitResult>>();
    var selectedRepos = new TreeMap<DimensionId, Path>();
    for (var id : dimensions) selectedRepos.putAll(select(id, true));
    // 先建立所有 bare repo，讓主世界的 dimensions 清單完整。
    for (var e : selectedRepos.entrySet())
      try (var repo = new DimensionRepository(e.getValue(), e.getKey(), true)) {
        repo.initialize(template, track);
      } catch (Exception ex) {
        result.put(e.getKey(), new Outcome<>(null, error(ex)));
      }
    for (var e : selectedRepos.entrySet())
      if (!result.containsKey(e.getKey()))
        try (var repo = new DimensionRepository(e.getValue(), e.getKey(), false);
            var source = sourceFactory.apply(layout.dimensions().get(e.getKey()))) {
          var m = metadataFactory.create(source, e.getKey());
          result.put(e.getKey(), new Outcome<>(repo.commit(source, manifest(), m, tolerance), null));
        } catch (Exception ex) {
          result.put(e.getKey(), new Outcome<>(null, error(ex)));
        }

    return new Batch<>(snapshot, result);
  }

  public Batch<DimensionRepository.CommitResult> commit(
      DimensionId selected, String message, CommitMetadata.Identity author, double tolerance)
      throws IOException {
    UUID snapshot = UUID.randomUUID();
    var result = new TreeMap<DimensionId, Outcome<DimensionRepository.CommitResult>>();
    for (var e : select(selected, false).entrySet())
      try (var repo = new DimensionRepository(e.getValue(), e.getKey(), false);
          var source = sourceFactory.apply(layout.dimensions().get(e.getKey()))) {
        result.put(
            e.getKey(),
            new Outcome<>(
                repo.commit(
                    source,
                    manifest(),
                    metadata(author, message, UUID.randomUUID(), e.getKey(), false, List.of()),
                    tolerance),
                null));
      } catch (Exception ex) {
        result.put(e.getKey(), new Outcome<>(null, error(ex)));
      }

    return new Batch<>(snapshot, result);
  }

  public Batch<DimensionRepository.Status> status(
      DimensionId selected, double tolerance, boolean full) throws IOException {
    var result = new TreeMap<DimensionId, Outcome<DimensionRepository.Status>>();
    for (var e : select(selected, false).entrySet())
      try (var repo = new DimensionRepository(e.getValue(), e.getKey(), false);
          var source = sourceFactory.apply(layout.dimensions().get(e.getKey()))) {
        result.put(
            e.getKey(), new Outcome<>(repo.status(source, manifest(), tolerance, full), null));
      } catch (Exception ex) {
        result.put(e.getKey(), new Outcome<>(null, error(ex)));
      }
    return new Batch<>(null, result);
  }

  private CommitMetadata metadata(
      CommitMetadata.Identity author,
      String message,
      UUID snapshot,
      DimensionId dimension,
      boolean auto,
      List<CommitMetadata.Contribution> contributions)
      throws IOException {
    return new CommitMetadata(
        author,
        author,
        message,
        Instant.now(),
        layout.dataVersion(),
        dimension,
        CommitMetadata.Source.CLI,
        auto,
        snapshot,
        contributions);
  }

  private static String error(Exception ex) {
    return ex.getMessage() == null || ex.getMessage().isBlank()
        ? ex.getClass().getSimpleName()
        : ex.getMessage();
  }
}
