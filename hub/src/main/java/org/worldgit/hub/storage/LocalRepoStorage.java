package org.worldgit.hub.storage;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.springframework.stereotype.Component;
import org.worldgit.core.model.DimensionId;
import org.worldgit.hub.config.HubProperties;

/** 本機磁碟：/data/repos/{owner}/{world}/{namespace.path}.git。 */
@Component
public class LocalRepoStorage implements RepoStorage {
  private final Path root;
  private final Path cache;

  public LocalRepoStorage(HubProperties props) throws IOException {
    root = props.dataDir().toAbsolutePath().normalize().resolve("repos");
    cache = props.dataDir().toAbsolutePath().normalize().resolve("cache");
    Files.createDirectories(root);
    Files.createDirectories(cache);
  }

  @Override
  public Path repoPath(String owner, String world, DimensionId dimension) {
    return worldDir(owner, world).resolve(dimension.directoryName() + ".git");
  }

  private Path worldDir(String owner, String world) {
    NameRules.requireSlug(owner);
    NameRules.requireSlug(world);
    return root.resolve(owner).resolve(world);
  }

  @Override
  public boolean exists(String owner, String world, DimensionId dimension) {
    return Files.isRegularFile(repoPath(owner, world, dimension).resolve("HEAD"));
  }

  @Override
  public Path create(String owner, String world, DimensionId dimension) throws IOException {
    Path dir = repoPath(owner, world, dimension);
    if (Files.exists(dir.resolve("HEAD"))) return dir;
    Files.createDirectories(dir);
    try (Git git = Git.init().setBare(true).setDirectory(dir.toFile()).setInitialBranch("main").call()) {
      var config = git.getRepository().getConfig();
      config.setBoolean("http", null, "receivepack", true);
      config.setLong("pack", null, "packSizeLimit", 95_000_000L);
      config.setInt("gc", null, "auto", 0);
      config.save();
    } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
      throw new IOException("建立 repo 失敗：" + e.getMessage(), e);
    }
    return dir;
  }

  @Override
  public List<DimensionId> dimensions(String owner, String world) throws IOException {
    Path dir = worldDir(owner, world);
    if (!Files.isDirectory(dir)) return List.of();
    var result = new ArrayList<DimensionId>();
    try (Stream<Path> s = Files.list(dir)) {
      for (Path p : (Iterable<Path>) s::iterator) {
        String name = p.getFileName().toString();
        if (!name.endsWith(".git") || !Files.isRegularFile(p.resolve("HEAD"))) continue;
        DimensionId id = NameRules.dimensionFromDirectory(name.substring(0, name.length() - 4));
        if (id != null) result.add(id);
      }
    }
    Collections.sort(result);
    return result;
  }

  @Override
  public void deleteWorld(String owner, String world) throws IOException {
    Path dir = worldDir(owner, world);
    if (!Files.exists(dir)) return;
    try (Stream<Path> s = Files.walk(dir)) {
      for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) Files.delete(p);
    }
  }

  @Override
  public Path cacheDir() {
    return cache;
  }
}
