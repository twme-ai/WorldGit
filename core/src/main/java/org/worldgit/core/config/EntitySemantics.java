package org.worldgit.core.config;

import java.util.*;
import org.worldgit.core.anvil.Nbt;

/** 線上 adapter 可提供版本的 entity tags 與 persistence 判斷。 */
public interface EntitySemantics {
  boolean persistent(Nbt.Compound entity);

  boolean inTag(String tag, String entityType);

  Set<String> STATIC_TYPES =
      Set.of(
          "minecraft:armor_stand",
          "minecraft:item_frame",
          "minecraft:glow_item_frame",
          "minecraft:painting",
          "minecraft:block_display",
          "minecraft:item_display",
          "minecraft:text_display",
          "minecraft:interaction",
          "minecraft:leash_knot",
          "minecraft:marker");

  static boolean flag(Nbt.Compound entity, String field) {
    return entity.integer(field, 0) != 0;
  }

  EntitySemantics OFFLINE =
      new EntitySemantics() {
        @Override
        public boolean persistent(Nbt.Compound e) {
          String id = e.string("id");
          return flag(e, "PersistenceRequired")
              || flag(e, "NoAI")
              || flag(e, "Tame")
              || e.containsKey("CustomName")
              || e.containsKey("Owner")
              || e.containsKey("Leash")
              || e.containsKey("leash")
              || STATIC_TYPES.contains(id)
              || Set.of(
                      "minecraft:villager",
                      "minecraft:wandering_trader",
                      "minecraft:iron_golem",
                      "minecraft:snow_golem",
                      "minecraft:cow",
                      "minecraft:mooshroom",
                      "minecraft:sheep",
                      "minecraft:pig",
                      "minecraft:chicken",
                      "minecraft:horse",
                      "minecraft:donkey",
                      "minecraft:mule",
                      "minecraft:llama",
                      "minecraft:cat",
                      "minecraft:wolf",
                      "minecraft:parrot",
                      "minecraft:bee",
                      "minecraft:fox",
                      "minecraft:goat",
                      "minecraft:rabbit",
                      "minecraft:camel",
                      "minecraft:sniffer",
                      "minecraft:armadillo",
                      "minecraft:turtle",
                      "minecraft:axolotl",
                      "minecraft:frog",
                      "minecraft:allay")
                  .contains(id)
              || id.contains("boat")
              || id.contains("minecart");
        }

        @Override
        public boolean inTag(String tag, String type) {
          if (tag.equals("minecraft:arrows"))
            return Set.of("minecraft:arrow", "minecraft:spectral_arrow").contains(type);
          throw new IllegalArgumentException(
              "離線來源無法解析 entity tag #" + tag + "；請由版本 adapter 提供 EntitySemantics");
        }
      };
}
