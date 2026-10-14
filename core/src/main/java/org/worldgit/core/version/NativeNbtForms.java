package org.worldgit.core.version;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import org.worldgit.core.anvil.Nbt;

/** DFU remnants and native codec defaults, scoped to the verified vanilla schemas. */
public final class NativeNbtForms {
  private NativeNbtForms() {}

  private static final class Types {
    static final Set<?> BLOCK_ENTITIES;
    static final Set<String> LIVING;
    static {
      try {
        BLOCK_ENTITIES = Set.copyOf((List<?>) VersionYaml.resource("4440.yml").get("block-entities"));
        LIVING = Set.copyOf(VersionYaml.resource("vanilla-health.yml").keySet());
      } catch (IOException error) { throw new UncheckedIOException(error); }
    }
  }

  public static Nbt.Compound blockEntity(Nbt.Compound input, int version) {
    if (version < 4440 || input.containsKey("components")
        || !Types.BLOCK_ENTITIES.contains(input.string("id"))) return input;
    var result = Nbt.copy(input);
    result.put("components", new Nbt.Compound());
    return result;
  }

  public static Nbt.Compound entity(Nbt.Compound input, int version) {
    if (version < 4440) return input;
    var result = input;
    if (Types.LIVING.contains(input.string("id"))
        && (!input.containsKey("equipment") || input.get("equipment") instanceof Nbt.Compound)) {
      var equipment = input.compound("equipment");
      for (var entry : Map.of("HandItems", List.of("mainhand", "offhand"),
          "ArmorItems", List.of("feet", "legs", "chest", "head")).entrySet()) {
        if (input.get(entry.getKey()) instanceof Nbt.ListTag list && list.type() == 10
            && list.values().size() == entry.getValue().size()) {
          boolean redundant = true;
          for (int i = 0; i < list.values().size(); i++) {
            Object old = list.values().get(i);
            redundant &= old instanceof Nbt.Compound item
                && Arrays.equals(Nbt.write(item), Nbt.write(equipment.compound(entry.getValue().get(i))));
          }
          if (redundant) {
            if (result == input) result = Nbt.copy(input);
            result.remove(entry.getKey());
          }
        }
      }
      if (input.get("saddle") instanceof Nbt.Compound saddle
          && equipment.get("saddle") instanceof Nbt.Compound modern
          && Arrays.equals(Nbt.write(saddle), Nbt.write(modern))) {
        if (result == input) result = Nbt.copy(input);
        result.remove("saddle");
      }
    }
    // The native serializer writes PigVariants.DEFAULT even when DFU left it absent.
    if (input.string("id").equals("minecraft:pig")
        && !input.containsKey("variant")) {
      if (result == input) result = Nbt.copy(input);
      result.put("variant", "minecraft:temperate");
    }
    if (version >= 4790 && Set.of("minecraft:pig", "minecraft:cow", "minecraft:chicken").contains(input.string("id"))
        && !input.containsKey("sound_variant")) {
      if (result == input) result = Nbt.copy(input);
      result.put("sound_variant", "minecraft:classic");
    }
    if (input.get("Passengers") instanceof Nbt.ListTag passengers) {
      ArrayList<Object> changed = null;
      for (int i = 0; i < passengers.values().size(); i++) {
        var before = (Nbt.Compound) passengers.values().get(i);
        var after = entity(before, version);
        if (after != before) {
          if (changed == null) changed = new ArrayList<>(passengers.values());
          changed.set(i, after);
        }
      }
      if (changed != null) {
        if (result == input) result = Nbt.copy(input);
        result.put("Passengers", new Nbt.ListTag(passengers.type(), changed));
      }
    }
    return result;
  }

  /** ENTITY_CHUNK DFU can leave legacy item arrays beside the upgraded equipment.
   * Compare with the same ITEM_STACK DFU before discarding any redundant payload. */
  static Nbt.Compound dfuEntity(Nbt.Compound input,int from,int to,NbtUpgrader upgrader) throws IOException {
    if(from>=3839 || to<4440)return input;
    var result=input;
    if(Types.LIVING.contains(input.string("id")) && input.get("equipment") instanceof Nbt.Compound equipment)
      for(var entry:Map.of("HandItems",List.of("mainhand","offhand"),
          "ArmorItems",List.of("feet","legs","chest","head")).entrySet()) {
        if(!(input.get(entry.getKey()) instanceof Nbt.ListTag list) || list.type()!=10
            || list.values().size()!=entry.getValue().size())continue;
        boolean redundant=true;
        for(int i=0;i<list.values().size();i++) {
          if(!(list.values().get(i) instanceof Nbt.Compound item)){redundant=false;break;}
          var converted=item;
          if(item.containsKey("Count")) {
            if(!item.string("id").startsWith("minecraft:")){redundant=false;break;}
            converted=upgrader.upgrade("ITEM_STACK",Nbt.copy(item),from,to);
          }
          redundant &= Arrays.equals(Nbt.write(emptyItemPatch(converted)),
              Nbt.write(emptyItemPatch(equipment.compound(entry.getValue().get(i)))));
        }
        if(redundant) {
          if(result==input)result=Nbt.copy(input);
          result.remove(entry.getKey());
        }
      }
    if(input.get("Passengers") instanceof Nbt.ListTag passengers && passengers.type()==10) {
      var values=new ArrayList<Object>();boolean changed=false;
      for(Object value:passengers.values()) {
        var before=(Nbt.Compound)value;var after=dfuEntity(before,from,to,upgrader);
        values.add(after);changed |= before!=after;
      }
      if(changed) {
        if(result==input)result=Nbt.copy(input);
        result.put("Passengers",new Nbt.ListTag(10,values));
      }
    }
    return result;
  }

  private static Nbt.Compound emptyItemPatch(Nbt.Compound item) {
    if(!(item.get("components") instanceof Nbt.Compound patch) || !patch.isEmpty())return item;
    var copy=Nbt.copy(item);copy.remove("components");return copy;
  }
}
