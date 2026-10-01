package org.worldgit.core.normalize;

import java.io.IOException;
import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.config.IgnoreRules;

/** world-meta 的 field selector；設定檔由 service 加入，不受 NBT 欄位規則影響。 */
public final class MetadataNormalizer {
  private MetadataNormalizer() {}

  public static SortedMap<String, byte[]> normalize(Map<String, byte[]> metadata, IgnoreRules rules)
      throws IOException {
    var result = new TreeMap<String, byte[]>();
    for (var entry : metadata.entrySet()) {
      String type = type(entry.getKey());
      if (type == null) {
        result.put(entry.getKey(), entry.getValue().clone());
        continue;
      }
      var data = Nbt.read(entry.getValue());
      data.keySet().removeIf(field -> rules.ignoredField(type, field, false));
      if (!data.isEmpty()) result.put(entry.getKey(), Nbt.write(data));
    }
    return result;
  }

  private static String type(String name) {
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
