package org.worldgit.core.merge;

import static org.worldgit.core.merge.MergeReport.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.worldgit.core.anvil.*;
import org.worldgit.core.apply.BlockBox;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.SnapshotCodec;
import org.worldgit.core.store.RefStore;

/** 一個世界組一份 durable MERGING；bounded binary NBT/zstd，原始與候選 tree 另由 repo refs pin。 */
public record MergeState(
    UUID operation,
    String mode,
    String source,
    String message,
    SortedMap<DimensionId, Dimension> dimensions) {
  public record Dimension(
      RefStore.Head original,
      String originalTree,
      String baseCommit,
      String sourceCommit,
      String otherParent,
      String baseTree,
      String oursTree,
      String theirsTree,
      String resultCommit,
      MergeReport report) {
    public Dimension withResult(String commit, MergeReport report) {
      return new Dimension(
          original,
          originalTree,
          baseCommit,
          sourceCommit,
          otherParent,
          baseTree,
          oursTree,
          theirsTree,
          commit,
          report);
    }
  }

  public MergeState {
    dimensions = Collections.unmodifiableSortedMap(new TreeMap<>(dimensions));
  }

  public int remaining() {
    return (int)
        dimensions.values().stream()
            .flatMap(d -> d.report.regions().stream())
            .filter(r -> !r.resolved())
            .count();
  }

  public List<Region> regions() {
    return dimensions.values().stream().flatMap(d -> d.report.regions().stream()).toList();
  }

  public void write(Path path) throws IOException {
    var root =
        new Nbt.Compound()
            .with("version", 1)
            .with("operation", operation.toString())
            .with("mode", mode)
            .with("source", source)
            .with("message", message);
    var ds = new Nbt.Compound();
    for (var entry : dimensions.entrySet()) {
      var d = entry.getValue();
      var row =
          new Nbt.Compound()
              .with("head", d.original.commit())
              .with("branch", text(d.original.branch()))
              .with("original", d.originalTree)
              .with("base-commit", text(d.baseCommit))
              .with("source-commit", d.sourceCommit)
              .with("other-parent", text(d.otherParent))
              .with("base", d.baseTree)
              .with("ours", d.oursTree)
              .with("theirs", d.theirsTree)
              .with("result", d.resultCommit)
              .with("report", report(d.report));
      ds.put(entry.getKey().value(), row);
    }
    root.put("dimensions", ds);
    byte[] raw = Nbt.write(root);
    if (raw.length > Nbt.MAX_BYTES) throw new IOException("合併狀態超過 32 MiB；請縮小合併範圍／衝突數");
    RegionFile.atomicWrite(path, SnapshotCodec.nbt(6, raw));
  }

  public static MergeState read(Path path) throws IOException {
    if (!Files.exists(path)) return null;
    if (Files.size(path) > Nbt.MAX_BYTES) throw new IOException("合併狀態過大");
    try {
      var root = Nbt.read(SnapshotCodec.nbt(6, Files.readAllBytes(path), true));
      if (root.integer("version", 0) != 1) throw new IOException("不支援 MERGING 版本");
      var ds = new TreeMap<DimensionId, Dimension>();
      for (var entry : root.compound("dimensions").entrySet()) {
        var id = new DimensionId(entry.getKey());
        var d = (Nbt.Compound) entry.getValue();
        ds.put(
            id,
            new Dimension(
                new RefStore.Head(d.string("head"), nullable(d.string("branch"))),
                d.string("original"),
                nullable(d.string("base-commit")),
                d.string("source-commit"),
                nullable(d.string("other-parent")),
                d.string("base"),
                d.string("ours"),
                d.string("theirs"),
                d.string("result"),
                report(id, d.compound("report"))));
      }
      return new MergeState(
          UUID.fromString(root.string("operation")),
          root.string("mode"),
          root.string("source"),
          root.string("message"),
          ds);
    } catch (RuntimeException ex) {
      throw new IOException("MERGING 狀態損毀", ex);
    }
  }

  private static Nbt.Compound report(MergeReport r) {
    var out =
        new Nbt.Compound()
            .with("automatic", r.automaticallyMergedSections())
            .with("warnings", strings(r.warnings()));
    var regions = new ArrayList<Object>();
    for (var region : r.regions()) {
      var row =
          new Nbt.Compound()
              .with("id", region.id())
              .with("count", region.blockCount())
              .with("ours-authors", strings(region.oursAuthors()))
              .with("theirs-authors", strings(region.theirsAuthors()))
              .with("redstone", (byte) (region.redstone() ? 1 : 0))
              .with("choice", region.choice().name())
              .with("resolved", (byte) (region.resolved() ? 1 : 0));
      var b = region.bounds();
      if (b != null)
        row.put("bounds", new int[] {b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()});
      var atoms = new ArrayList<Object>();
      for (var a : region.atoms()) {
        var atom =
            new Nbt.Compound()
                .with("kind", a.kind().name())
                .with("path", a.path())
                .with("key", strings(a.key()));
        if (a.position() != null) atom.put("pos", coords(a.position()));
        if (a.uuid() != null) atom.put("uuid", a.uuid().toString());
        atoms.add(atom);
      }
      row.put("atoms", new Nbt.ListTag(10, atoms));
      regions.add(row);
    }
    out.put("regions", new Nbt.ListTag(10, regions));
    out.put(
        "shapes",
        new Nbt.ListTag(11, r.updateShapes().stream().map(p -> (Object) coords(p)).toList()));
    var rules = new ArrayList<Object>();
    for (var d : r.ruleDifferences())
      rules.add(
          new Nbt.Compound()
              .with("base", d.base())
              .with("ours", d.ours())
              .with("theirs", d.theirs())
              .with("merged", d.merged()));
    out.put("rules", new Nbt.ListTag(10, rules));
    return out;
  }

  private static MergeReport report(DimensionId id, Nbt.Compound r) {
    var regions = new ArrayList<Region>();
    for (Object value : r.list("regions").values()) {
      var row = (Nbt.Compound) value;
      BlockBox b = null;
      if (row.containsKey("bounds")) {
        int[] v = (int[]) row.get("bounds");
        b = new BlockBox(v[0], v[1], v[2], v[3], v[4], v[5]);
      }
      var atoms = new ArrayList<Atom>();
      for (Object a : row.list("atoms").values()) {
        var atom = (Nbt.Compound) a;
        atoms.add(
            new Atom(
                Kind.valueOf(atom.string("kind")),
                atom.containsKey("pos") ? cell((int[]) atom.get("pos")) : null,
                atom.string("path"),
                strings(atom.list("key")),
                atom.containsKey("uuid") ? UUID.fromString(atom.string("uuid")) : null));
      }
      regions.add(
          new Region(
              row.integer("id", 0),
              id,
              b,
              row.integer("count", 0),
              strings(row.list("ours-authors")),
              strings(row.list("theirs-authors")),
              row.integer("redstone", 0) == 1,
              Choice.valueOf(row.string("choice")),
              row.integer("resolved", 0) == 1,
              atoms));
    }
    var rules = new ArrayList<RuleDifference>();
    for (Object value : r.list("rules").values()) {
      var d = (Nbt.Compound) value;
      rules.add(
          new RuleDifference(
              id, d.string("base"), d.string("ours"), d.string("theirs"), d.string("merged")));
    }
    return new MergeReport(
        r.integer("automatic", 0),
        regions,
        rules,
        r.list("shapes").values().stream().map(v -> cell((int[]) v)).toList(),
        strings(r.list("warnings")));
  }

  private static String text(String s) {
    return s == null ? "" : s;
  }

  private static String nullable(String s) {
    return s.isEmpty() ? null : s;
  }

  private static int[] coords(Cell c) {
    return new int[] {c.x(), c.y(), c.z()};
  }

  private static Cell cell(int[] c) {
    return new Cell(c[0], c[1], c[2]);
  }

  private static Nbt.ListTag strings(List<String> values) {
    return new Nbt.ListTag(8, values.stream().map(v -> (Object) v).toList());
  }

  private static List<String> strings(Nbt.ListTag values) {
    return values.values().stream().map(String.class::cast).toList();
  }
}
