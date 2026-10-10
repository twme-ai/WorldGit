package org.worldgit.core.apply;

import java.io.IOException;
import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.core.normalize.SnapshotCodec;
import org.worldgit.core.store.ObjectStore;
import org.worldgit.core.store.TreeEditor;

/**
 * 套用後驗證失敗：受影響範圍與目標仍有差異。內容已寫入（狀態為 PARTIAL），須 {@code switch <目標> --force} 重套或 {@code reset --hard} 回原狀。
 *
 * <p>帶結構化資料（chunk 座標與差異種類），讓 Paper／Fabric 以各自語系呈現；{@link #getMessage()} 是繁體中文預設。
 */
public final class ApplyVerificationException extends IOException {
  public static final int LIMIT = 8;

  /** 一個 chunk 殘留的差異：section、biome sample 數，方塊／流體排程 tick 差異筆數，結構參照，整個 chunk 刪除。 */
  public record ChunkMismatch(
      ChunkPos chunk,
      int sections,
      int biomes,
      int blockTicks,
      int fluidTicks,
      boolean structures,
      boolean delete,
      List<String> samples) {
    public ChunkMismatch {
      samples = List.copyOf(samples);
    }
  }

  private final String dimension;
  private final List<ChunkMismatch> chunks;
  private final int total;
  private final int entityPuts;
  private final int entityRemoves;
  private final int metaFiles;

  public ApplyVerificationException(
      String dimension,
      List<ChunkMismatch> chunks,
      int total,
      int entityPuts,
      int entityRemoves,
      int metaFiles) {
    super(message(dimension, chunks, total, entityPuts, entityRemoves, metaFiles));
    this.dimension = dimension;
    this.chunks = List.copyOf(chunks);
    this.total = total;
    this.entityPuts = entityPuts;
    this.entityRemoves = entityRemoves;
    this.metaFiles = metaFiles;
  }

  public String dimension() {
    return dimension;
  }

  /** 列出的 chunk（最多 {@link #LIMIT}）。 */
  public List<ChunkMismatch> chunks() {
    return chunks;
  }

  /** 有殘留差異的 chunk 總數。 */
  public int total() {
    return total;
  }

  public int entityPuts() {
    return entityPuts;
  }

  public int entityRemoves() {
    return entityRemoves;
  }

  public int metaFiles() {
    return metaFiles;
  }

  /** 由驗證計畫（目前世界 → 目標，應為空）建立。差異的細節從 base／target tree 讀取。 */
  public static ApplyVerificationException of(ObjectStore store, String dimension, ApplyPlan check)
      throws IOException {
    var all = new ArrayList<ChunkMismatch>();
    for (var op : check.chunks().values()) {
      int blockTicks = 0, fluidTicks = 0;
      var samples = new ArrayList<String>();
      for (var section : op.sections().values()) sectionSamples(store, check.baseTree(), op.pos(), section, samples);
      if (op.setStructures()) structureSamples(store, check.baseTree(), check.targetTree(), op.pos(), samples);
      if (op.setTicks()) {
        var before = ticks(store, check.baseTree(), op.pos());
        var after = ticks(store, check.targetTree(), op.pos());
        blockTicks = diffTicks(before, after, "block_ticks", samples);
        fluidTicks = diffTicks(before, after, "fluid_ticks", samples);
      }
      all.add(
          new ChunkMismatch(
              op.pos(),
              op.sections().size(),
              op.biomes().size(),
              blockTicks,
              fluidTicks,
              op.setStructures(),
              op.delete(),
              samples));
    }
    var stats = check.stats();
    return new ApplyVerificationException(
        dimension,
        all.size() > LIMIT ? all.subList(0, LIMIT) : all,
        all.size(),
        stats.entityPuts(),
        stats.entityRemoves(),
        stats.metaFiles());
  }

  /** 最多兩格：座標與「目前→目標」的方塊，方便判斷是哪一種方塊無法被還原或被遊戲改回。 */
  private static void sectionSamples(
      ObjectStore store, String observed, ChunkPos pos, ApplyPlan.SectionOp op, List<String> samples)
      throws IOException {
    if (samples.size() >= 4) return;
    var entry = observed == null ? null : TreeEditor.find(store, observed, pos.treePath() + "/s." + op.y() + ".bin");
    var before = entry == null ? org.worldgit.core.model.Section.air() : SnapshotCodec.section(store.readBlob(entry.id()));
    var after = op.section();
    int shown = 0;
    for (int i = 0; i < 4096 && shown < 2; i++) {
      if (!op.covers(i)) continue;
      var a = before.block(i);
      var b = after.block(i);
      boolean sameBe = Arrays.equals(before.blockEntities().get(i), after.blockEntities().get(i));
      if (a.equals(b) && sameBe) continue;
      shown++;
      samples.add(
          "(" + (pos.x() * 16 + (i & 15)) + "," + (op.y() * 16 + (i >> 8)) + "," + (pos.z() * 16 + ((i >> 4) & 15)) + ") "
              + a.canonical() + "→" + b.canonical() + (a.equals(b) ? "（BE）" : ""));
    }
  }

  private static void structureSamples(
      ObjectStore store, String observed, String target, ChunkPos pos, List<String> samples) throws IOException {
    var before = structures(store, observed, pos);
    var after = structures(store, target, pos);
    var keys = new TreeSet<String>();
    for (String part : List.of("References", "starts")) {
      var names = new TreeSet<String>(before.compound(part).keySet());
      names.addAll(after.compound(part).keySet());
      for (String name : names) {
        Object a = before.compound(part).get(name), b = after.compound(part).get(name);
        boolean same =
            a instanceof long[] x && b instanceof long[] y
                ? Arrays.equals(x, y)
                : a instanceof Nbt.Compound x && b instanceof Nbt.Compound y ? Nbt.equal(x, y) : Objects.equals(a, b);
        if (same) continue;
        String size = a instanceof long[] x && b instanceof long[] y ? " " + x.length + "→" + y.length : a == null ? " +" : b == null ? " -" : "";
        keys.add(part + ":" + name + size);
      }
    }
    int n = 0;
    for (String k : keys) if (n++ < 2 && samples.size() < 6) samples.add(k);
  }

  private static Nbt.Compound structures(ObjectStore store, String tree, ChunkPos pos) throws IOException {
    if (tree == null) return new Nbt.Compound();
    var entry = TreeEditor.find(store, tree, pos.treePath() + "/structures.bin");
    return entry == null ? new Nbt.Compound() : Nbt.read(SnapshotCodec.nbt(5, store.readBlob(entry.id()), true));
  }

  private static Nbt.Compound ticks(ObjectStore store, String tree, ChunkPos pos) throws IOException {
    if (tree == null) return new Nbt.Compound();
    var entry = TreeEditor.find(store, tree, pos.treePath() + "/ticks.bin");
    if (entry == null) return new Nbt.Compound();
    return Nbt.read(SnapshotCodec.nbt(4, store.readBlob(entry.id()), true));
  }

  private static String key(Nbt.Compound t) {
    return t.string("i") + "@" + t.integer("x", 0) + "," + t.integer("y", 0) + "," + t.integer("z", 0);
  }

  private static int diffTicks(Nbt.Compound before, Nbt.Compound after, String list, List<String> samples) {
    var a = new TreeMap<String, Nbt.Compound>();
    var b = new TreeMap<String, Nbt.Compound>();
    for (Object v : before.list(list).values()) a.put(key((Nbt.Compound) v), (Nbt.Compound) v);
    for (Object v : after.list(list).values()) b.put(key((Nbt.Compound) v), (Nbt.Compound) v);
    var keys = new TreeSet<String>(a.keySet());
    keys.addAll(b.keySet());
    int n = 0;
    for (String k : keys) {
      var x = a.get(k);
      var y = b.get(k);
      if (x != null && y != null && x.integer("t", 0) == y.integer("t", 0) && x.integer("p", 0) == y.integer("p", 0))
        continue;
      n++;
      if (samples.size() < 6)
        samples.add(
            k + " t " + (x == null ? "-" : x.integer("t", 0)) + "→" + (y == null ? "-" : y.integer("t", 0)));
    }
    return n;
  }

  public static String describe(ChunkMismatch c) {
    var parts = new ArrayList<String>();
    if (c.delete()) parts.add("整個 chunk 刪除");
    if (c.sections() > 0) parts.add("方塊 section " + c.sections());
    if (c.biomes() > 0) parts.add("biome " + c.biomes());
    if (c.blockTicks() > 0) parts.add("排程 tick（方塊 " + c.blockTicks() + " 筆）");
    if (c.fluidTicks() > 0) parts.add("排程 tick（流體 " + c.fluidTicks() + " 筆）");
    if (c.structures()) parts.add("結構參照");
    if (parts.isEmpty()) parts.add("排程 tick／結構資料");
    return "[" + c.chunk().x() + "," + c.chunk().z() + "] " + String.join("，", parts);
  }

  private static String message(
      String dimension, List<ChunkMismatch> chunks, int total, int puts, int removes, int meta) {
    var text = new StringBuilder("受影響 chunk 套用驗證失敗：").append(dimension);
    text.append("，").append(total).append(" 個 chunk 與目標仍有差異");
    if (!chunks.isEmpty()) text.append("：");
    for (int i = 0; i < chunks.size(); i++) {
      text.append(i == 0 ? "" : "；").append(describe(chunks.get(i)));
      if (!chunks.get(i).samples().isEmpty())
        text.append(" ⟨").append(String.join("、", chunks.get(i).samples())).append("⟩");
    }
    if (total > chunks.size()) text.append("；另 ").append(total - chunks.size()).append(" 個 chunk 未列出");
    if (puts + removes > 0) text.append("；實體 +").append(puts).append(" -").append(removes);
    if (meta > 0) text.append("；world metadata ").append(meta).append(" 個檔案");
    text.append("。世界已部分套用（PARTIAL）；下一步：switch <目標> --force 重新套用，或 reset --hard 回到原狀。");
    return text.toString();
  }
}
