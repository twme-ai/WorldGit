package org.worldgit.core.version;

import java.io.IOException;
import java.util.*;
import org.worldgit.core.anvil.Nbt;

/** 原版 saved-data codec 的結構反向轉換；一般欄位由 YAML 描述。 */
final class SavedDataReverse {
  private SavedDataReverse() {}

  static void convert(
      Nbt.Compound root, String type, int from, int to, ConversionReport.Collector loss)
      throws IOException {
    if (from <= 4189 || to > 4189) return;
    var data = root.compound("data");
    if (type.equals("SAVED_DATA_RAIDS")) {
      data.put("NextAvailableID", data.getOrDefault("next_id", 1));
      data.put("Tick", data.getOrDefault("tick", 0));
      var raids = new ArrayList<Object>();
      var names = VersionYaml.resource("saved-data.yml");
      var fields = (Map<?, ?>) names.get("raid");
      for (Object value : data.list("raids").values()) {
        var raid = Nbt.copy((Nbt.Compound) value);
        for (var field : fields.entrySet())
          if (raid.containsKey(field.getKey()))
            raid.put((String) field.getValue(), raid.remove(field.getKey()));
        if (raid.get("center") instanceof int[] p && p.length == 3) {
          raid.put("CX", p[0]);
          raid.put("CY", p[1]);
          raid.put("CZ", p[2]);
          raid.remove("center");
        }
        raids.add(raid);
      }
      data.put("Raids", new Nbt.ListTag(10, raids));
      data.remove("next_id");
      data.remove("tick");
      data.remove("raids");
    } else if (type.equals("SAVED_DATA_TICKETS")) {
      var forced = new ArrayList<Long>();
      for (Object value : data.list("tickets").values()) {
        var ticket = (Nbt.Compound) value;
        if (!ticket.string("type").equals("minecraft:forced")) {
          loss.add("chunk-ticket", ticket.string("type"), "skip", "saved-data/tickets");
          continue;
        }
        if (ticket.get("chunk_pos") instanceof int[] pos && pos.length == 2)
          forced.add((pos[0] & 0xffffffffL) | ((long) pos[1] << 32));
        else throw new IOException("chunk ticket 座標無法反向轉換");
      }
      data.put("Forced", forced.stream().mapToLong(Long::longValue).toArray());
      data.remove("tickets");
    } else if (type.equals("SAVED_DATA_SCOREBOARD")) {
      for (String field : List.of("Objectives", "Teams"))
        for (Object value : data.list(field).values()) {
          var entry = (Nbt.Compound) value;
          for (String text : List.of("DisplayName", "MemberNamePrefix", "MemberNameSuffix"))
            if (entry.containsKey(text)) entry.put(text, ReverseNbt.json(entry.get(text)));
          if (to <= 3465)
            for (String newer : List.of("format", "display_auto_update"))
              if (entry.containsKey(newer)) {
                loss.add("scoreboard-field", newer, "skip", "saved-data/scoreboard");
                entry.remove(newer);
              }
        }
      for (Object value : data.list("PlayerScores").values()) {
        var score = (Nbt.Compound) value;
        if (score.containsKey("display"))
          score.put("display", ReverseNbt.json(score.get("display")));
        if (to <= 3465)
          for (String newer : List.of("display", "format"))
            if (score.containsKey(newer)) {
              loss.add("scoreboard-field", newer, "skip", "saved-data/scoreboard");
              score.remove(newer);
            }
      }
    } else if (type.equals("SAVED_DATA_RANDOM_SEQUENCES")) {
      if (to <= 3465) {
        if (!data.compound("sequences").isEmpty())
          loss.add("saved-data", "random_sequences", "skip", "saved-data/random_sequences");
      } else {
        data.putIfAbsent("include_sequence_id", (byte) 1);
        data.putIfAbsent("include_world_seed", (byte) 1);
      }
    }
    root.put("data", data);
  }
}
