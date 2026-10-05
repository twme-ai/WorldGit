package org.worldgit.core.remote;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.worldgit.core.config.WorldGitConfig;
import org.worldgit.core.model.*;
import org.worldgit.core.service.*;
import org.worldgit.core.store.*;

/** 維度獨立的分支選取；下載與組裝成功後才發布目的地。 */
public final class WorldClone {
  public record Result(Path world, Set<DimensionId> dimensions, WorldAssembler.Result assembly,
      Map<DimensionId, List<GitTransfer.PackSize>> packs) {}
  public static Result cloneWorld(RemoteSpec remote, Path destination, String branch,
      Set<DimensionId> selected, Credentials credentials, WorldAssembler.Budget budget) throws IOException {
    return cloneWorld(remote, destination, Map.of(), branch, selected, credentials, budget);
  }
  public static Result cloneWorld(RemoteSpec remote, Path destination, Map<DimensionId, String> branches,
      Set<DimensionId> selected, Credentials credentials, WorldAssembler.Budget budget) throws IOException {
    return cloneWorld(remote, destination, branches, null, selected, credentials, budget);
  }
  private static Result cloneWorld(RemoteSpec remote, Path destination, Map<DimensionId, String> branches,
      String fallback, Set<DimensionId> selected, Credentials credentials, WorldAssembler.Budget budget) throws IOException {
    if (selected != null && !selected.contains(DimensionId.OVERWORLD)) throw new IOException("clone 可開啟世界必須包含 minecraft:overworld");
    Path dest = destination.toAbsolutePath().normalize();
    if (Files.exists(dest)) throw new IOException("clone 目的地已存在");
    Files.createDirectories(dest.getParent()); Path temp = Files.createTempDirectory(dest.getParent(), ".wgit-clone-");
    var paths = new TreeMap<DimensionId, Path>(); var choices = new TreeMap<DimensionId, String>();
    var packs = new TreeMap<DimensionId, List<GitTransfer.PackSize>>();
    try {
      Path staging = temp.resolve(".wgit-repositories");
      download(remote, staging, DimensionId.OVERWORLD, branches.getOrDefault(DimensionId.OVERWORLD, fallback), credentials, paths, choices, packs);
      var available = new TreeSet<DimensionId>(RemoteDimensions.discover(remote, credentials)); available.add(DimensionId.OVERWORLD);
      available.addAll(branches.keySet()); // 舊宣告也允許明確指定新維度。
      try (var repo = new DimensionRepository(paths.get(DimensionId.OVERWORLD), DimensionId.OVERWORLD, false)) {
        var manifest = TreeEditor.find(repo.objects(), repo.refs().readCommit(repo.refs().head()).tree(), "dimensions");
        if (manifest != null) {
          var document = SafeYaml.parse(new String(repo.objects().readBlob(manifest.id()), java.nio.charset.StandardCharsets.UTF_8));
          if (!(document.get("dimensions") instanceof Map<?, ?> map) || map.size() > 32) throw new IOException("dimensions 清單無效");
          for (Object key : map.keySet()) available.add(new DimensionId((String)key));
        }
      }
      if (selected != null && !available.containsAll(selected)) throw new IOException("找不到指定維度");
      for (var id : new TreeSet<>(selected == null ? available : selected)) if (!id.equals(DimensionId.OVERWORLD)) {
        try { download(remote, staging, id, branches.getOrDefault(id, fallback), credentials, paths, choices, packs); }
        catch (IOException ex) {
          if (selected != null || branches.containsKey(id) || !(ex instanceof UnavailableDimension || ex.getMessage() != null && ex.getMessage().matches("(?is).*(?:404|not found|not exist|not a git repository).*"))) throw ex;
          WorldAssembler.deleteTree(staging.resolve(id.directoryName()));
        }
      }
      if (!paths.keySet().containsAll(branches.keySet())) throw new IOException("指定分支的維度未下載");
      WorldAssembler.Result assembly;
      int version;
      try (var group = new RepositoryGroup(staging, paths)) {
        var commits = group.resolve("HEAD"); version = commits.get(DimensionId.OVERWORLD).metadata().mcDataVersion();
        assembly = new WorldAssembler(budget).assemble(group, commits, temp, null);
      }
      for (var entry : paths.entrySet()) {
        Path directory = entry.getKey().equals(DimensionId.OVERWORLD) ? temp : WorldAssembler.dimensionPath(temp, entry.getKey(), version);
        Files.createDirectories(directory); Files.move(entry.getValue(), directory.resolve(".worldgit"), StandardCopyOption.ATOMIC_MOVE);
      }
      WorldAssembler.deleteTree(staging); Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE);
      return new Result(dest, Collections.unmodifiableSet(new TreeSet<>(paths.keySet())), assembly, Collections.unmodifiableMap(packs));
    } finally { WorldAssembler.deleteTree(temp); }
  }
  private static final class UnavailableDimension extends IOException { UnavailableDimension(String message) { super(message); } }
  private static void download(RemoteSpec remote, Path staging, DimensionId dimension, String requested,
      Credentials credentials, Map<DimensionId, Path> paths, Map<DimensionId, String> choices,
      Map<DimensionId, List<GitTransfer.PackSize>> packs) throws IOException {
    org.worldgit.core.operation.OperationProgress.report(dimension, "download", 0, null, org.worldgit.core.operation.OperationProgress.Unit.BYTES);
    Path path = staging.resolve(dimension.directoryName()); String url = remote.expand(dimension);
    try (var ignored = new JGitStore(path, true)) {}
    String branch = requested;
    try (var transfer = new GitTransfer(path, url, credentials.resolve("origin", url))) {
      var refs = transfer.advertised(false);
      if (branch == null) {
        String head = refs.get("HEAD");
        branch = transfer.advertisedHeadBranch();
        if (branch != null && !refs.containsKey("refs/heads/"+branch)) branch=null;
        if (branch == null) branch = refs.entrySet().stream().filter(e -> e.getKey().startsWith("refs/heads/") && Objects.equals(e.getValue(), head))
            .map(e -> e.getKey().substring(11)).findFirst().orElse(refs.containsKey("refs/heads/main") ? "main" : null);
        if (branch == null) branch=refs.keySet().stream().filter(ref -> ref.startsWith("refs/heads/")).map(ref -> ref.substring(11)).findFirst().orElse(null);
      }
      if (branch == null || !refs.containsKey("refs/heads/" + branch)) throw new UnavailableDimension("遠端維度缺少分支：" + dimension + " " + branch);
    }
    JGitStore.validateBranch(branch);
    RemoteSpec.write(path, new TreeMap<>(Map.of("origin", new RemoteSpec(url, new TreeMap<>()))));
    try (var remotes = new WorldRemotes(path, Map.of(dimension, path), credentials)) {
      var fetched = remotes.fetch("origin", false); if (!fetched.success()) throw new IOException(fetched.error()); packs.putAll(fetched.packs());
    }
    try (var repo = new DimensionRepository(path, dimension, false)) {
      String tip = repo.refs().resolve("refs/remotes/origin/" + branch);
      repo.refs().updateRef("refs/heads/" + branch, null, tip);
      repo.refs().checkout(repo.refs().headState(), new RefStore.Head(tip, branch));
      String tree = repo.refs().readCommit(tip).tree();
      WorldGitConfig.write(repo.ignorePath(), org.worldgit.core.merge.TreeFilter.rules(repo.objects(), tree));
      String configPath = dimension.equals(DimensionId.OVERWORLD) ? "world-meta/worldgit.yml" : "worldgit.yml";
      var config = TreeEditor.find(repo.objects(), tree, configPath);
      if (config != null) {
        String yaml = new String(repo.objects().readBlob(config.id()), java.nio.charset.StandardCharsets.UTF_8);
        var parsed = WorldGitConfig.readRepo(yaml, configPath); WorldGitConfig.write(repo.configPath(), yaml);
        if (parsed.track() == WorldGitConfig.Track.MODIFIED_ONLY) org.worldgit.core.capture.ModifiedChunks.write(path, org.worldgit.core.capture.ModifiedChunks.tree(repo.objects(), tree));
      }
      org.worldgit.core.capture.PlayerTouchedEntities.restore(repo.objects(), tree, path);
    }
    paths.put(dimension, path); choices.put(dimension, branch);
  }
}
