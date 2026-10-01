package org.worldgit.core.normalize;

import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.config.*;
import org.worldgit.core.model.EntitySnapshot;

public final class EntityNormalizer {
  private EntityNormalizer() {}

  private static final Set<String> TRANSIENT =
      Set.of(
          "Motion",
          "FallDistance",
          "fall_distance",
          "OnGround",
          "Air",
          "Fire",
          "PortalCooldown",
          "HasTicked",
          "TicksFrozen",
          "HurtTime",
          "HurtByTimestamp",
          "DeathTime",
          "FallFlying",
          "current_impulse_context_reset_grace_time",
          "Brain",
          "Age",
          "InLove",
          "LoveCause",
          "InWaterTime",
          "DrownedConversionTime",
          "PickupDelay",
          "Time",
          "LastRestock",
          "RestocksToday",
          "LastGossipDecay",
          "Gossips",
          "FoodLevel",
          "sleeping_pos",
          "life",
          "inGround",
          "shake",
          "Fuse",
          "start_interpolation",
          "AngryAt",
          "AngerTime",
          "LeashDelay",
          "SleepingX",
          "SleepingY",
          "SleepingZ",
          "WasOnFire",
          "Health");

  public static boolean exact(Nbt.Compound e) {
    return EntitySemantics.flag(e, "NoAI") || EntitySemantics.STATIC_TYPES.contains(e.string("id"));
  }

  public static boolean platformField(String k) {
    return k.startsWith("Paper.")
        || k.startsWith("Bukkit.")
        || k.startsWith("Spigot.")
        || k.startsWith("WorldUUID")
        || k.startsWith("starlight.");
  }

  public static UUID uuid(Nbt.Compound e) {
    if (e.get("UUID") instanceof int[] a && a.length == 4)
      return new UUID(
          (long) a[0] << 32 | Integer.toUnsignedLong(a[1]),
          (long) a[2] << 32 | Integer.toUnsignedLong(a[3]));
    if (e.get("UUIDMost") instanceof Long a && e.get("UUIDLeast") instanceof Long b)
      return new UUID(a, b);
    throw new IllegalArgumentException("實體缺少 UUID：" + e.string("id"));
  }

  public static EntitySnapshot normalize(Nbt.Compound entity, IgnoreRules rules) {
    return new EntitySnapshot(uuid(entity), normalizeData(entity, rules, true));
  }

  private static Nbt.Compound normalizeData(Nbt.Compound e, IgnoreRules rules, boolean top) {
    String type = e.string("id");
    boolean exact = exact(e);
    var result = new Nbt.Compound();
    for (var entry : e.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      // 身分及頂層位置必須存在；其餘可由 !field 覆寫內建忽略表。
      boolean ignored =
          platformField(key)
              || TRANSIENT.contains(key)
              || key.equals("Rotation") && !exact
              || key.equals("Pos") && !top;
      if (!Set.of("id", "UUID", "UUIDMost", "UUIDLeast", "Pos").contains(key)
          && rules.ignoredField(type, key, ignored)) continue;
      if (key.equals("Pos") && !top) continue;
      if ((key.equals("attributes") || key.equals("Attributes"))
          && value instanceof Nbt.ListTag list) {
        var attrs = new ArrayList<Nbt.Compound>();
        for (Object o : list.values()) {
          var a = Nbt.copy((Nbt.Compound) o);
          String id = a.string("id");
          if (id.isEmpty()) id = a.string("Name");
          if ((id.equals("minecraft:movement_speed")
                  || id.equals("minecraft:generic.movement_speed"))
              && a.list("modifiers").values().isEmpty()
              && a.list("Modifiers").values().isEmpty()) continue;
          for (String modifiers : List.of("modifiers", "Modifiers"))
            if (a.get(modifiers) instanceof Nbt.ListTag ml) {
              var sorted = new ArrayList<>(ml.values());
              sorted.sort(
                  Comparator.comparing(
                      v -> java.util.HexFormat.of().formatHex(Nbt.write((Nbt.Compound) v))));
              a.put(modifiers, new Nbt.ListTag(10, sorted));
            }
          attrs.add(a);
        }
        attrs.sort(Comparator.comparing(a -> a.string("id") + a.string("Name")));
        if (!attrs.isEmpty()) result.put(key, new Nbt.ListTag(10, new ArrayList<>(attrs)));
        continue;
      }
      if (key.equals("Passengers") && value instanceof Nbt.ListTag list) {
        var passengers = new ArrayList<Object>();
        for (Object p : list.values())
          passengers.add(normalizeData((Nbt.Compound) p, rules, false));
        passengers.sort(Comparator.comparing(p -> uuid((Nbt.Compound) p).toString()));
        value = new Nbt.ListTag(10, passengers);
      } else if (Set.of("equipment", "ArmorItems", "HandItems").contains(key))
        value = stripDamage(value, type, key, rules);
      else value = Nbt.copy(value);
      if ((key.equals("Item") || key.equals("item"))
          && type.equals("minecraft:item")
          && value instanceof Nbt.Compound item) {
        item.remove("Count");
        item.remove("count");
      }
      result.put(key, value);
    }
    return result;
  }

  private static Object stripDamage(Object v, String type, String path, IgnoreRules rules) {
    if (v instanceof Nbt.Compound c) {
      var r = new Nbt.Compound();
      c.forEach(
          (k, x) -> {
            boolean damage = k.equals("minecraft:damage") || k.equals("Damage");
            if (!rules.ignoredField(type, path + "." + k, damage))
              r.put(k, stripDamage(x, type, path + "." + k, rules));
          });
      return r;
    }
    if (v instanceof Nbt.ListTag l)
      return new Nbt.ListTag(
          l.type(), l.values().stream().map(x -> stripDamage(x, type, path, rules)).toList());
    return Nbt.copy(v);
  }

  /** 相對 HEAD 的黏性錨點，不採逐次累積距離；NoAI/靜態實體精確比對。 */
  public static boolean stickyEqual(EntitySnapshot a, EntitySnapshot b, double tolerance) {
    if (Arrays.equals(a.bytes(), b.bytes())) return true;
    if (exact(a.data()) || exact(b.data()) || tolerance <= 0) return false;
    double[] pa = a.position(), pb = b.position();
    double distance = 0;
    for (int i = 0; i < 3; i++) distance += (pa[i] - pb[i]) * (pa[i] - pb[i]);
    if (!Double.isFinite(distance) || distance > tolerance * tolerance) return false;
    var ac = a.data();
    var bc = b.data();
    ac.remove("Pos");
    bc.remove("Pos");
    return Nbt.equal(ac, bc);
  }
}
