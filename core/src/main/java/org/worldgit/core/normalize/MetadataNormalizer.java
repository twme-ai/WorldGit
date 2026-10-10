package org.worldgit.core.normalize;

import java.io.IOException;
import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.anvil.SavedData;
import org.worldgit.core.config.IgnoreRules;

/** world-meta 的 field selector；設定檔由 service 加入，不受 NBT 欄位規則影響。 */
public final class MetadataNormalizer {
  private MetadataNormalizer() {}

  public static SortedMap<String, byte[]> normalize(Map<String, byte[]> metadata, IgnoreRules rules)
      throws IOException {
    var result = new TreeMap<String, byte[]>();
    for (var entry : metadata.entrySet()) {
      byte[] bytes = SavedData.normalize(entry.getKey(), entry.getValue());
      if (bytes == null) continue;
      String type = type(entry.getKey());
      if (type == null) {
        result.put(entry.getKey(), bytes.clone());
        continue;
      }
      var data = Nbt.read(bytes);
      if (chunkTickets(entry.getKey())) stableChunkTickets(data);
      if (entry.getKey().equals("level.nbt") && data.containsKey("DataPacks"))
        data.put("DataPacks", portablePacks(data.compound("DataPacks")));
      data.keySet().removeIf(field -> rules.ignoredField(type, field, false));
      if (!data.isEmpty()) result.put(entry.getKey(), Nbt.write(data));
    }
    return result;
  }

  private static boolean chunkTickets(String name) {
    int start = name.lastIndexOf("saved.");
    if (start < 0 || !name.endsWith(".nbt")) return false;
    try {
      String path = new String(Base64.getUrlDecoder().decode(name.substring(start + 6, name.length() - 4)),
          java.nio.charset.StandardCharsets.UTF_8);
      return path.equals("data/minecraft/chunk_tickets.dat");
    } catch (IllegalArgumentException invalid) { return false; }
  }

  /** TicketStorage packs hash-map entries; their order has no meaning, but every field does. */
  private static void stableChunkTickets(Nbt.Compound root) throws IOException {
    var data = root.compound("data");
    if (!(data.get("tickets") instanceof Nbt.ListTag tickets) || tickets.type() != 10) return;
    record Encoded(Object value, byte[] bytes) {}
    var sorted = new ArrayList<Encoded>();
    for (Object ticket : tickets.values()) sorted.add(new Encoded(ticket, Nbt.write((Nbt.Compound) ticket)));
    sorted.sort((a, b) -> Arrays.compareUnsigned(a.bytes(), b.bytes()));
    data.put("tickets", new Nbt.ListTag(tickets.type(), sorted.stream().map(Encoded::value).toList()));
  }

  /** Paper 標記與 Fabric API 共用標籤包由平台提供；實際世界資料包及其順序完整保留。 */
  public static Nbt.Compound portablePacks(Nbt.Compound packs) {
    var out = Nbt.copy(packs);
    for (String key : List.of("Enabled", "Disabled"))
      if (out.containsKey(key)) {
        var list = out.list(key);
        out.put(
            key,
            new Nbt.ListTag(
                list.type(),
                list.values().stream()
                    .filter(v -> !v.equals("paper") && !v.equals("fabric-convention-tags-v2"))
                    .toList()));
      }
    return out;
  }

  public static String type(String name) {
    if (name.contains("saved.") && name.endsWith(".nbt")) return "worldgit:saved_data";
    if (name.equals("level.nbt")) return "worldgit:level";
    if (name.matches("map_\\d+\\.dat\\.nbt")) return "worldgit:map";
    if (name.equals("scoreboard.dat.nbt")) return "worldgit:scoreboard";
    if (name.equals("custom_boss_events.dat.nbt")) return "worldgit:boss_events";
    if (name.endsWith("game_rules.dat.nbt")) return "worldgit:gamerules";
    if (name.endsWith("world_border.dat.nbt")) return "worldgit:border";
    if (name.endsWith("world_gen_settings.dat.nbt")) return "worldgit:worldgen";
    return null;
  }
}
