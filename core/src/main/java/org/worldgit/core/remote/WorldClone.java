package org.worldgit.core.remote;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.worldgit.core.config.WorldGitConfig;
import org.worldgit.core.model.*;
import org.worldgit.core.service.*;
import org.worldgit.core.store.*;

/** 先在相鄰暫存目錄下載/驗證/組裝，成功才 rename。失敗不留下可誤開的半成品。 */
public final class WorldClone {
  public record Result(
      Path world,
      Set<DimensionId> dimensions,
      WorldAssembler.Result assembly,
      Map<DimensionId, List<GitTransfer.PackSize>> packs) {}

  public static Result cloneWorld(
      RemoteSpec remote,
      Path destination,
      String branch,
      Set<DimensionId> selected,
      Credentials credentials,
      WorldAssembler.Budget budget)
      throws IOException {
    JGitStore.validateBranch(branch);
    Path dest = destination.toAbsolutePath().normalize();
    if (Files.exists(dest)) throw new IOException("clone 目的地已存在");
    Files.createDirectories(dest.getParent());
    Path temp = Files.createTempDirectory(dest.getParent(), ".wgit-clone-");
    var paths = new TreeMap<DimensionId, Path>();
    var packs = new TreeMap<DimensionId, List<GitTransfer.PackSize>>();
    try {
      Path root = temp.resolve(".worldgit");
      Files.createDirectories(root);
      paths.put(DimensionId.OVERWORLD, root.resolve(DimensionId.OVERWORLD.directoryName()));
      initialize(paths.get(DimensionId.OVERWORLD), branch);
      RemoteSpec.write(root, new TreeMap<>(Map.of("origin", remote)));
      try (var r = new WorldRemotes(root, paths, credentials)) {
        var f = r.fetch("origin", false);
        if (!f.success()) throw new IOException(f.error());
        packs.putAll(f.packs());
      }
      var dimensions = new TreeSet<DimensionId>();
      dimensions.add(DimensionId.OVERWORLD);
      try (var group = new RepositoryGroup(root, paths)) {
        var repo = group.repos().get(DimensionId.OVERWORLD);
        String tip = repo.refs().resolve("refs/remotes/origin/" + branch);
        String tree = repo.refs().readCommit(tip).tree();
        var manifest = TreeEditor.find(repo.objects(), tree, "dimensions");
        if (manifest != null) {
          var map =
              SafeYaml.parse(
                  new String(
                      repo.objects().readBlob(manifest.id()),
                      java.nio.charset.StandardCharsets.UTF_8));
          if (!(map.get("dimensions") instanceof Map<?, ?> m))
            throw new IOException("dimensions 清單無效");
          for (Object id : m.keySet())
            try {
              dimensions.add(new DimensionId((String) id));
            } catch (RuntimeException e) {
              throw new IOException("dimensions 維度無效");
            }
        }
      }
      if (dimensions.size() > 32) throw new IOException("最多 32 維度");
      if (selected != null && !dimensions.containsAll(selected)) throw new IOException("找不到指定維度");
      var included = new TreeSet<>(selected == null ? dimensions : selected);
      // metadata 的其他維度設定也需可解析，下載的 repo 以選取集合為限。
      for (var id : included)
        if (!id.equals(DimensionId.OVERWORLD)) {
          Path repo = root.resolve(id.directoryName());
          initialize(repo, branch);
          paths.put(id, repo);
        }
      try (var r = new WorldRemotes(root, paths, credentials)) {
        var f = r.fetch("origin", false);
        if (!f.success()) throw new IOException(f.error());
        f.packs()
            .forEach(
                (d, p) ->
                    packs.merge(
                        d,
                        p,
                        (a, b) -> {
                          var all = new ArrayList<>(a);
                          all.addAll(b);
                          return all;
                        }));
      }
      WorldAssembler.Result assembly;
      try (var group = new RepositoryGroup(root, paths)) {
        var commits = new TreeMap<DimensionId, RefStore.Commit>();
        for (var e : group.repos().entrySet()) {
          var repo = e.getValue();
          String tip = repo.refs().resolve("refs/remotes/origin/" + branch);
          var commit = repo.refs().readCommit(tip);
          commits.put(e.getKey(), commit);
          repo.refs().updateRef("refs/heads/" + branch, null, tip);
          String rules = org.worldgit.core.merge.TreeFilter.rules(repo.objects(), commit.tree());
          WorldGitConfig.write(repo.ignorePath(), rules);
          String config =
              e.getKey().equals(DimensionId.OVERWORLD) ? "world-meta/worldgit.yml" : "worldgit.yml";
          var c = TreeEditor.find(repo.objects(), commit.tree(), config);
          if (c != null) {
            String yaml =
                new String(
                    repo.objects().readBlob(c.id()), java.nio.charset.StandardCharsets.UTF_8);
            WorldGitConfig.readRepo(yaml, config);
            WorldGitConfig.write(repo.configPath(), yaml);
            if (WorldGitConfig.readRepo(yaml, config).track()
                == WorldGitConfig.Track.MODIFIED_ONLY) {
              // sparse 歷史已保存的 chunk 全數視為曾編輯；未存地形重生後不可自動納入。
              org.worldgit.core.capture.ModifiedChunks.write(
                  repo.directory(),
                  org.worldgit.core.capture.ModifiedChunks.tree(repo.objects(), commit.tree()));
            }
          }
        }
        assembly = new WorldAssembler(budget).assemble(group, commits, temp, included);
      }
      if (!included.contains(DimensionId.OVERWORLD))
        Files.move(paths.get(DimensionId.OVERWORLD), root.resolve("metadata"));
      if (selected != null) {
        OperationState.write(
            root.resolve("clone-selection.yml"),
            Map.of(
                "dimensions",
                included.stream().map(DimensionId::value).toList(),
                "world-dimensions",
                dimensions.stream().map(DimensionId::value).toList(),
                "metadata-only",
                !included.contains(DimensionId.OVERWORLD)));
        var manifest = new TreeMap<String, String>();
        for (var id : dimensions)
          if (!id.equals(DimensionId.OVERWORLD))
            manifest.put(id.value(), "../" + id.directoryName());
        OperationState.write(
            root.resolve("dimension-manifest.yml"), Map.of("dimensions", manifest));
      }
      Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE);
      return new Result(dest, included, assembly, packs);
    } finally {
      WorldAssembler.deleteTree(temp);
    }
  }

  private static void initialize(Path path, String branch) throws IOException {
    try (var store = new JGitStore(path, true)) {
      if (!branch.equals("main"))
        store.checkout(store.headState(), new RefStore.Head(null, branch));
    }
  }
}
