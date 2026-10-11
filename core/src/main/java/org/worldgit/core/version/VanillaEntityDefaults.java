package org.worldgit.core.version;

import java.io.IOException;
import java.util.*;
import org.worldgit.core.anvil.Nbt;

/** 舊版載入活實體需要的預設值；只在實體化時補入，不屬於歷史內容。 */
public final class VanillaEntityDefaults {
  private VanillaEntityDefaults() {}

  public static void materialize(Nbt.Compound entity, int version) throws IOException {
    materialize(entity, version, Map.of());
  }

  public static void materialize(Nbt.Compound entity, int version,
      Map<UUID, Nbt.Compound> previous) throws IOException {
    if (version > 4189) return;
    if (!entity.containsKey("Health") && !previous.isEmpty()) {
      var old = previous.get(org.worldgit.core.normalize.EntityNormalizer.uuid(entity));
      if (old != null && old.string("id").equals(entity.string("id")) && old.containsKey("Health"))
        entity.put("Health", Nbt.copy(old.get("Health")));
    }
    if (!entity.containsKey("Health")) {
      Object health = VersionYaml.resource("vanilla-health.yml").get(entity.string("id"));
      if (health instanceof Number value) {
        float max = value.floatValue();
        for (String key : List.of("Attributes", "attributes"))
          for (Object attribute : entity.list(key).values()) {
            var a = (Nbt.Compound) attribute;
            if ((a.string("id") + a.string("Name")).endsWith("max_health"))
              max = a.get("base") instanceof Number b ? b.floatValue()
                  : a.get("Base") instanceof Number b ? b.floatValue() : max;
          }
        entity.put("Health", max);
      }
    }
    for (Object passenger : entity.list("Passengers").values())
      materialize((Nbt.Compound) passenger, version, previous);
  }
}
