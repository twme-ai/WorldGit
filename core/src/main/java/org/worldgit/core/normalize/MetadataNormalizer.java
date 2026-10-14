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
    metadata = nativeLayout(metadata);
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

  /** 1.21.11 的 LEVEL DFU 暫存 border 與原生 SavedData 為同一組設定。 */
  public static SortedMap<String, byte[]> nativeLayout(Map<String, byte[]> metadata) throws IOException {
    var out = new TreeMap<>(metadata);
    if(metadata.containsKey("level.nbt")) {
      var level=Nbt.read(metadata.get("level.nbt"));
      int version=level.integer("DataVersion",0);
      if(version>=3465 && version<=4671 && level.get("DragonFight") instanceof Nbt.Compound dragon
          && Set.of("DragonKilled","PreviouslyKilled","NeedsStateScanning","Dragon","Gateways",
              "ExitPortalLocation","IsRespawning").containsAll(dragon.keySet())
          && dragon.get("Gateways") instanceof int[] gateways) {
        var values=new ArrayList<Object>();for(int gateway:gateways)values.add(gateway);
        dragon.put("Gateways",new Nbt.ListTag(values.isEmpty()?0:3,values));
        out.put("level.nbt",Nbt.write(level));
      }
    }
    for (var entry : metadata.entrySet()) {
      if (!"data/minecraft/ender_dragon_fight.dat".equals(SavedData.relativePath(entry.getKey()))) continue;
      var root = Nbt.read(entry.getValue());
      if (Set.of(4790, 4903, 5023).contains(root.integer("DataVersion", 0))
          && Set.of("data", "DataVersion").containsAll(root.keySet())
          && root.get("data") instanceof Nbt.Compound dragon
          && Set.of("needs_state_scanning", "dragon_killed", "previously_killed", "respawn_stage",
              "respawn_time", "dragon_uuid", "exit_portal_location", "gateways", "respawn_crystals")
              .containsAll(dragon.keySet())
          && dragon.get("gateways") instanceof int[] gateways) {
        // Codec.INT.list() accepts both NBT forms; NbtOps packs native lists as
        // int arrays, while LEVEL DFU can preserve the legacy ordered list.
        var values = new ArrayList<Object>();
        for (int gateway : gateways) values.add(gateway);
        dragon.put("gateways", new Nbt.ListTag(values.isEmpty() ? 0 : 3, values));
        out.put(entry.getKey(), Nbt.write(root));
      }
    }
    for(var entry:metadata.entrySet())
      if(entry.getKey().equals("world_border.dat.nbt") || entry.getKey().matches("[^.]+\\.[^.]+\\.world_border\\.dat\\.nbt")) {
        var root=Nbt.read(entry.getValue());
        int version=root.integer("DataVersion",0);
        if(version>=4671 && version<=5023 && Set.of("data","DataVersion").containsAll(root.keySet())
            && root.get("data") instanceof Nbt.Compound border && staticBorder(border)) {
          root.put("data",border);out.put(entry.getKey(),Nbt.write(root));
        }
      }
    if (!metadata.containsKey("level.nbt")) return out;
    var level = Nbt.read(out.get("level.nbt"));
    if (level.integer("DataVersion",0) != 4671 || !(level.get("world_border") instanceof Nbt.Compound border)) return out;
    staticBorder(border);
    var nativeFile = out.containsKey("world_border.dat.nbt") ? Nbt.read(out.get("world_border.dat.nbt")) : null;
    if (nativeFile != null && (!Set.of("data","DataVersion").containsAll(nativeFile.keySet())
        || !Arrays.equals(Nbt.write(border),Nbt.write(nativeFile.compound("data"))))) return out;
    if (nativeFile == null) out.put("world_border.dat.nbt",Nbt.write(new Nbt.Compound().with("DataVersion",4671).with("data",Nbt.copy(border))));
    level.remove("world_border");out.put("level.nbt",Nbt.write(level));
    return out;
  }

  /** 原生 StaticBorderExtent 的 target 等於 size；零時長時原版不使用舊 target。
   * 保留活動插值、未知 schema、custom payload 與其他全部設定。 */
  private static boolean staticBorder(Nbt.Compound border) {
    if(!Set.of("center_x","center_z","damage_per_block","safe_zone","size","warning_blocks",
        "warning_time","lerp_time","lerp_target").containsAll(border.keySet())
        || !(border.get("lerp_time") instanceof Long time) || time!=0L
        || !(border.get("size") instanceof Double size) || !Double.isFinite(size) || size<0
        || !(border.get("lerp_target") instanceof Double target) || !Double.isFinite(target))return false;
    border.put("lerp_target",size);return true;
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
