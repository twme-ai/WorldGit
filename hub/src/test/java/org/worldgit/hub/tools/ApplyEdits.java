package org.worldgit.hub.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.anvil.RegionFile;
import org.worldgit.core.anvil.WorldLayout;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.normalize.ChunkNormalizer;

/**
 * 驗收／示範用：對「關閉中」的世界複本直接改方塊（離線寫 .mca），之後用 wgit commit 就會產生有 diff 的 commit。
 * 用法：ApplyEdits &lt;world 目錄&gt; &lt;edits.json&gt;；JSON 為 {"ops":[{"op":"set","pos":[x,y,z],"state":"minecraft:stone"},
 * {"op":"fill","from":[..],"to":[..],"state":"..."}]}，僅修改主世界已存在的 section。
 */
public final class ApplyEdits {
  private ApplyEdits() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("用法：ApplyEdits <world> <edits.json>");
    int n = apply(Path.of(args[0]), new ObjectMapper().readTree(Path.of(args[1]).toFile()).get("ops"));
    System.out.println("已修改 " + n + " 格");
  }

  public static int apply(Path world, JsonNode ops) throws IOException {
    var layout = WorldLayout.discover(world);
    Path region = layout.dimensions().get(DimensionId.OVERWORLD).region();
    // chunk → section → 索引 → 狀態
    var edits = new TreeMap<ChunkPos, Map<Integer, Map<Integer, String>>>();
    int count = 0;
    for (JsonNode op : ops) {
      String state = op.get("state").asText();
      if (op.get("op").asText().equals("set")) {
        put(edits, pos(op.get("pos")), state);
        count++;
      } else {
        int[] a = pos(op.get("from")), b = pos(op.get("to"));
        for (int x = Math.min(a[0], b[0]); x <= Math.max(a[0], b[0]); x++)
          for (int y = Math.min(a[1], b[1]); y <= Math.max(a[1], b[1]); y++)
            for (int z = Math.min(a[2], b[2]); z <= Math.max(a[2], b[2]); z++) {
              put(edits, new int[] {x, y, z}, state);
              count++;
            }
      }
    }
    var byRegion = new TreeMap<String, Map<Integer, Nbt.Compound>>();
    var regionFiles = new HashMap<String, Path>();
    for (var chunk : edits.entrySet()) {
      ChunkPos pos = chunk.getKey();
      Path path = region.resolve(pos.regionName() + ".mca");
      Nbt.Compound raw;
      try (var r = new RegionFile(path)) {
        raw = byRegion.getOrDefault(pos.regionName(), Map.of()).get(pos.regionIndex());
        if (raw == null) raw = r.read(pos.regionIndex());
      }
      for (var sec : chunk.getValue().entrySet()) editSection(raw, sec.getKey(), sec.getValue());
      byRegion.computeIfAbsent(pos.regionName(), k -> new HashMap<>()).put(pos.regionIndex(), raw);
      regionFiles.put(pos.regionName(), path);
    }
    int ts = (int) (System.currentTimeMillis() / 1000);
    for (var e : byRegion.entrySet()) RegionFile.update(regionFiles.get(e.getKey()), e.getValue(), ts);
    return count;
  }

  private static int[] pos(JsonNode n) {
    return new int[] {n.get(0).asInt(), n.get(1).asInt(), n.get(2).asInt()};
  }

  private static void put(TreeMap<ChunkPos, Map<Integer, Map<Integer, String>>> edits, int[] p, String state) {
    var chunk = edits.computeIfAbsent(new ChunkPos(Math.floorDiv(p[0], 16), Math.floorDiv(p[2], 16)), k -> new TreeMap<>());
    int index = Math.floorMod(p[0], 16) | Math.floorMod(p[2], 16) << 4 | Math.floorMod(p[1], 16) << 8;
    chunk.computeIfAbsent(Math.floorDiv(p[1], 16), k -> new TreeMap<>()).put(index, state);
  }

  private static Nbt.Compound parseState(String s) {
    int i = s.indexOf('[');
    var c = new Nbt.Compound().with("Name", i < 0 ? s : s.substring(0, i));
    if (i >= 0) {
      var props = new Nbt.Compound();
      for (String kv : s.substring(i + 1, s.length() - 1).split(",")) {
        String[] p = kv.split("=");
        props.put(p[0], p[1]);
      }
      c.put("Properties", props);
    }
    return c;
  }

  private static void editSection(Nbt.Compound chunk, int sy, Map<Integer, String> edits) {
    for (Object o : chunk.list("sections").values()) {
      var section = (Nbt.Compound) o;
      if (section.integer("Y", 0) != sy) continue;
      var bs = section.compound("block_states");
      var palette = new ArrayList<>(bs.list("palette").values());
      int[] indices = ChunkNormalizer.unpack(bs, palette.size(), 4, 4096);
      for (var e : edits.entrySet()) {
        var want = parseState(e.getValue());
        int idx = palette.indexOf(want);
        if (idx < 0) {
          palette.add(want);
          idx = palette.size() - 1;
        }
        indices[e.getKey()] = idx;
      }
      bs.put("palette", new Nbt.ListTag(10, palette));
      bs.put("data", ChunkNormalizer.pack(indices, Math.max(4, ChunkNormalizer.ceilLog2(palette.size()))));
      return;
    }
    throw new IllegalStateException("找不到 section " + sy + "（此工具只改已存在的 section）");
  }
}
