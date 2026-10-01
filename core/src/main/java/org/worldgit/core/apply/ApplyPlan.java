package org.worldgit.core.apply;

import java.io.IOException;
import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;

/**
 * 「目前世界 → 目標 tree」需要改寫的內容，中性、可序列化、可分批、可只取某範圍。離線路徑直接寫檔；插件／模組在線上逐批套用。
 *
 * <p>只含差異：tree id 相同的子樹不會出現。section 以壓縮 blob 保存（解碼才展開），所以數千 chunk 的計畫也不會一次佔滿記憶體。
 */
public final class ApplyPlan {
  /** 要寫入的一個 section；mask 非 null 時只有 mask 內的格子被覆蓋，其餘保持現況（範圍裁切與 .wgignore 區域）。 */
  public record SectionOp(int y, byte[] blob, long[] mask) {
    public SectionOp {
      blob = blob == null ? null : blob.clone();
      mask = mask == null ? null : mask.clone();
      if (mask != null && mask.length != 64) throw new IllegalArgumentException("mask 必須是 64 個 long");
    }

    @Override
    public byte[] blob() {
      return blob == null ? null : blob.clone();
    }

    @Override
    public long[] mask() {
      return mask == null ? null : mask.clone();
    }

    /** 目標 section；null blob 表示整個 section 變成空氣。 */
    public Section section() throws IOException {
      return blob == null ? Section.air() : SnapshotCodec.section(blob);
    }

    public boolean covers(int index) {
      return mask == null || (mask[index >>> 6] & (1L << (index & 63))) != 0;
    }

    public int coveredCount() {
      if (mask == null) return 4096;
      int n = 0;
      for (long m : mask) n += Long.bitCount(m);
      return n;
    }

    /** 把目標內容覆蓋到 current 的 mask 範圍；回傳新 section（不可變）。 */
    public Section mergeInto(Section current) throws IOException {
      Section target = section();
      if (mask == null) return target;
      var blocks = new ArrayList<>(current.blocks());
      var bes = current.blockEntities();
      var targetBes = target.blockEntities();
      for (int i = 0; i < 4096; i++)
        if (covers(i)) {
          blocks.set(i, target.block(i));
          var be = targetBes.get(i);
          if (be == null) bes.remove(i);
          else bes.put(i, be);
        }
      return new Section(blocks, bes);
    }
  }

  /** 一個 chunk 的 biome：64 個 4x4x4 sample，空字串表示「保持現況」。 */
  public record BiomeOp(int y, List<String> samples) {
    public BiomeOp {
      samples = List.copyOf(samples);
      if (samples.size() != 64) throw new IllegalArgumentException("biome 必須有 64 個 sample");
    }
  }

  /**
   * 一個 chunk 的方塊級改寫。delete 表示整個 chunk 要刪除（目標沒有、且選擇刪除 untracked）。 ticks/structures 的 set 旗標表示要用 data
   * （正規化 NBT bytes，null＝清空）取代；只有整個 chunk 在範圍內才會設。
   */
  public record ChunkOp(
      ChunkPos pos,
      boolean delete,
      SortedMap<Integer, SectionOp> sections,
      SortedMap<Integer, BiomeOp> biomes,
      boolean setTicks,
      byte[] ticks,
      boolean setStructures,
      byte[] structures) {
    public ChunkOp {
      sections = Collections.unmodifiableSortedMap(new TreeMap<>(sections));
      biomes = Collections.unmodifiableSortedMap(new TreeMap<>(biomes));
      ticks = ticks == null ? null : ticks.clone();
      structures = structures == null ? null : structures.clone();
    }

    @Override
    public byte[] ticks() {
      return ticks == null ? null : ticks.clone();
    }

    @Override
    public byte[] structures() {
      return structures == null ? null : structures.clone();
    }

    public boolean empty() {
      return !delete && sections.isEmpty() && biomes.isEmpty() && !setTicks && !setStructures;
    }
  }

  /** 實體操作以 UUID 為鍵：target 為 null 表示移除；否則先移除全維度同 UUID 的舊實體，再依 Pos 放入目標 chunk。 */
  public record EntityOp(UUID uuid, ChunkPos hint, EntitySnapshot target, double[] sourcePosition) {
    public EntityOp(UUID uuid, ChunkPos hint, EntitySnapshot target) { this(uuid,hint,target,null); }
    public EntityOp {
      sourcePosition=sourcePosition==null ? null : sourcePosition.clone();
      if(sourcePosition!=null && (sourcePosition.length!=3 || Arrays.stream(sourcePosition).anyMatch(v -> !Double.isFinite(v))))
        throw new IllegalArgumentException("實體來源位置無效");
    }
    @Override public double[] sourcePosition() { return sourcePosition==null ? null : sourcePosition.clone(); }
    public ChunkPos targetChunk() {
      return target == null ? null : target.chunk();
    }
  }

  public record Stats(
      int chunks,
      int sections,
      int biomeSections,
      int entityPuts,
      int entityRemoves,
      int chunkDeletes,
      int untrackedKept,
      int metaFiles) {
    public boolean empty() {
      return chunks == 0
          && sections == 0
          && biomeSections == 0
          && entityPuts == 0
          && entityRemoves == 0
          && chunkDeletes == 0
          && metaFiles == 0;
    }
  }

  private final DimensionId dimension;
  private final String baseTree, targetTree;
  private final int dataVersion;
  private final Scope scope;
  private final SortedMap<ChunkPos, ChunkOp> chunks;
  private final List<EntityOp> entities;
  private final SortedMap<String, byte[]> worldMeta;
  private final List<ChunkPos> untracked;
  private String ignoreRules = "";

  public String ignoreRules() { return ignoreRules; }

  public ApplyPlan withRules(String rules) {
    var copy = new ApplyPlan(dimension, baseTree, targetTree, dataVersion, scope,
        chunks.values(), entities, worldMeta, untracked);
    copy.ignoreRules = Objects.requireNonNull(rules);
    return copy;
  }

  public ApplyPlan(
      DimensionId dimension,
      String baseTree,
      String targetTree,
      int dataVersion,
      Scope scope,
      Collection<ChunkOp> chunkOps,
      Collection<EntityOp> entityOps,
      Map<String, byte[]> worldMeta,
      Collection<ChunkPos> untracked) {
    this.dimension = Objects.requireNonNull(dimension);
    this.baseTree = baseTree;
    this.targetTree = targetTree;
    this.dataVersion = dataVersion;
    this.scope = Objects.requireNonNull(scope);
    var c = new TreeMap<ChunkPos, ChunkOp>();
    for (var op : chunkOps) c.put(op.pos(), op);
    this.chunks = Collections.unmodifiableSortedMap(c);
    this.entities = List.copyOf(entityOps);
    var meta = new TreeMap<String, byte[]>();
    worldMeta.forEach((k, v) -> meta.put(k, v == null ? null : v.clone()));
    this.worldMeta = Collections.unmodifiableSortedMap(meta);
    this.untracked = List.copyOf(new TreeSet<>(untracked));
  }

  public DimensionId dimension() {
    return dimension;
  }

  public String baseTree() {
    return baseTree;
  }

  public String targetTree() {
    return targetTree;
  }

  /** 目標 commit 的 DataVersion，寫新 chunk 時使用。 */
  public int dataVersion() {
    return dataVersion;
  }

  public Scope scope() {
    return scope;
  }

  public SortedMap<ChunkPos, ChunkOp> chunks() {
    return chunks;
  }

  public List<EntityOp> entities() {
    return entities;
  }

  /** 世界級 metadata 檔（目標內容；只有主世界 repo 的 switch/reset 會有）。 */
  public SortedMap<String, byte[]> worldMeta() {
    var copy = new TreeMap<String, byte[]>();
    worldMeta.forEach((k, v) -> copy.put(k, v == null ? null : v.clone()));
    return copy;
  }

  /** 目標沒有、世界卻有而被保留（標為 untracked）的 chunk。 */
  public List<ChunkPos> untrackedKept() {
    return untracked;
  }

  public boolean empty() {
    return stats().empty();
  }

  public Stats stats() {
    int sections = 0, biomes = 0, deletes = 0, puts = 0, removes = 0;
    for (var op : chunks.values()) {
      sections += op.sections().size();
      biomes += op.biomes().size();
      if (op.delete()) deletes++;
    }
    for (var e : entities) if (e.target() == null) removes++;
    else puts++;
    return new Stats(chunks.size(), sections, biomes, puts, removes, deletes, untracked.size(), worldMeta.size());
  }

  /** 只取某個範圍內的 chunk 操作（實體依目標位置／提示 chunk 篩選）。 */
  public ApplyPlan only(Scope narrower) {
    var ops = new ArrayList<ChunkOp>();
    for (var op : chunks.values()) {
      if (!narrower.touchesChunk(op.pos())) continue;
      if (op.delete()) {
        if (narrower.coversChunk(op.pos())) ops.add(op);
        continue;
      }
      var sections = new TreeMap<Integer, SectionOp>();
      for (var section : op.sections().values()) {
        if (!narrower.touchesSection(op.pos(), section.y())) continue;
        long[] mask = new long[64];
        for (int i = 0; i < 4096; i++)
          if (section.covers(i) && narrower.containsBlock(op.pos().x()*16+(i&15),
              section.y()*16+(i>>8), op.pos().z()*16+((i>>4)&15)))
            mask[i>>6] |= 1L << (i&63);
        if (Arrays.stream(mask).anyMatch(v -> v != 0))
          sections.put(section.y(), new SectionOp(section.y(), section.blob(), mask));
      }
      var biomes = new TreeMap<Integer, BiomeOp>();
      for (var biome : op.biomes().values()) {
        var samples = new ArrayList<>(biome.samples());
        for (int i = 0; i < 64; i++)
          if (!narrower.containsBlock(op.pos().x()*16+(i&3)*4,
              biome.y()*16+(i>>4)*4, op.pos().z()*16+((i>>2)&3)*4)) samples.set(i, "");
        if (samples.stream().anyMatch(v -> !v.isEmpty()))
          biomes.put(biome.y(), new BiomeOp(biome.y(), samples));
      }
      boolean full = narrower.coversChunk(op.pos());
      var clipped = new ChunkOp(op.pos(), false, sections, biomes, full && op.setTicks(),
          op.ticks(), full && op.setStructures(), op.structures());
      if (!clipped.empty()) ops.add(clipped);
    }
    var ents=new ArrayList<EntityOp>();
    for(var e:entities) {
      double[] target=e.target()==null ? null : e.target().position();
      double[] source=e.sourcePosition();
      boolean targetInside=target!=null && narrower.containsPoint(target[0],target[1],target[2]);
      boolean sourceInside=source!=null ? narrower.containsPoint(source[0],source[1],source[2])
          : e.hint()!=null && narrower.coversChunk(e.hint());
      if(targetInside) ents.add(e);
      else if(sourceInside) ents.add(new EntityOp(e.uuid(),e.hint(),null,source));
    }
    return new ApplyPlan(dimension, baseTree, targetTree, dataVersion, narrower, ops, ents,
        narrower.kind()==Scope.Kind.ALL ? worldMeta : Map.of(),
        untracked.stream().filter(narrower::touchesChunk).toList()).withRules(ignoreRules);
  }

  /** 嚴格 section 限額；同一 chunk 可分成多批，chunk 級欄位只在最後一批出現。 */
  public List<ApplyPlan> batches(int maxSections) {
    if (maxSections < 1) throw new IllegalArgumentException("maxSections");
    var result = new ArrayList<ApplyPlan>();
    for (var op : chunks.values()) {
      var sections = new ArrayList<>(op.sections().values());
      int count = Math.max(1, (sections.size() + maxSections - 1) / maxSections);
      for (int batch = 0; batch < count; batch++) {
        var part = new TreeMap<Integer, SectionOp>();
        for (int i = batch*maxSections; i < Math.min(sections.size(), (batch+1)*maxSections); i++)
          part.put(sections.get(i).y(), sections.get(i));
        boolean last = batch == count-1;
        result.add(sub(List.of(new ChunkOp(op.pos(), op.delete(), part,
            last ? op.biomes() : new TreeMap<>(), last && op.setTicks(), op.ticks(),
            last && op.setStructures(), op.structures())), List.of(), Map.of()));
      }
    }
    // barrier：所有移除（包含 put 的 UUID）完成後，才能在目標 chunk 生成。
    var removals = entities.stream().map(e -> new EntityOp(e.uuid(), e.hint(), null,e.sourcePosition())).toList();
    var puts = entities.stream().filter(e -> e.target() != null).toList();
    for (var phase : List.of(removals, puts))
      for (int i = 0; i < phase.size(); i += 64)
        result.add(sub(List.of(), phase.subList(i, Math.min(phase.size(), i+64)), Map.of()));
    if (!worldMeta.isEmpty()) result.add(sub(List.of(), List.of(), worldMeta));
    return result;
  }

  private ApplyPlan sub(List<ChunkOp> ops, List<EntityOp> ents, Map<String, byte[]> meta) {
    return new ApplyPlan(dimension, baseTree, targetTree, dataVersion, scope, ops, ents, meta, List.of()).withRules(ignoreRules);
  }

  // ───────────────────────── 序列化 ─────────────────────────

  public byte[] toBytes() {
    var root = new Nbt.Compound().with("version", 1).with("dimension", dimension.value());
    root.put("dataVersion", dataVersion);
    if (baseTree != null) root.put("baseTree", baseTree);
    if (targetTree != null) root.put("targetTree", targetTree);
    root.put("scope", scope.toNbt());
    root.put("ignoreRules", ignoreRules);
    var chunkList = new ArrayList<Object>();
    for (var op : chunks.values()) {
      var c = new Nbt.Compound().with("x", op.pos().x()).with("z", op.pos().z());
      c.put("delete", (byte) (op.delete() ? 1 : 0));
      var secs = new ArrayList<Object>();
      for (var s : op.sections().values()) {
        var n = new Nbt.Compound().with("y", s.y());
        if (s.blob != null) n.put("blob", s.blob.clone());
        if (s.mask != null) n.put("mask", s.mask.clone());
        secs.add(n);
      }
      c.put("sections", new Nbt.ListTag(10, secs));
      var bios = new ArrayList<Object>();
      for (var b : op.biomes().values())
        bios.add(
            new Nbt.Compound()
                .with("y", b.y())
                .with("samples", new Nbt.ListTag(8, new ArrayList<>(b.samples()))));
      c.put("biomes", new Nbt.ListTag(10, bios));
      if (op.setTicks()) {
        c.put("setTicks", (byte) 1);
        if (op.ticks != null) c.put("ticks", op.ticks.clone());
      }
      if (op.setStructures()) {
        c.put("setStructures", (byte) 1);
        if (op.structures != null) c.put("structures", op.structures.clone());
      }
      chunkList.add(c);
    }
    root.put("chunks", new Nbt.ListTag(10, chunkList));
    var ents = new ArrayList<Object>();
    for (var e : entities) {
      var n = new Nbt.Compound().with("uuid", e.uuid().toString());
      if (e.hint() != null) n.put("hint", new int[] {e.hint().x(), e.hint().z()});
      if (e.sourcePosition()!=null) n.put("sourcePosition",new Nbt.ListTag(6,new ArrayList<>(Arrays.stream(e.sourcePosition()).boxed().toList())));
      if (e.target() != null) n.put("nbt", e.target().bytes());
      ents.add(n);
    }
    root.put("entities", new Nbt.ListTag(10, ents));
    var meta = new Nbt.Compound();
    worldMeta.forEach((k,v) -> meta.put(k, v == null ? new byte[0] : v));
    root.put("worldMeta", meta);
    int[] un = new int[untracked.size() * 2];
    for (int i = 0; i < untracked.size(); i++) {
      un[i * 2] = untracked.get(i).x();
      un[i * 2 + 1] = untracked.get(i).z();
    }
    root.put("untracked", un);
    return Nbt.write(root);
  }

  public static ApplyPlan fromBytes(byte[] bytes) throws IOException {
    try {
      var root = Nbt.read(bytes);
      if (root.integer("version", 0) != 1) throw new IOException("ApplyPlan 版本不支援");
      var chunkOps = new ArrayList<ChunkOp>();
      for (Object o : root.list("chunks").values()) {
        var c = (Nbt.Compound) o;
        var secs = new TreeMap<Integer, SectionOp>();
        for (Object so : c.list("sections").values()) {
          var s = (Nbt.Compound) so;
          secs.put(
              s.integer("y", 0), new SectionOp(s.integer("y", 0), (byte[]) s.get("blob"), (long[]) s.get("mask")));
        }
        var bios = new TreeMap<Integer, BiomeOp>();
        for (Object bo : c.list("biomes").values()) {
          var b = (Nbt.Compound) bo;
          bios.put(
              b.integer("y", 0),
              new BiomeOp(
                  b.integer("y", 0),
                  b.list("samples").values().stream().map(String.class::cast).toList()));
        }
        chunkOps.add(
            new ChunkOp(
                new ChunkPos(c.integer("x", 0), c.integer("z", 0)),
                c.integer("delete", 0) != 0,
                secs,
                bios,
                c.integer("setTicks", 0) != 0,
                (byte[]) c.get("ticks"),
                c.integer("setStructures", 0) != 0,
                (byte[]) c.get("structures")));
      }
      var ents = new ArrayList<EntityOp>();
      for (Object o : root.list("entities").values()) {
        var e = (Nbt.Compound) o;
        UUID uuid = UUID.fromString(e.string("uuid"));
        ChunkPos hint = e.get("hint") instanceof int[] h ? new ChunkPos(h[0], h[1]) : null;
        EntitySnapshot target = e.get("nbt") instanceof byte[] b ? new EntitySnapshot(uuid, Nbt.read(b)) : null;
        double[] source=e.containsKey("sourcePosition") ? e.list("sourcePosition").values().stream().mapToDouble(v -> ((Number)v).doubleValue()).toArray() : null;
        ents.add(new EntityOp(uuid, hint, target,source));
      }
      var meta = new TreeMap<String, byte[]>();
      root.compound("worldMeta").forEach((k, v) -> meta.put(k, ((byte[]) v).length == 0 ? null : (byte[]) v));
      var untracked = new ArrayList<ChunkPos>();
      if (root.get("untracked") instanceof int[] u)
        for (int i = 0; i + 1 < u.length; i += 2) untracked.add(new ChunkPos(u[i], u[i + 1]));
      return new ApplyPlan(
          new DimensionId(root.string("dimension")),
          root.containsKey("baseTree") ? root.string("baseTree") : null,
          root.containsKey("targetTree") ? root.string("targetTree") : null,
          root.integer("dataVersion", 0),
          Scope.fromNbt(root.compound("scope")),
          chunkOps,
          ents,
          meta,
          untracked).withRules(root.string("ignoreRules"));
    } catch (RuntimeException e) {
      throw new IOException("ApplyPlan 內容無效：" + e.getMessage(), e);
    }
  }
}
