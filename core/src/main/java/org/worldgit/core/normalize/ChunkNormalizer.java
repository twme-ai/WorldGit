package org.worldgit.core.normalize;

import java.io.*;
import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.config.*;
import org.worldgit.core.model.*;

/** NBT -> 中性不可變快照。只擷取追蹤欄位，光照/Heightmaps/POI 永遠不進模型。 */
public final class ChunkNormalizer {
  private final IgnoreRules rules;
  private final EntitySemantics semantics;
  private static final Set<String> FURNACE =
      Set.of("lit_time_remaining", "cooking_time_spent", "BurnTime", "CookTime");
  private static final Map<String, Set<String>> BE_IGNORE =
      Map.of(
          "minecraft:furnace",
          FURNACE,
          "minecraft:blast_furnace",
          FURNACE,
          "minecraft:smoker",
          FURNACE,
          "minecraft:mob_spawner",
          Set.of("Delay"),
          "minecraft:jukebox",
          Set.of("ticks_since_song_started"),
          "minecraft:command_block",
          Set.of("LastExecution", "SuccessCount"),
          "minecraft:brewing_stand",
          Set.of("BrewTime"),
          "minecraft:campfire",
          Set.of("CookingTimes"),
          "minecraft:hopper",
          Set.of("TransferCooldown"));

  public ChunkNormalizer(IgnoreRules rules, EntitySemantics semantics) {
    this.rules = rules;
    this.semantics = semantics;
  }

  public static boolean full(Nbt.Compound chunk) {
    return Set.of("full", "minecraft:full").contains(chunk.string("Status"));
  }

  public ChunkSnapshot normalize(ChunkPos pos, Nbt.Compound chunk, List<Nbt.Compound> entityData)
      throws IOException {
    try {
      var sections = new TreeMap<Integer, Section>();
      var biomes = new TreeMap<Integer, List<String>>();
      var bes = new HashMap<Integer, Map<Integer, byte[]>>();
      for (Object o : chunk.list("block_entities").values()) {
        var be = (Nbt.Compound) o;
        int x = be.integer("x", 0), y = be.integer("y", 0), z = be.integer("z", 0);
        if (rules.ignoredBlock(x, y, z)) continue;
        var clean = new Nbt.Compound();
        String id = be.string("id");
        be.forEach(
            (k, v) -> {
              if (!Set.of("x", "y", "z").contains(k)
                  && !rules.ignoredField(
                      id,
                      k,
                      EntityNormalizer.platformField(k)
                          || BE_IGNORE.getOrDefault(id, Set.of()).contains(k)))
                clean.put(k, Nbt.copy(v));
            });
        var map = bes.computeIfAbsent(Math.floorDiv(y, 16), k -> new TreeMap<>());
        int index = (x & 15) | ((z & 15) << 4) | ((y & 15) << 8);
        if (map.put(index, Nbt.write(clean)) != null)
          throw new IllegalArgumentException("block entity 座標重複");
      }
      for (Object o : chunk.list("sections").values()) {
        var s = (Nbt.Compound) o;
        int y = s.integer("Y", 0);
        var bs = s.compound("block_states");
        var palette = bs.list("palette").values();
        var blocks = new ArrayList<BlockState>(4096);
        if (!palette.isEmpty()) {
          var states = new ArrayList<BlockState>();
          for (Object entry : palette) {
            var p = (Nbt.Compound) entry;
            var properties = new TreeMap<String, String>();
            p.compound("Properties").forEach((k, v) -> properties.put(k, String.valueOf(v)));
            states.add(new BlockState(p.string("Name"), properties));
          }
          int[] indices = unpack(bs, states.size(), 4, 4096);
          for (int i = 0; i < 4096; i++) {
            int x = pos.x() * 16 + (i & 15),
                z = pos.z() * 16 + ((i >> 4) & 15),
                gy = y * 16 + (i >> 8);
            blocks.add(
                rules.hasAreas() && rules.ignoredBlock(x, gy, z)
                    ? BlockState.AIR
                    : states.get(indices[i]));
          }
        } else blocks.addAll(Collections.nCopies(4096, BlockState.AIR));
        Section section = new Section(blocks, bes.getOrDefault(y, Map.of()));
        if (!section.empty()) sections.put(y, section);
        var b = s.compound("biomes");
        var bp = b.list("palette").values();
        if (!bp.isEmpty()) {
          int[] indices = unpack(b, bp.size(), 1, 64);
          var names = new ArrayList<String>(64);
          for (int i = 0; i < 64; i++) {
            int x = pos.x() * 16 + (i & 3) * 4,
                z = pos.z() * 16 + ((i >> 2) & 3) * 4,
                gy = y * 16 + (i >> 4) * 4;
            // biome 以 4x4x4 為粒度，區域內的 sample 用空字串表示未追蹤。
            names.add(rules.ignoredBlock(x, gy, z) ? "" : (String) bp.get(indices[i]));
          }
          if (names.stream().anyMatch(n -> !n.isEmpty())) biomes.put(y, names);
        }
      }
      // 不應默默丟棄有 BE 卻沒有 sections 的損毀 chunk。
      for (int y : bes.keySet())
        if (!sections.containsKey(y))
          sections.put(y, new Section(Collections.nCopies(4096, BlockState.AIR), bes.get(y)));
      var entities = new ArrayList<EntitySnapshot>();
      for (var e : entityData)
        if (!rules.ignoredEntity(e, semantics)) entities.add(EntityNormalizer.normalize(e, rules));
      entities.sort(Comparator.comparing(e -> e.uuid().toString()));
      var ticks = new Nbt.Compound();
      Comparator<Nbt.Compound> order =
          Comparator.comparingInt((Nbt.Compound c) -> c.integer("y", 0))
              .thenComparingInt(c -> c.integer("z", 0))
              .thenComparingInt(c -> c.integer("x", 0))
              .thenComparing(c -> c.string("i"))
              .thenComparingInt(c -> c.integer("p", 0))
              .thenComparingInt(c -> c.integer("t", 0));
      for (String key : List.of("block_ticks", "fluid_ticks")) {
        var list = new ArrayList<Nbt.Compound>();
        for (Object t : chunk.list(key).values()) {
          var c = (Nbt.Compound) t;
          if (!rules.ignoredBlock(c.integer("x", 0), c.integer("y", 0), c.integer("z", 0)))
            list.add(Nbt.copy(c));
        }
        list.sort(order);
        if (!list.isEmpty()) ticks.put(key, new Nbt.ListTag(10, new ArrayList<>(list)));
      }
      var structures = Nbt.copy(chunk.compound("structures"));
      // References 是 chunk 座標集合，MC 的 LongSet 載入/存檔可能改變 long[] 順序。
      structures
          .compound("References")
          .replaceAll(
              (id, value) -> {
                if (value instanceof long[] refs) {
                  long[] sorted = refs.clone();
                  Arrays.sort(sorted);
                  return sorted;
                }
                return value;
              });
      boolean noStructures =
          structures.compound("References").isEmpty() && structures.compound("starts").isEmpty();
      return new ChunkSnapshot(
          pos,
          chunk.integer("DataVersion", 0),
          sections,
          biomes,
          entities,
          ticks.isEmpty() ? null : Nbt.write(ticks),
          noStructures ? null : Nbt.write(structures));
    } catch (IllegalArgumentException | ClassCastException | IndexOutOfBoundsException e) {
      throw new IOException("chunk " + pos + " 正規化失敗：" + e.getMessage(), e);
    }
  }

  public static int ceilLog2(int n) {
    return n <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(n - 1);
  }

  public static int[] unpack(Nbt.Compound data, int paletteSize, int minimumBits, int count) {
    int[] indices = new int[count];
    if (paletteSize == 1) return indices;
    int bits = Math.max(minimumBits, ceilLog2(paletteSize)), perLong = 64 / bits;
    if (!(data.get("data") instanceof long[] raw) || raw.length != (count + perLong - 1) / perLong)
      throw new IllegalArgumentException("palette data 長度無效");
    long mask = (1L << bits) - 1;
    for (int i = 0; i < count; i++) {
      indices[i] = (int) ((raw[i / perLong] >>> ((i % perLong) * bits)) & mask);
      if (indices[i] >= paletteSize) throw new IllegalArgumentException("palette 索引越界");
    }
    return indices;
  }

  public static long[] pack(int[] values, int bits) {
    int perLong = 64 / bits;
    long[] raw = new long[(values.length + perLong - 1) / perLong];
    for (int i = 0; i < values.length; i++)
      raw[i / perLong] |= (long) values[i] << ((i % perLong) * bits);
    return raw;
  }
}
