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
  private static final Set<String> BOAT_WOODS = Set.of("oak", "spruce", "birch", "jungle",
      "acacia", "dark_oak", "mangrove", "cherry", "bamboo", "pale_oak");
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
        var source = chunk.integer("DataVersion", 0) >= 5023 ? Nbt.copy(be) : be;
        if (chunk.integer("DataVersion", 0) >= 5023) canonical263BlockEntity(source);
        source = org.worldgit.core.version.NativeNbtForms.blockEntity(source, chunk.integer("DataVersion", 0));
        source.forEach(
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
        var canonical = chunk.integer("DataVersion", 0) <= 4189 ? LegacyText.normalize(clean) : clean;
        if (map.put(index, Nbt.write(canonical)) != null)
          throw new IllegalArgumentException("block entity 座標重複");
      }
      for (Object o : chunk.list("sections").values()) {
        var s = (Nbt.Compound) o;
        int y = s.integer("Y", 0);
        var bs = s.compound("block_states");
        var palette = bs.list("palette").values();
        var blocks = new ArrayList<BlockState>();
        if (!palette.isEmpty()) {
          var states = new ArrayList<BlockState>();
          boolean needsBlocks = bes.containsKey(y);
          for (Object entry : palette) {
            var state = org.worldgit.core.version.BlockStateNbt.read(entry,chunk.integer("DataVersion",0));
            states.add(state);
            needsBlocks |= !state.air();
          }
          int[] indices = states.size() == 1 ? null : unpack(bs, states.size(), 4, 4096);
          // 單項 palette 的 index 固定為 0；其他 palette 即使全空也保留 unpack 的格式／越界驗證。
          if (needsBlocks) blocks.ensureCapacity(4096);
          if (needsBlocks && states.size() == 1 && !rules.hasAreas())
            blocks.addAll(Collections.nCopies(4096, states.getFirst()));
          else if (needsBlocks) for (int i = 0; i < 4096; i++) {
            int x = pos.x() * 16 + (i & 15),
                z = pos.z() * 16 + ((i >> 4) & 15),
                gy = y * 16 + (i >> 8);
            blocks.add(
                rules.hasAreas() && rules.ignoredBlock(x, gy, z)
                    ? BlockState.AIR
                    : states.get(indices == null ? 0 : indices[i]));
          }
        } else if (bes.containsKey(y)) blocks.addAll(Collections.nCopies(4096, BlockState.AIR));
        if (!blocks.isEmpty()) {
          Section section = new Section(blocks, bes.getOrDefault(y, Map.of()));
          if (!section.empty()) sections.put(y, section);
        }
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
        if (!rules.ignoredEntity(e, semantics)) entities.add(EntityNormalizer.normalize(
            canonicalEntity(e, chunk.integer("DataVersion", 0)), rules));
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
          if (!rules.ignoredBlock(c.integer("x", 0), c.integer("y", 0), c.integer("z", 0))) {
            var copy = Nbt.copy(c);
            // 決策 #169：負的剩餘延遲（已到期）一律視為 0，內容不再隨遊戲時間變動。
            if (copy.integer("t", 0) < 0) copy.put("t", 0);
            list.add(copy);
          }
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

  private static Nbt.Compound canonicalEntity(Nbt.Compound entity, int version) {
    var source = nativeBoat(entity, version);
    return version <= 4189 ? LegacyText.normalize(source)
        : org.worldgit.core.version.NativeNbtForms.entity(source, version);
  }

  /** DFU retains Type after the split; the native fixed-wood boat codec omits it. */
  private static Nbt.Compound nativeBoat(Nbt.Compound entity, int version) {
    if (version < 4189) return entity;
    var result = entity;
    if (entity.get("Type") instanceof String wood && BOAT_WOODS.contains(wood)) {
      String prefix = "minecraft:" + wood;
      String id = entity.string("id");
      if (id.equals(prefix + "_boat") || id.equals(prefix + "_chest_boat")
          || wood.equals("bamboo") && (id.equals(prefix + "_raft") || id.equals(prefix + "_chest_raft"))) {
        result = Nbt.copy(entity);
        result.remove("Type");
      }
    }
    if (entity.get("Passengers") instanceof Nbt.ListTag passengers) {
      ArrayList<Object> changed = null;
      for (int i = 0; i < passengers.values().size(); i++) {
        var before = (Nbt.Compound) passengers.values().get(i);
        var after = nativeBoat(before, version);
        if (after != before) {
          if (changed == null) changed = new ArrayList<>(passengers.values());
          changed.set(i, after);
        }
      }
      if (changed != null) {
        if (result == entity) result = Nbt.copy(entity);
        result.put("Passengers", new Nbt.ListTag(passengers.type(), changed));
      }
    }
    return result;
  }

  private static void canonical263BlockEntity(Nbt.Compound data) {
    data.putIfAbsent("components", new Nbt.Compound());
    var fields = switch (data.string("id")) {
      case "minecraft:furnace", "minecraft:smoker", "minecraft:blast_furnace" -> {
        data.putIfAbsent("speed_multiplier", 1f);
        data.putIfAbsent("lit_total_time", 0);
        yield List.of("cooking_total_time", "cooking_time_spent", "lit_time_remaining", "lit_total_time");
      }
      case "minecraft:brewing_stand" -> List.of("BrewTime", "Fuel");
      default -> List.<String>of();
    };
    for (String field : fields)
      if (data.get(field) instanceof Number value && value.longValue() >= Integer.MIN_VALUE
          && value.longValue() <= Integer.MAX_VALUE && value.doubleValue() == value.longValue())
        data.put(field, value.intValue());
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
