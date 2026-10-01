package org.worldgit.core.apply;

import java.io.IOException;
import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.config.IgnoreRules;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.ChunkNormalizer;

/** 方塊級 patch；範圍外的原始 BE NBT、tick 與自訂欄位保持現狀。 */
public final class ChunkNbt {
  private ChunkNbt() {}

  public static Nbt.Compound apply(Nbt.Compound current, ApplyPlan.ChunkOp op,
      int version, IgnoreRules rules) throws IOException {
    var out = current == null ? new Nbt.Compound() : Nbt.copy(current);
    var sections = new TreeMap<Integer, Nbt.Compound>();
    for (var value : out.list("sections").values()) {
      var section = (Nbt.Compound)value;
      sections.put(section.integer("Y", 0), section);
    }
    var bes = new ArrayList<Object>(out.list("block_entities").values());
    for (var patch : op.sections().values()) {
      var raw = sections.computeIfAbsent(patch.y(), y -> emptySection(y));
      Section currentSection = blocks(raw);
      Section target = patch.section();
      var blocks = new ArrayList<>(currentSection.blocks());
      for (int i = 0; i < 4096; i++) if (patch.covers(i)) blocks.set(i, target.block(i));
      raw.put("block_states", blockStates(new Section(blocks, Map.of())));
      var oldBes = new HashMap<Integer, Nbt.Compound>();
      bes.removeIf(value -> {
        var be = (Nbt.Compound)value;
        int y = be.integer("y", 0), index = (be.integer("x",0)&15)
            | ((be.integer("z",0)&15)<<4) | ((y&15)<<8);
        if (Math.floorDiv(y,16) == patch.y() && patch.covers(index)) {
          oldBes.put(index, be); return true;
        }
        return false;
      });
      for (var entry : target.blockEntities().entrySet()) {
        int i = entry.getKey();
        if (!patch.covers(i)) continue;
        var be = Nbt.read(entry.getValue());
        var old = oldBes.get(i);
        if (old != null && old.string("id").equals(be.string("id")))
          old.forEach((k,v) -> { if (rules.ignoredField(be.string("id"), k, false)) be.put(k,Nbt.copy(v)); });
        be.put("x", op.pos().x()*16+(i&15));
        be.put("y", patch.y()*16+(i>>8));
        be.put("z", op.pos().z()*16+((i>>4)&15));
        bes.add(be);
      }
    }
    for (var patch : op.biomes().values()) {
      var raw = sections.computeIfAbsent(patch.y(), y -> emptySection(y));
      var samples = biomeSamples(raw);
      for (int i=0;i<64;i++) if (!patch.samples().get(i).isEmpty()) samples.set(i, patch.samples().get(i));
      raw.put("biomes", biomes(samples));
    }
    // 光照與 heightmap 是整個 chunk 的衍生資料，裁切也必須讓它重算。
    for (var section : sections.values())
      section.keySet().removeIf(k -> k.equals("BlockLight") || k.equals("SkyLight") || k.startsWith("starlight."));
    out.keySet().removeIf(k -> k.equals("Heightmaps") || k.startsWith("starlight."));
    out.put("isLightOn", (byte)0);
    out.put("sections", new Nbt.ListTag(10,new ArrayList<>(sections.values())));
    out.put("block_entities",new Nbt.ListTag(10,bes));
    if (op.setTicks()) {
      var ticks = op.ticks() == null ? new Nbt.Compound() : Nbt.read(op.ticks());
      for (String k : List.of("block_ticks","fluid_ticks")) out.put(k,ticks.list(k));
    }
    if (op.setStructures()) out.put("structures", op.structures() == null
        ? new Nbt.Compound().with("References",new Nbt.Compound()).with("starts",new Nbt.Compound())
        : Nbt.read(op.structures()));
    out.put("DataVersion", version);
    out.put("Status","minecraft:full");
    out.put("xPos",op.pos().x()); out.put("zPos",op.pos().z());
    out.putIfAbsent("yPos",sections.isEmpty() ? -4 : sections.firstKey());
    out.putIfAbsent("LastUpdate",0L); out.putIfAbsent("InhabitedTime",0L);
    return out;
  }

  private static Nbt.Compound emptySection(int y) {
    return new Nbt.Compound().with("Y",(byte)y).with("block_states",blockStates(Section.air()))
        .with("biomes",biomes(Collections.nCopies(64,"minecraft:plains")));
  }

  public static Section blocks(Nbt.Compound raw) {
    var bs=raw.compound("block_states");
    var palette = new ArrayList<BlockState>();
    for (Object value : bs.list("palette").values()) {
      var p=(Nbt.Compound)value;
      var properties=new TreeMap<String,String>();
      p.compound("Properties").forEach((k,v)->properties.put(k,(String)v));
      palette.add(new BlockState(p.string("Name"),properties));
    }
    if (palette.isEmpty()) return Section.air();
    int[] indices=ChunkNormalizer.unpack(bs,palette.size(),4,4096);
    var blocks=new ArrayList<BlockState>(4096);
    for(int i:indices) blocks.add(palette.get(i));
    return new Section(blocks,Map.of());
  }

  public static Nbt.Compound blockStates(Section section) {
    var palette=new LinkedHashMap<BlockState,Integer>();
    int[] indices=new int[4096];
    for(int i=0;i<4096;i++) indices[i]=palette.computeIfAbsent(section.block(i),k->palette.size());
    var entries=new ArrayList<Object>();
    for(var state:palette.keySet()) {
      var p=new Nbt.Compound().with("Name",state.name());
      if(!state.properties().isEmpty()) { var props=new Nbt.Compound(); state.properties().forEach(props::put); p.put("Properties",props); }
      entries.add(p);
    }
    var bs=new Nbt.Compound().with("palette",new Nbt.ListTag(10,entries));
    if(palette.size()>1) bs.put("data",ChunkNormalizer.pack(indices,Math.max(4,ChunkNormalizer.ceilLog2(palette.size()))));
    return bs;
  }

  private static ArrayList<String> biomeSamples(Nbt.Compound raw) {
    var b=raw.compound("biomes"); var palette=b.list("palette").values();
    var samples=new ArrayList<String>(64);
    if(palette.isEmpty()) { samples.addAll(Collections.nCopies(64,"minecraft:plains")); return samples; }
    for(int i:ChunkNormalizer.unpack(b,palette.size(),1,64)) samples.add((String)palette.get(i));
    return samples;
  }

  public static Nbt.Compound biomes(List<String> samples) {
    var palette=new LinkedHashMap<String,Integer>(); int[] indices=new int[64];
    for(int i=0;i<64;i++) indices[i]=palette.computeIfAbsent(samples.get(i),k->palette.size());
    var out=new Nbt.Compound().with("palette",new Nbt.ListTag(8,new ArrayList<>(palette.keySet())));
    if(palette.size()>1) out.put("data",ChunkNormalizer.pack(indices,Math.max(1,ChunkNormalizer.ceilLog2(palette.size()))));
    return out;
  }
}
