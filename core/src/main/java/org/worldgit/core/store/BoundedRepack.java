package org.worldgit.core.store;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.internal.storage.pack.PackWriter;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.storage.pack.PackConfig;

/** JGit 不實作 pack.packSizeLimit。以 zlib 最壞上界分組，禁用跨 pack delta/reuse，保證獨立有界 pack。 */
final class BoundedRepack {
  private BoundedRepack() {}

  static List<Long> run(FileRepository repo, long limit) throws IOException {
    Path packDir = repo.getDirectory().toPath().resolve("objects/pack");
    Files.createDirectories(packDir);
    var old = new HashSet<Path>();
    try (var files = Files.list(packDir)) {
      files
          .filter(p -> p.toString().endsWith(".pack") || p.toString().endsWith(".idx"))
          .forEach(old::add);
    }
    var installed = new HashSet<Path>();
    var sizes = new ArrayList<Long>();
    var reachable = new HashSet<String>();
    try (var walk = new ObjectWalk(repo);
        var reader = repo.newObjectReader()) {
      for (var ref : repo.getRefDatabase().getRefs())
        if (ref.getObjectId() != null) walk.markStart(walk.parseAny(ref.getObjectId()));
      var batch = new ArrayList<RevObject>();
      long bound = 32;
      RevObject object;
      while ((object = walk.next()) != null) {
        long n = bound(reader, object);
        if (n + 32 > limit) throw new IOException("單一物件超過 pack 上限：" + object.name());
        if (bound + n > limit && !batch.isEmpty()) {
          write(repo, batch, packDir, limit, installed, sizes);
          batch.clear();
          bound = 32;
        }
        batch.add(object);
        reachable.add(object.name());
        bound += n;
      }
      while ((object = walk.nextObject()) != null) {
        long n = bound(reader, object);
        if (n + 32 > limit) throw new IOException("單一物件超過 pack 上限：" + object.name());
        if (bound + n > limit && !batch.isEmpty()) {
          write(repo, batch, packDir, limit, installed, sizes);
          batch.clear();
          bound = 32;
        }
        batch.add(object);
        reachable.add(object.name());
        bound += n;
      }
      if (!batch.isEmpty()) write(repo, batch, packDir, limit, installed, sizes);
    }
    // 全部新 pack/idx 安裝完成才移除舊 pack；失敗時舊 pack 與 loose objects 均保留。
    for (Path p : old) if (!installed.contains(p)) Files.deleteIfExists(p);
    for (String id : reachable)
      Files.deleteIfExists(
          repo.getDirectory()
              .toPath()
              .resolve("objects/" + id.substring(0, 2) + "/" + id.substring(2)));
    return List.copyOf(sizes);
  }

  private static long bound(ObjectReader reader, RevObject object) throws IOException {
    long size = reader.getObjectSize(object.getId(), object.getType());
    // zlib compressBound 的上界，再保留 git object header 安全空間。
    return size + (size >> 12) + (size >> 14) + (size >> 25) + 96;
  }

  private static void write(
      FileRepository repo,
      List<RevObject> objects,
      Path dir,
      long limit,
      Set<Path> installed,
      List<Long> sizes)
      throws IOException {
    var config = new PackConfig(repo);
    config.setDeltaCompress(false);
    config.setReuseDeltas(false);
    config.setReuseObjects(false);
    config.setBuildBitmaps(false);
    config.setThreads(1);
    Path pack = Files.createTempFile(dir, "worldgit-", ".tmp"),
        idx = Files.createTempFile(dir, "worldgit-", ".tmp");
    try (var reader = repo.newObjectReader();
        var writer = new PackWriter(config, reader)) {
      writer.preparePack(objects.iterator());
      try (var out = Files.newOutputStream(pack)) {
        writer.writePack(NullProgressMonitor.INSTANCE, NullProgressMonitor.INSTANCE, out);
      }
      long size = Files.size(pack);
      if (size > limit) throw new IOException("pack 超過上限：" + size);
      try (var out = Files.newOutputStream(idx)) {
        writer.writeIndex(out);
      }
      String stem = "pack-" + writer.computeName().name();
      Path targetPack = dir.resolve(stem + ".pack"), targetIdx = dir.resolve(stem + ".idx");
      Files.move(
          pack, targetPack, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      Files.move(
          idx, targetIdx, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      installed.add(targetPack);
      installed.add(targetIdx);
      sizes.add(size);
    } finally {
      Files.deleteIfExists(pack);
      Files.deleteIfExists(idx);
    }
  }
}
