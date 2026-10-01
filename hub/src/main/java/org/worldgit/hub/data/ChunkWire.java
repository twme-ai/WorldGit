package org.worldgit.hub.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.diff.WorldDiff;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.core.normalize.SnapshotCodec;
import org.worldgit.core.store.ObjectStore;

/**
 * Hub → 瀏覽器的二進位格式（big-endian，由 HTTP gzip 壓縮）。所有數字用 DataOutputStream。
 *
 * <pre>
 * WGCK v1（方塊資料）  magic "WGCK" u8 version=1  u16 nStates {utf8 state}  u16 nBiomes {utf8 id}  u32 nChunks
 *   chunk: i32 cx  i32 cz  u8 nSections
 *     section: i8 sy  u16 paletteCount  u16[paletteCount] 全域 state 編號  u8 bits  byte[(4096*bits+7)/8] 位元流
 *              u16 nBlockEntities {u16 pos  utf8-json}   // 只含橫幅／告示牌／頭顱，供特殊模型使用
 *     u8 nBiomeSections {i8 sy  u8 uniform(1 時只有一個 u16；0 時 64 個 u16)}
 *
 * WGDF v1（diff）  magic "WGDF" u8 version=1  u16 nStates {utf8 state}  u32 nSections
 *   section: i32 cx  i32 cz  i8 sy  u16 count  {u16 index  u8 kind(1新增 2移除 3修改)  u16 beforeState}
 * </pre>
 */
final class ChunkWire {
  private static final Pattern BE_NAMES = Pattern.compile(".*(_banner|_sign|_hanging_sign|_head|_skull)$");
  private static final ObjectMapper JSON = new ObjectMapper();

  /** utf8 字串：u16 長度 + bytes。 */
  static void utf(DataOutputStream out, String s) throws IOException {
    byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    out.writeShort(b.length);
    out.write(b);
  }

  static final class Table {
    final LinkedHashMap<String, Integer> ids = new LinkedHashMap<>();

    int id(String s) {
      return ids.computeIfAbsent(s, k -> ids.size());
    }

    void write(DataOutputStream out) throws IOException {
      out.writeShort(ids.size());
      for (String s : ids.keySet()) utf(out, s);
    }
  }

  /** 編碼 chunk 視窗內有資料的 chunk。 */
  static byte[] chunks(ObjectStore store, TreeNav nav, Collection<ChunkPos> window) throws IOException {
    var states = new Table();
    var biomes = new Table();
    var body = new ByteArrayOutputStream();
    var out = new DataOutputStream(body);
    int count = 0;
    for (ChunkPos pos : window) {
      var files = nav.chunk(pos);
      if (files == null) continue;
      count++;
      out.writeInt(pos.x());
      out.writeInt(pos.z());
      var sections = new ArrayList<Map.Entry<Integer, ObjectStore.Entry>>();
      for (var e : files.entrySet()) {
        String n = e.getKey();
        if (n.startsWith("s.") && n.endsWith(".bin")) sections.add(Map.entry(Integer.parseInt(n.substring(2, n.length() - 4)), e.getValue()));
      }
      out.writeByte(sections.size());
      for (var s : sections) {
        SectionBlob b = SectionBlob.parse(store.readBlob(s.getValue().id()));
        out.writeByte(s.getKey());
        out.writeShort(b.palette().length);
        for (String p : b.palette()) out.writeShort(states.id(p));
        out.writeByte(b.bits());
        out.write(b.packed());
        var bes = new ArrayList<Map.Entry<Integer, String>>();
        for (var be : b.blockEntities().entrySet()) {
          if (!BE_NAMES.matcher(SectionBlob.blockName(b.palette()[b.index(be.getKey())])).matches()) continue;
          bes.add(Map.entry(be.getKey(), beJson(be.getValue())));
        }
        out.writeShort(bes.size());
        for (var be : bes) {
          out.writeShort(be.getKey());
          utf(out, be.getValue());
        }
      }
      var bio = files.get("biomes.bin");
      if (bio == null) out.writeByte(0);
      else {
        var map = SnapshotCodec.biomes(store.readBlob(bio.id()));
        out.writeByte(map.size());
        for (var e : map.entrySet()) {
          out.writeByte(e.getKey());
          var list = e.getValue();
          boolean uniform = list.stream().allMatch(list.get(0)::equals);
          out.writeByte(uniform ? 1 : 0);
          for (int i = 0; i < (uniform ? 1 : 64); i++) out.writeShort(biomes.id(list.get(i).isEmpty() ? "minecraft:plains" : list.get(i)));
        }
      }
    }
    var result = new ByteArrayOutputStream();
    var head = new DataOutputStream(result);
    head.writeBytes("WGCK");
    head.writeByte(1);
    states.write(head);
    biomes.write(head);
    head.writeInt(count);
    body.writeTo(result);
    return result.toByteArray();
  }

  /** 只輸出前端特殊模型需要的欄位（橫幅圖樣、告示牌文字）。 */
  static String beJson(byte[] nbt) throws IOException {
    Nbt.Compound c = Nbt.read(nbt);
    ObjectNode o = JSON.createObjectNode();
    o.put("id", c.string("id"));
    var patterns = c.list("patterns");
    if (!patterns.values().isEmpty()) {
      var arr = o.putArray("patterns");
      for (Object p : patterns.values())
        if (p instanceof Nbt.Compound pc) arr.addObject().put("pattern", pc.string("pattern")).put("color", pc.string("color"));
    }
    for (String side : List.of("front_text", "back_text")) {
      if (c.get(side) instanceof Nbt.Compound t) {
        ObjectNode text = o.putObject(side);
        var lines = text.putArray("messages");
        for (Object m : t.list("messages").values()) lines.add(String.valueOf(m));
        text.put("color", t.string("color"));
      }
    }
    return JSON.writeValueAsString(o);
  }

  static byte[] diff(WorldDiff diff) throws IOException {
    var states = new Table();
    var body = new ByteArrayOutputStream();
    var out = new DataOutputStream(body);
    int n = 0;
    for (var s : diff.sections()) {
      if (s.blocks().isEmpty()) continue;
      n++;
      out.writeInt(s.chunk().x());
      out.writeInt(s.chunk().z());
      out.writeByte(s.sectionY());
      out.writeShort(s.blocks().size());
      for (var b : s.blocks()) {
        int lx = Math.floorMod(b.pos().x(), 16), lz = Math.floorMod(b.pos().z(), 16), ly = Math.floorMod(b.pos().y(), 16);
        out.writeShort(lx | lz << 4 | ly << 8);
        out.writeByte(b.kind().ordinal() + 1);
        out.writeShort(states.id(b.before().canonical()));
      }
    }
    var result = new ByteArrayOutputStream();
    var head = new DataOutputStream(result);
    head.writeBytes("WGDF");
    head.writeByte(1);
    states.write(head);
    head.writeInt(n);
    body.writeTo(result);
    return result.toByteArray();
  }
}
