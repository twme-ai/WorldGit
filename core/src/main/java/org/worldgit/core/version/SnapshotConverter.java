package org.worldgit.core.version;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.apply.ChunkNbt;
import org.worldgit.core.config.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;
import org.worldgit.core.store.*;

/** 重建 chunk／entity dynamic → 原版 DFU 或反向 codec → 既有正規化；不動 refs。 */
public final class SnapshotConverter implements AutoCloseable {
  public record Result(String tree, ConversionReport report) {}

  private final int from, to;
  private final ReverseRules rules;
  private final ConversionReport.Collector losses = new ConversionReport.Collector();
  private NbtUpgrader dfu;
  private final boolean ownsDfu;
  private final boolean runtime;
  private IgnoreRules normalizationRules = IgnoreRules.none();

  public SnapshotConverter(int from, int to, Path config, NbtUpgrader dfu) throws IOException {
    this(from, to, config, dfu, false);
  }

  SnapshotConverter(int from, int to, Path config, NbtUpgrader dfu, boolean runtime) throws IOException {
    this.runtime = runtime;
    MinecraftVersion.byDataVersion(from);
    MinecraftVersion.byDataVersion(to);
    this.from = from;
    this.to = to;
    this.rules = new ReverseRules(from, to, config);
    this.dfu = dfu;
    ownsDfu = dfu == null;
  }

  private NbtUpgrader dfu() throws IOException {
    if (dfu == null) dfu = new DfuProcess(MinecraftVersion.byDataVersion(to));
    return dfu;
  }

  public Result convert(ObjectStore store, String tree, DimensionId dimension) throws IOException {
    if (tree == null || from == to)
      return new Result(tree, new ConversionReport(from, to, rules.fingerprint, List.of()));
    var level = TreeEditor.find(store, tree, "world-meta/level.nbt");
    if (dimension.equals(DimensionId.OVERWORLD) && level == null)
      throw new IOException("跨版本主世界缺少 level.nbt，無法確認 registry／世界生成設定；請先以來源版本重新 capture");
    if (level != null && Nbt.read(store.readBlob(level.id())).integer("DataVersion", from) != from)
      throw new IOException("commit mcDataVersion 與 level.nbt 不符；拒絕以改版本數字冒充轉換");
    var editor = new TreeEditor(store, tree);
    normalizationRules = IgnoreRules.parse(org.worldgit.core.merge.TreeFilter.rules(store, tree));
    var reverse = new ReverseNbt(rules, losses, from, to);
    for (var region : store.readTree(tree).values())
      if (region.name().matches("r\\.-?\\d+\\.-?\\d+") && region.kind() == ObjectStore.Kind.TREE)
        for (var chunk : store.readTree(region.id()).values()) {
          String[] coords = chunk.name().split("\\.");
          if (coords.length != 3 || !coords[0].equals("c"))
            throw new IOException("chunk tree 名稱無效");
          var pos = new ChunkPos(Integer.parseInt(coords[1]), Integer.parseInt(coords[2]));
          org.worldgit.core.operation.OperationProgress.check();
          var snapshot = readChunk(store, chunk.id(), pos, from);
          var converted = convert(snapshot, reverse);
          editor.replaceTree(
              region.name() + "/" + chunk.name(), SnapshotCodec.chunkFiles(converted));
        }
    String name = dimension.equals(DimensionId.OVERWORLD) ? "world-meta" : "dimension-meta";
    var entry = TreeEditor.find(store, tree, name);
    if (entry != null) {
      var files = new TreeMap<String, byte[]>();
      for (var e : store.readTree(entry.id()).values()) files.put(e.name(), store.readBlob(e.id()));
      var metadata=MetadataConversion.convert(
          files, dimension, from, to, from < to ? dfu() : null, reverse, losses, runtime);
      if (!ownsDfu) metadata=dfu.projectMetadata(metadata,from,to);
      metadata=MetadataNormalizer.nativeLayout(metadata);
      editor.replaceTree(name,metadata);
    }
    String result = editor.write();
    store.flush();
    return new Result(result, losses.report(from, to, rules.fingerprint));
  }

  public ChunkSnapshot convert(ChunkSnapshot snapshot) throws IOException {
    return convert(snapshot, new ReverseNbt(rules, losses, from, to));
  }

  private ChunkSnapshot convert(ChunkSnapshot s, ReverseNbt reverse) throws IOException {
    if (from < to) {
      var raw = materialize(s);
      raw = dfu().upgrade("CHUNK", raw, from, to);
      raw.put("DataVersion", to);
      var entities =
          new Nbt.Compound()
              .with("DataVersion", from)
              .with("Position", new int[] {s.pos().x(), s.pos().z()})
              .with(
                  "Entities",
                  new Nbt.ListTag(10, s.entities().stream().map(e -> (Object) e.data()).toList()));
      entities = dfu().upgrade("ENTITY_CHUNK", entities, from, to);
      return new ChunkNormalizer(normalizationRules, EntitySemantics.OFFLINE)
          .normalize(
              s.pos(),
              raw,
              entities.list("Entities").values().stream().map(Nbt.Compound.class::cast).toList());
    }
    var sections = new TreeMap<Integer, Section>();
    for (var e : s.sections().entrySet()) {
      var blocks = new ArrayList<BlockState>(4096);
      var bes = new TreeMap<Integer, byte[]>();
      for (int i = 0; i < 4096; i++)
        blocks.add(
            rules.block(e.getValue().block(i), s.pos() + "/s." + e.getKey() + "/" + i, losses));
      for (var be : e.getValue().blockEntities().entrySet()) {
        int i = be.getKey();
        var n =
            reverse.convert(
                Nbt.read(be.getValue()), "block-entity", s.pos() + "/s." + e.getKey() + "/" + i);
        if (n == null) continue;
        if (!blocks.get(i).name().equals(rules.rename("block", e.getValue().block(i).name()))
            && !rules.canHost(blocks.get(i).name(), n.string("id"))) {
          losses.add(
              "block-entity",
              Nbt.read(be.getValue()).string("id"),
              "skip",
              s.pos() + "/s." + e.getKey() + "/" + i);
          continue;
        }
        if (n != null) bes.put(i, Nbt.write(n));
      }
      var section = new Section(blocks, bes);
      if (!section.empty()) sections.put(e.getKey(), section);
    }
    var biomes = new TreeMap<Integer, List<String>>();
    for (var e : s.biomes().entrySet()) {
      var b = new ArrayList<String>();
      for (int i = 0; i < 64; i++) {
        String id = e.getValue().get(i);
        b.add(
            id.isEmpty()
                ? id
                : rules.id("biome", id, s.pos() + "/biomes/" + e.getKey() + "/" + i, losses));
      }
      biomes.put(e.getKey(), b);
    }
    var entities = new ArrayList<EntitySnapshot>();
    for (var e : s.entities()) {
      var n = reverse.convert(e.data(), "entity", s.pos() + "/entity/" + e.uuid());
      if (n != null) entities.add(new EntitySnapshot(e.uuid(), n));
    }
    // 決策 #169：整世界遷移的 runtime 路徑也必須夾值，不能只依賴歷史重新正規化。
    byte[] ticks = TickSemantics.clamp(s.ticks());
    if (ticks != null) {
      var n = Nbt.read(ticks);
      for (String key : List.of("block_ticks", "fluid_ticks")) {
        var list = new ArrayList<Object>();
        for (Object v : n.list(key).values()) {
          var c = (Nbt.Compound) v;
          String id =
              key.equals("block_ticks")
                  ? rules.id("block", c.string("i"), s.pos() + "/ticks", losses)
                  : c.string("i");
          if (!id.equals("skip")) {
            c.put("i", id);
            list.add(c);
          }
        }
        if (list.isEmpty()) n.remove(key);
        else {
          list.sort(Comparator.comparingInt((Object value) -> ((Nbt.Compound) value).integer("y", 0))
              .thenComparingInt(value -> ((Nbt.Compound) value).integer("z", 0))
              .thenComparingInt(value -> ((Nbt.Compound) value).integer("x", 0))
              .thenComparing(value -> ((Nbt.Compound) value).string("i"))
              .thenComparingInt(value -> ((Nbt.Compound) value).integer("p", 0))
              .thenComparingInt(value -> ((Nbt.Compound) value).integer("t", 0)));
          n.put(key, new Nbt.ListTag(10, list));
        }
      }
      ticks = n.isEmpty() ? null : Nbt.write(n);
    }
    byte[] structures = s.structures();
    if (structures != null) {
      var n = Nbt.read(structures);
      for (String kind : List.of("starts", "References")) {
        var entries = n.compound(kind);
        for (String id : new ArrayList<>(entries.keySet())) {
          if (rules.known("structures", id)) continue;
          losses.add(kind.equals("starts") ? "structure" : "structure-reference", id,
              "skip", s.pos() + "/structures/" + kind);
          entries.remove(id);
        }
      }
      structures = Nbt.write(n);
    }
    var result = new ChunkSnapshot(s.pos(), to, sections, biomes, entities, ticks, structures);
    if (runtime) return result;
    return new ChunkNormalizer(normalizationRules, EntitySemantics.OFFLINE)
        .normalize(s.pos(), materialize(result), entities.stream().map(EntitySnapshot::data).toList());
  }

  public static ChunkSnapshot readChunk(ObjectStore store, String tree, ChunkPos pos, int version)
      throws IOException {
    var sections = new TreeMap<Integer, Section>();
    var biomes = new TreeMap<Integer, List<String>>();
    var entities = new ArrayList<EntitySnapshot>();
    byte[] ticks = null, structures = null;
    for (var f : store.readTree(tree).values()) {
      byte[] b = store.readBlob(f.id());
      String name = f.name();
      if (name.matches("s\\.-?\\d+\\.bin"))
        sections.put(
            Integer.parseInt(name.substring(2, name.length() - 4)), SnapshotCodec.section(b));
      else
        switch (name) {
          case "biomes.bin" -> biomes.putAll(SnapshotCodec.biomes(b));
          case "entities.bin" -> entities.addAll(SnapshotCodec.entities(b));
          case "ticks.bin" -> ticks = SnapshotCodec.nbt(4, b, true);
          case "structures.bin" -> structures = SnapshotCodec.nbt(5, b, true);
          default -> throw new IOException("不支援的 chunk blob " + name);
        }
    }
    return new ChunkSnapshot(pos, version, sections, biomes, entities, ticks, structures);
  }

  public static Nbt.Compound materialize(ChunkSnapshot s) throws IOException {
    var raw =
        new Nbt.Compound()
            .with("DataVersion", s.dataVersion())
            .with("xPos", s.pos().x())
            .with("zPos", s.pos().z())
            .with("yPos", -4)
            .with("Status", "minecraft:full")
            .with("LastUpdate", 0L)
            .with("InhabitedTime", 0L);
    var ys = new TreeSet<>(s.sections().keySet());
    ys.addAll(s.biomes().keySet());
    var list = new ArrayList<Object>();
    var bes = new ArrayList<Object>();
    for (int y : ys) {
      var section = s.sections().getOrDefault(y, Section.air());
      list.add(
          new Nbt.Compound()
              .with("Y", (byte) y)
              .with("block_states", ChunkNbt.blockStates(section, s.dataVersion()))
              .with(
                  "biomes",
                  ChunkNbt.biomes(
                      s.biomes().getOrDefault(y, Collections.nCopies(64, "minecraft:plains")))));
      for (var e : section.blockEntities().entrySet()) {
        int i = e.getKey();
        var be =
            Nbt.read(e.getValue())
                .with("x", s.pos().x() * 16 + (i & 15))
                .with("y", y * 16 + (i >> 8))
                .with("z", s.pos().z() * 16 + ((i >> 4) & 15));
        bes.add(be);
      }
    }
    raw.put("sections", new Nbt.ListTag(10, list));
    raw.put("block_entities", new Nbt.ListTag(10, bes));
    var ticks = s.ticks() == null ? new Nbt.Compound() : Nbt.read(TickSemantics.clamp(s.ticks()));
    for (String key : List.of("block_ticks", "fluid_ticks")) raw.put(key, ticks.list(key));
    raw.put(
        "structures",
        s.structures() == null
            ? new Nbt.Compound()
                .with("References", new Nbt.Compound())
                .with("starts", new Nbt.Compound())
            : Nbt.read(s.structures()));
    return raw;
  }

  @Override
  public void close() throws IOException {
    if (ownsDfu && dfu != null) dfu.close();
  }
}
