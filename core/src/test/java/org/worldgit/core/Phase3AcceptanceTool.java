package org.worldgit.core;

import static org.worldgit.core.merge.MergeReport.*;

import java.nio.file.*;
import java.util.*;
import org.worldgit.core.anvil.*;
import org.worldgit.core.apply.*;
import org.worldgit.core.config.*;
import org.worldgit.core.merge.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;
import org.worldgit.core.service.*;

/** 本機複本限定驗收／量測；不寫 baseline 或 experiments。 */
public final class Phase3AcceptanceTool {
  static final CommitMetadata.Identity AUTHOR =
      new CommitMetadata.Identity("Phase 3 acceptance", "worldgit@localhost");

  static void check(boolean ok, String why) {
    if (!ok) throw new AssertionError(why);
  }

  static WorldOperations.MergeOptions options(boolean noCommit) {
    return new WorldOperations.MergeOptions(
        noCommit, null, 1, false, AUTHOR, CommitMetadata.Source.CLI);
  }

  public static void main(String[] args) throws Exception {
    var layout = WorldLayout.discover(Path.of(args[1]));
    switch (args[0]) {
      case "roundtrip" -> roundtrip(layout);
      case "mutate" -> mutate(layout, args[2], false);
      case "conflict" -> mutate(layout, args[2], true);
      case "inspect" -> inspect(layout);
      case "shapes" -> {
        for (var cell : List.of(new Cell(15, 80, 0), new Cell(16, 80, 2))) {
          var s =
              Phase2AcceptanceTool.read(layout, cell.chunk())
                  .sections()
                  .getOrDefault(cell.sectionY(), Section.air());
          System.out.println(cell + " " + s.block(cell.index()).canonical());
        }
      }
      case "benchmark-prepare" -> benchmarkPrepare(layout, Integer.parseInt(args[2]));
      case "benchmark-merge" -> {
        long start = System.nanoTime();
        try (var ops = new WorldOperations(layout)) {
          var r = ops.merge("B", options(true));
          check(r.success(), r.error());
          System.out.printf(
              Locale.ROOT,
              "merge_seconds=%.3f regions=%d automatic_sections=%d%n",
              (System.nanoTime() - start) / 1e9,
              r.merging().regions().size(),
              r.reports().values().stream()
                  .mapToInt(MergeReport::automaticallyMergedSections)
                  .sum());
        }
      }
      default -> throw new IllegalArgumentException("未知命令");
    }
  }

  static void commit(WorldLayout layout, String msg) throws Exception {
    check(new WorldRepositories(layout).commit(null, msg, AUTHOR, 2).success(), msg);
  }

  static void mutate(WorldLayout layout, String side, boolean conflict) throws Exception {
    boolean a = side.equals("A");
    int bx = conflict ? 0 : a ? 0 : -32, bz = conflict ? 2 : a ? 0 : -32;
    int sy = 14;
    var pos = new ChunkPos(Math.floorDiv(bx, 16), Math.floorDiv(bz, 16));
    var raw = Phase2AcceptanceTool.raw(layout, pos);
    var snap =
        new ChunkNormalizer(IgnoreRules.none(), EntitySemantics.OFFLINE)
            .normalize(pos, raw, List.of());
    var s = snap.sections().getOrDefault(sy, Section.air());
    var blocks = new ArrayList<>(s.blocks());
    var bes = s.blockEntities();
    if (conflict) {
      for (int dy = 0; dy < 2; dy++) {
        int i = (bx & 15) | ((bz & 15) << 4) | (dy << 8);
        blocks.set(
            i,
            new BlockState(
                a ? "minecraft:oak_door" : "minecraft:iron_door",
                new TreeMap<>(
                    Map.of(
                        "half",
                        dy == 0 ? "lower" : "upper",
                        "facing",
                        "north",
                        "hinge",
                        "left",
                        "open",
                        "false",
                        "powered",
                        "false"))));
        bes.remove(i);
      }
      blocks.set(
          1 | (2 << 4),
          new BlockState(
              a ? "minecraft:oak_fence" : "minecraft:birch_fence",
              new TreeMap<>(
                  Map.of(
                      "east",
                      "false",
                      "west",
                      "false",
                      "north",
                      "false",
                      "south",
                      "false",
                      "waterlogged",
                      "false"))));
      blocks.set(
          2 | (2 << 4),
          new BlockState(
              a ? "minecraft:redstone_wire" : "minecraft:stone",
              a
                  ? new TreeMap<>(
                      Map.of(
                          "east", "none", "west", "none", "north", "none", "south", "none", "power",
                          "0"))
                  : new TreeMap<>()));
    } else {
      blocks.set(
          (bx & 15) | ((bz & 15) << 4),
          new BlockState(a ? "minecraft:gold_block" : "minecraft:diamond_block"));
      int chest = 1 | (1 << 4);
      blocks.set(chest, Phase2AcceptanceTool.chest());
      bes.put(chest, Phase2AcceptanceTool.chestNbt(a ? 4 : 9));
    }
    var secs = new TreeMap<Integer, ApplyPlan.SectionOp>();
    secs.put(
        sy, new ApplyPlan.SectionOp(sy, SnapshotCodec.section(new Section(blocks, bes)), null));
    Phase2AcceptanceTool.write(
        layout,
        pos,
        ChunkNbt.apply(
            raw,
            new ApplyPlan.ChunkOp(pos, false, secs, new TreeMap<>(), false, null, false, null),
            layout.dataVersion(),
            IgnoreRules.none()));
    if (!conflict) {
      // 每邊跨 chunk 的第二個建築與 UUID 實體。
      var other = new ChunkPos(a ? 1 : -1, a ? 0 : -2);
      raw = Phase2AcceptanceTool.raw(layout, other);
      snap =
          new ChunkNormalizer(IgnoreRules.none(), EntitySemantics.OFFLINE)
              .normalize(other, raw, List.of());
      s = snap.sections().getOrDefault(sy, Section.air());
      blocks = new ArrayList<>(s.blocks());
      blocks.set(0, new BlockState(a ? "minecraft:emerald_block" : "minecraft:lapis_block"));
      secs = new TreeMap<>();
      secs.put(
          sy,
          new ApplyPlan.SectionOp(
              sy, SnapshotCodec.section(new Section(blocks, s.blockEntities())), null));
      Phase2AcceptanceTool.write(
          layout,
          other,
          ChunkNbt.apply(
              raw,
              new ApplyPlan.ChunkOp(other, false, secs, new TreeMap<>(), false, null, false, null),
              layout.dataVersion(),
              IgnoreRules.none()));
      var dim = layout.dimensions().get(DimensionId.OVERWORLD);
      try (var w = new RegionWriter(dim.entities().resolve(other.regionName() + ".mca"))) {
        var root = w.read(other.regionIndex());
        if (root == null)
          root =
              new Nbt.Compound()
                  .with("DataVersion", layout.dataVersion())
                  .with("Position", new int[] {other.x(), other.z()});
        var values = new ArrayList<>(root.list("Entities").values());
        var entity = Phase2AcceptanceTool.armorStand(other.x() * 16 + .5, 226);
        entity.put("UUID", new int[] {0, 0x4000, 0x80000000, a ? 0x43 : 0x44});
        entity.put(
            "Pos", new Nbt.ListTag(6, List.of(other.x() * 16 + .5, 226., other.z() * 16 + .5)));
        values.add(entity);
        root.put("Entities", new Nbt.ListTag(10, values));
        w.write(other.regionIndex(), root, 2);
      }
    }
  }

  static void roundtrip(WorldLayout layout) throws Exception {
    check(
        new WorldRepositories(layout)
            .init(null, "creative", WorldGitConfig.Track.ALL, AUTHOR)
            .success(),
        "init");
    try (var ops = new WorldOperations(layout)) {
      ops.createBranch("base", null);
      ops.createBranch("B", null);
    }
    mutate(layout, "A", false);
    commit(layout, "A: BE/entity/cross chunk");
    try (var ops = new WorldOperations(layout)) {
      ops.createBranch("A", null);
      check(ops.switchTo("B", false, false, false, false).success(), "switch B");
    }
    mutate(layout, "B", false);
    commit(layout, "B: BE/entity/cross chunk");
    try (var ops = new WorldOperations(layout)) {
      check(ops.switchTo("A", false, false, false, false).success(), "switch A");
      ops.createBranch("A-original", null);
      var r = ops.merge("B", options(false));
      check(r.state().equals("COMPLETE"), "zero intervention merge " + r.error());
      check(r.reports().values().stream().allMatch(v -> v.regions().isEmpty()), "no conflicts");
      check(ops.verify("HEAD", null, Scope.all(), true).success(), "merged verify");
    }
    assertMerged(layout);
    check(
        Phase2AcceptanceTool.read(layout, new ChunkPos(0, 0))
            .sections()
            .get(14)
            .block(0)
            .name()
            .equals("minecraft:gold_block"),
        "A result");
    check(
        Phase2AcceptanceTool.read(layout, new ChunkPos(-2, -2))
            .sections()
            .get(14)
            .block(0)
            .name()
            .equals("minecraft:diamond_block"),
        "B result");
    try (var ops = new WorldOperations(layout)) {
      ops.createBranch("merged", null);
      check(ops.switchTo("base", false, false, false, false).success(), "base");
      ops.createBranch("D", null);
      ops.createBranch("C", null);
      check(ops.switchTo("C", false, false, false, false).success(), "C from base");
    }
    mutate(layout, "A", true);
    commit(layout, "C: doors/fence/redstone");
    try (var ops = new WorldOperations(layout)) {
      check(ops.switchTo("D", false, false, false, false).success(), "D");
    }
    mutate(layout, "B", true);
    commit(layout, "D: conflict");
    try (var ops = new WorldOperations(layout)) {
      check(ops.switchTo("C", false, false, false, false).success(), "C");
      var result = ops.merge("D", options(true));
      check(result.state().equals("MERGING"), result.error());
      var region = ops.merging().regions().getFirst();
      check(ops.merging().regions().size() == 1, "one connected region");
      check(
          region.blockCount() == 4 && region.bounds().equals(new BlockBox(0, 224, 2, 2, 225, 2)),
          "door pair bounds " + region);
      check(region.redstone(), "redstone warning");
    }
    try (var ops = new WorldOperations(layout)) {
      for (Choice c : List.of(Choice.THEIRS, Choice.BASE, Choice.OURS)) {
        check(ops.selectRegion(1, c, false, false).success(), "select " + c);
        String revision = c == Choice.THEIRS ? "D" : c == Choice.BASE ? "base" : "C";
        check(ops.verify(revision, null, Scope.all(), true).success(), "verify " + c);
      }
      check(ops.abortMerge(false).success(), "abort");
      check(ops.verify("C", null, Scope.all(), true).success(), "abort verify C");
    }
    try (var ops = new WorldOperations(layout)) {
      check(ops.switchTo("A-original", false, false, false, false).success(), "A patch");
      check(ops.cherryPick("B", options(false)).state().equals("COMPLETE"), "cherry B");
      check(ops.revert("B", options(false)).state().equals("COMPLETE"), "revert B");
      check(ops.verify("A-original", null, Scope.all(), true).success(), "patch roundtrip");
      check(ops.switchTo("C", false, false, false, false).success(), "C conflict");
      check(ops.cherryPick("D", options(false)).state().equals("MERGING"), "cherry conflict");
      check(ops.abortMerge(false).success(), "cherry abort");
      check(ops.revert("D", options(false)).state().equals("MERGING"), "revert conflict");
      check(ops.abortMerge(false).success(), "revert abort");
    }
    System.out.println(
        "PASS zero-conflict BE/entity/cross-chunk merge; durable doors/fence/redstone region;"
            + " ours/theirs/base; abort; clean/conflict cherry-pick/revert; verify=0");
  }

  static void benchmarkPrepare(WorldLayout layout, int conflicts) throws Exception {
    var worlds = new WorldRepositories(layout);
    check(worlds.init(null, "creative", WorldGitConfig.Track.ALL, AUTHOR).success(), "bench init");
    try (var ops = new WorldOperations(layout)) {
      ops.createBranch("B", null);
    }
    benchmarkMutate(layout, true, conflicts);
    commit(layout, "bench A");
    try (var ops = new WorldOperations(layout)) {
      ops.createBranch("A", null);
      check(ops.switchTo("B", false, false, false, false).success(), "bench B");
    }
    benchmarkMutate(layout, false, conflicts);
    commit(layout, "bench B");
    try (var ops = new WorldOperations(layout)) {
      check(ops.switchTo("A", false, false, false, false).success(), "bench A");
    }
  }

  static void benchmarkMutate(WorldLayout layout, boolean a, int conflicts) throws Exception {
    var dim = layout.dimensions().get(DimensionId.OVERWORLD);
    int count = 0;
    for (Path path : RegionFile.list(dim.region()))
      try (var w = new RegionWriter(path)) {
        for (int i = 0; i < 1024; i++)
          if (w.has(i)) {
            if (conflicts > 0 && count >= conflicts) continue;
            var pos = w.pos(i);
            int index = conflicts > 0 ? 0 : a ? 0 : 15;
            var blocks = new ArrayList<>(Collections.nCopies(4096, BlockState.AIR));
            blocks.set(
                index, new BlockState(a ? "minecraft:gold_block" : "minecraft:diamond_block"));
            long[] mask = new long[64];
            mask[index >>> 6] |= 1L << (index & 63);
            var ss = new TreeMap<Integer, ApplyPlan.SectionOp>();
            ss.put(
                14,
                new ApplyPlan.SectionOp(
                    14, SnapshotCodec.section(new Section(blocks, Map.of())), mask));
            w.write(
                i,
                ChunkNbt.apply(
                    w.read(i),
                    new ApplyPlan.ChunkOp(
                        pos, false, ss, new TreeMap<>(), false, null, false, null),
                    layout.dataVersion(),
                    IgnoreRules.none()),
                2);
            count++;
          }
      }
    check(count == (conflicts > 0 ? conflicts : 1000), "changed chunk count " + count);
    System.out.println("branch=" + (a ? "A" : "B") + " changed_chunks=" + count);
  }

  static void assertMerged(WorldLayout layout) throws Exception {
    var a = Phase2AcceptanceTool.read(layout, new ChunkPos(0, 0)).sections().get(14);
    var b = Phase2AcceptanceTool.read(layout, new ChunkPos(-2, -2)).sections().get(14);
    check(a.block(0).name().equals("minecraft:gold_block"), "merged A block");
    check(b.block(0).name().equals("minecraft:diamond_block"), "merged B block");
    check(
        Nbt.read(a.blockEntities().get(17)).list("Items").values().stream()
            .map(Nbt.Compound.class::cast)
            .anyMatch(
                item ->
                    item.string("id").equals("minecraft:diamond") && item.integer("count", 0) == 4),
        "merged A chest items");
    check(
        Nbt.read(b.blockEntities().get(17)).list("Items").values().stream()
            .map(Nbt.Compound.class::cast)
            .anyMatch(
                item ->
                    item.string("id").equals("minecraft:diamond") && item.integer("count", 0) == 9),
        "merged B chest items");
    check(
        Phase2AcceptanceTool.read(layout, new ChunkPos(1, 0))
            .sections()
            .get(14)
            .block(0)
            .name()
            .equals("minecraft:emerald_block"),
        "A cross chunk");
    check(
        Phase2AcceptanceTool.read(layout, new ChunkPos(-1, -2))
            .sections()
            .get(14)
            .block(0)
            .name()
            .equals("minecraft:lapis_block"),
        "B cross chunk");
    var counts = new HashMap<UUID, Integer>();
    var dim = layout.dimensions().get(DimensionId.OVERWORLD);
    for (Path path : RegionFile.list(dim.entities()))
      try (var file = new RegionFile(path)) {
        for (int i = 0; i < 1024; i++)
          if (file.has(i))
            for (Object value : file.read(i).list("Entities").values())
              counts.merge(EntityNormalizer.uuid((Nbt.Compound) value), 1, Integer::sum);
      }
    for (String suffix : List.of("43", "44"))
      check(
          counts.getOrDefault(UUID.fromString("00000000-0000-4000-8000-0000000000" + suffix), 0)
              == 1,
          "merged UUID " + suffix);
    var snapshots = new HashSet<UUID>();
    for (var entry : new WorldRepositories(layout).tracked().entrySet())
      try (var repo = new DimensionRepository(entry.getValue(), entry.getKey(), false)) {
        var commit = repo.refs().readCommit(repo.refs().head());
        snapshots.add(commit.metadata().snapshot());
        if (entry.getKey().equals(DimensionId.OVERWORLD))
          check(commit.parents().size() == 2, "merge parents");
      }
    check(snapshots.size() == 1, "shared snapshot UUID");
    System.out.println(
        "PASS both branches blocks/chest items/UUIDs/cross-chunk; two parents; shared snapshot");
  }

  static void inspect(WorldLayout layout) throws Exception {
    assertMerged(layout);
    for (var p : List.of(new ChunkPos(0, 0), new ChunkPos(1, 0), new ChunkPos(-2, -2))) {
      var s = Phase2AcceptanceTool.read(layout, p).sections().getOrDefault(14, Section.air());
      System.out.println(
          p
              + " cell0="
              + s.block(0).canonical()
              + " cell15="
              + s.block(15).canonical()
              + " BE="
              + s.blockEntities().size());
    }
  }
}
