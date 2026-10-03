package org.worldgit.core;

import java.nio.file.*;
import java.util.*;
import org.worldgit.core.anvil.*;
import org.worldgit.core.apply.*;
import org.worldgit.core.model.*;
import org.worldgit.core.remote.*;
import org.worldgit.core.service.*;

public class Phase4AcceptanceTool {
  public static void main(String[] args) throws Exception {
    String command = args[0];
    Path supplied = Path.of(args[1]);
    if (command.equals("bare-merge")) {
      var paths = new TreeMap<DimensionId, Path>();
      for (String id :
          List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")) {
        var d = new DimensionId(id);
        paths.put(d, supplied.resolve(d.directoryName() + ".git"));
      }
      try (var bare = new BareWorldMerge(supplied, paths)) {
        var preview = bare.preview(args[2], args[3], 1);
        var choices = new HashMap<Integer, org.worldgit.core.merge.MergeReport.Choice>();
        if (args.length > 4)
          preview
              .dimensions()
              .values()
              .forEach(
                  c ->
                      c.report()
                          .regions()
                          .forEach(
                              r ->
                                  choices.put(
                                      r.id(),
                                      org.worldgit.core.merge.MergeReport.Choice.valueOf(
                                          args[4].toUpperCase(Locale.ROOT)))));
        var result =
            bare.merge(
                preview,
                choices,
                1,
                new CommitMetadata.Identity("Hub", "hub@local"),
                "Hub PR merge",
                false);
        System.out.println(
            "state="
                + result.state()
                + " snapshot="
                + result.snapshot()
                + " conflicts="
                + preview.dimensions().values().stream()
                    .mapToInt(c -> c.report().regions().size())
                    .sum());
        if (!result.state().equals("COMPLETE")) throw new IllegalStateException(result.toString());
      }
      return;
    }
    var layout = WorldLayout.discover(supplied);
    if (command.equals("trees")) {
      var trees = new TreeMap<String, String>();
      try (var group =
          new RepositoryGroup(layout.repositoryRoot(), new WorldRepositories(layout).tracked())) {
        for (var e : group.repos().entrySet())
          trees.put(
              e.getKey().value(),
              e.getValue().refs().readCommit(e.getValue().refs().head()).tree());
      }
      System.out.println(
          "{"
              + trees.entrySet().stream()
                  .map(e -> "\"" + e.getKey() + "\":\"" + e.getValue() + "\"")
                  .collect(java.util.stream.Collectors.joining(","))
              + "}");
    } else if (command.equals("edit")) {
      int x = Integer.parseInt(args[2]);
      String name = args[3];
      var blocks = new ArrayList<>(Collections.nCopies(4096, BlockState.AIR));
      blocks.set(x & 15, new BlockState("minecraft:" + name, new TreeMap<>()));
      long[] mask = new long[64];
      mask[0] = 1L << (x & 15);
      var section =
          new ApplyPlan.SectionOp(
              14,
              org.worldgit.core.normalize.SnapshotCodec.section(new Section(blocks, Map.of())),
              mask);
      var op =
          new ApplyPlan.ChunkOp(
              new ChunkPos(0, 0),
              false,
              new TreeMap<>(Map.of(14, section)),
              new TreeMap<>(),
              false,
              null,
              false,
              null);
      new OfflineApplier(layout)
          .apply(
              new ApplyPlan(
                  DimensionId.OVERWORLD,
                  null,
                  null,
                  layout.dataVersion(),
                  Scope.all(),
                  List.of(op),
                  List.of(),
                  Map.of(),
                  List.of()));
    } else if (command.equals("seed"))
      System.out.println(
          layout.worldMetadata().entrySet().stream()
              .filter(e -> e.getKey().contains("world_gen") || e.getKey().equals("level.nbt"))
              .map(
                  e -> {
                    try {
                      return e.getKey() + "=" + Nbt.read(e.getValue());
                    } catch (Exception ex) {
                      throw new RuntimeException(ex);
                    }
                  })
              .toList());
    else throw new IllegalArgumentException(command);
  }
}
