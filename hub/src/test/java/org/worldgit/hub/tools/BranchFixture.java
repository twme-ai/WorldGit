package org.worldgit.hub.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.SnapshotCodec;
import org.worldgit.core.store.*;

/** 可重跑的分支／compare 固定場景；只建立小型 bare repo，不依賴 Minecraft 伺服器。 */
public final class BranchFixture {
  public static final UUID INITIAL = UUID.fromString("00000000-0000-4000-8000-000000000001");
  public static final UUID FEATURE = UUID.fromString("00000000-0000-4000-8000-000000000002");
  public static final UUID MAIN = UUID.fromString("00000000-0000-4000-8000-000000000003");
  public static final int[] MAIN_CELL = {3, 65, 3};
  public static final List<DimensionId> DIMS = List.of(DimensionId.OVERWORLD, new DimensionId("minecraft:the_nether"), new DimensionId("minecraft:the_end"));
  private BranchFixture() {}

  public static Map<DimensionId, Path> create(Path directory) throws Exception {
    var paths = new LinkedHashMap<DimensionId, Path>();
    for (var d : DIMS) {
      Path path = directory.resolve(d.directoryName() + ".git");
      try (var store = new JGitStore(path, true)) {
        String baseTree = tree(store, d, "initial");
        String initial = store.commit(baseTree, null, metadata(d, INITIAL, "初始建築", 0));
        if (d.equals(DimensionId.OVERWORLD)) {
          ref(path, "side", initial);
          head(path, "feature");
          ref(path, "feature", initial);
          store.commit(tree(store, d, "feature"), initial, metadata(d, FEATURE, "feature：金塊建築、拆除石柱、鑽石改造", 1));
          head(path, "main");
          store.commit(tree(store, d, "main"), initial, metadata(d, MAIN, "main：新增綠寶石", 2));
        } else ref(path, "feature", initial);
        store.flush();
      }
      paths.put(d, path);
    }
    return paths;
  }

  public static CommitMetadata metadata(DimensionId d, UUID snapshot, String message, int minute) {
    var author = new CommitMetadata.Identity("建築測試者", "builder@example.test");
    return new CommitMetadata(author, author, message, Instant.parse("2026-10-01T08:00:00Z").plusSeconds(minute * 60L),
        4903, d, CommitMetadata.Source.HUB, false, snapshot, List.of());
  }

  public static String tree(JGitStore store, DimensionId d, String version) throws Exception {
    var blocks = new ArrayList<>(Collections.nCopies(4096, BlockState.AIR));
    for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) blocks.set((z << 4) | x, new BlockState("minecraft:stone_bricks"));
    for (int y = 1; y <= 4; y++) {
      blocks.set((y << 8) | (4 << 4) | 4, new BlockState("minecraft:stone"));
      blocks.set((y << 8) | (5 << 4) | 5, new BlockState("minecraft:stone"));
    }
    if (version.equals("main")) blocks.set((1 << 8) | (3 << 4) | 3, new BlockState("minecraft:emerald_block"));
    if (version.equals("feature")) {
      for (int y = 1; y <= 4; y++) {
        blocks.set((y << 8) | (4 << 4) | 4, BlockState.AIR);
        blocks.set((y << 8) | (5 << 4) | 5, new BlockState("minecraft:diamond_block"));
        for (int z = 8; z < 12; z++) for (int x = 8; x < 12; x++) blocks.set((y << 8) | (z << 4) | x, new BlockState("minecraft:gold_block"));
      }
    }
    String section = store.writeBlob(SnapshotCodec.section(new Section(blocks, Map.of())));
    String chunk = store.writeTree(List.of(new ObjectStore.Entry("s.4.bin", ObjectStore.Kind.BLOB, section)));
    String region = store.writeTree(List.of(new ObjectStore.Entry("c.0.0", ObjectStore.Kind.TREE, chunk)));
    var entries = new ArrayList<ObjectStore.Entry>();
    entries.add(new ObjectStore.Entry("r.0.0", ObjectStore.Kind.TREE, region));
    if (d.equals(DimensionId.OVERWORLD)) entries.add(new ObjectStore.Entry("dimensions", ObjectStore.Kind.BLOB,
        store.writeBlob("'minecraft:overworld': {}\n'minecraft:the_nether': {}\n'minecraft:the_end': {}\n".getBytes(StandardCharsets.UTF_8))));
    return store.writeTree(entries);
  }

  public static void ref(Path path, String branch, String id) throws Exception {
    try (var git = Git.open(path.toFile())) {
      var ref = git.getRepository().updateRef(Constants.R_HEADS + branch);
      ref.setNewObjectId(ObjectId.fromString(id));
      ref.setForceUpdate(true);
      var result = ref.update();
      if (!Set.of(RefUpdate.Result.NEW, RefUpdate.Result.FORCED, RefUpdate.Result.NO_CHANGE, RefUpdate.Result.FAST_FORWARD).contains(result))
        throw new IllegalStateException(result.toString());
    }
  }

  public static void head(Path path, String branch) throws Exception {
    try (var git = Git.open(path.toFile())) { git.getRepository().updateRef(Constants.HEAD).link(Constants.R_HEADS + branch); }
  }

  /** 全 chunk 移除的鬼影驗收；一般整合測試仍使用原本三個分支。 */
  public static void clearBranch(Path path) throws Exception {
    try (var store = new JGitStore(path, false)) {
      String parent = store.head();
      var dimensions = store.readTree(store.readCommit(parent).tree()).get("dimensions");
      ref(path, "clear", parent);
      head(path, "clear");
      try {
        store.commit(store.writeTree(dimensions == null ? List.of() : List.of(dimensions)), parent,
            metadata(DimensionId.OVERWORLD, UUID.fromString("00000000-0000-4000-8000-000000000004"), "移除整個 chunk", 3));
      } finally { head(path, "main"); }
    }
  }

  public static void main(String[] args) throws Exception {
    var paths = create(Path.of(args[0]));
    clearBranch(paths.get(DimensionId.OVERWORLD));
  }
}
