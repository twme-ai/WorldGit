package org.worldgit.core.version;

import java.io.*;
import java.util.*;
import org.worldgit.core.anvil.Nbt;

/** 特殊 codec 的反向轉換；ID／一般欄位及元件對照由 YAML 決定。 */
public final class ReverseNbt {
  private final ReverseRules rules;
  private final ConversionReport.Collector loss;
  private final int from, to;

  public ReverseNbt(ReverseRules rules, ConversionReport.Collector loss, int from, int to) {
    this.rules = rules;
    this.loss = loss;
    this.from = from;
    this.to = to;
  }

  boolean known(String kind, String id) {
    return rules.known(kind, id);
  }

  Nbt.Compound worldGeneration(Nbt.Compound settings) throws IOException {
    return (Nbt.Compound) generation(settings, "world-gen", 0);
  }
  private Object generation(Object value,String path,int depth) throws IOException {
    if(depth>64)throw new IOException("世界生成設定遞迴超過限制");
    if(value instanceof Nbt.Compound c){
      var out=new Nbt.Compound();
      for(var e:c.entrySet()) {
        if(e.getValue() instanceof String id && e.getKey().equals("biome"))out.put(e.getKey(),rules.id("biome",id,path,loss));
        else if(e.getValue() instanceof String id && e.getKey().equals("block"))out.put(e.getKey(),rules.id("block",id,path,loss));
        else out.put(e.getKey(),generation(e.getValue(),path+"/"+e.getKey(),depth+1));
      }
      return out;
    }
    if(value instanceof Nbt.ListTag l){var list=new ArrayList<Object>();for(Object x:l.values())list.add(generation(x,path,depth+1));return new Nbt.ListTag(l.type(),list);}
    return Nbt.copy(value);
  }

  public Nbt.Compound convert(Nbt.Compound input, String kind, String path) throws IOException {
    var n = Nbt.copy(input);
    String original = n.string("id");
    if (kind.equals("entity") && to <= 3955) {
      String id = n.string("id");
      for (String wood :
          List.of(
              "oak",
              "spruce",
              "birch",
              "jungle",
              "acacia",
              "dark_oak",
              "mangrove",
              "cherry",
              "bamboo"))
        if (id.equals("minecraft:" + wood + "_boat")
            || id.equals("minecraft:" + wood + "_chest_boat")
            || wood.equals("bamboo")
                && (id.equals("minecraft:bamboo_raft")
                    || id.equals("minecraft:bamboo_chest_raft"))) {
          n.put("id", id.contains("chest") ? "minecraft:chest_boat" : "minecraft:boat");
          n.put("Type", wood);
          break;
        }
    }
    if ((kind.equals("entity") || kind.equals("player")) && to <= 4189) {
      boolean modernSaddle = n.compound("equipment").get("saddle") instanceof Nbt.Compound saddle
          && !saddle.isEmpty();
      if (n.string("id").equals("minecraft:splash_potion")
          || n.string("id").equals("minecraft:lingering_potion")) n.put("id", "minecraft:potion");
      if (n.get("equipment") instanceof Nbt.Compound equipment) {
        n.put(
            "HandItems",
            new Nbt.ListTag(
                10, List.of(equipment.compound("mainhand"), equipment.compound("offhand"))));
        n.put(
            "ArmorItems",
            new Nbt.ListTag(
                10,
                List.of(
                    equipment.compound("feet"),
                    equipment.compound("legs"),
                    equipment.compound("chest"),
                    equipment.compound("head"))));
        if (equipment.containsKey("body"))
          n.put(to <= 3700 ? "ArmorItem" : "body_armor_item", equipment.get("body"));
        if (equipment.containsKey("saddle")) {
          var saddle = equipment.compound("saddle");
          if (Set.of("minecraft:pig", "minecraft:strider").contains(n.string("id"))) {
            n.put("Saddle", (byte) (saddle.isEmpty() ? 0 : 1));
            if (!saddle.isEmpty() && (!saddle.string("id").equals("minecraft:saddle")
                || !saddle.compound("components").isEmpty()))
              loss.add("equipment", "saddle item data", "Saddle=true", path);
          } else n.put("SaddleItem", saddle);
        }
        for (String slot : equipment.keySet())
          if (!Set.of("mainhand", "offhand", "feet", "legs", "chest", "head", "body", "saddle").contains(slot))
            loss.add("equipment-slot", slot, "skip", path);
        n.remove("equipment");
      }
      if (n.get("drop_chances") instanceof Nbt.Compound c) {
        n.put(
            "HandDropChances",
            new Nbt.ListTag(5, List.of(number(c, "mainhand", .085f), number(c, "offhand", .085f))));
        n.put(
            "ArmorDropChances",
            new Nbt.ListTag(
                5,
                List.of(
                    number(c, "feet", .085f),
                    number(c, "legs", .085f),
                    number(c, "chest", .085f),
                    number(c, "head", .085f))));
        if (c.containsKey("body")) n.put("body_armor_drop_chance", number(c, "body", .085f));
        if (c.containsKey("saddle") && !modernSaddle)
          loss.add("equipment-drop-chance", "saddle=" + number(c, "saddle", .085f), "skip", path);
        for (String slot : c.keySet())
          if (!Set.of("mainhand", "offhand", "feet", "legs", "chest", "head", "body", "saddle").contains(slot))
            loss.add("equipment-drop-chance", slot, "skip", path);
        n.remove("drop_chances");
      }
      if (modernSaddle) {
        float chance = number(input.compound("drop_chances"), "saddle", .085f);
        // Legacy saddled entities always drop their saddle. Native 2.0 is the
        // guaranteed/preserved item convention; every chance >=1 remains certain.
        if (!(chance >= 1f))
          loss.add("equipment-drop-chance", "saddle=" + chance, "1.0", path);
      }
    }
    if (!original.isEmpty() && !kind.equals("player")) {
      String id = rules.id(kind, n.string("id"), path, loss);
      if (id.equals("skip")) return null;
      n.put("id", id);
    }
    // 1.21.5 omits empty equipment and default drop chances. The older Mob and
    // ArmorStand serializers always write their fixed-size arrays.
    if (kind.equals("entity") && rules.crosses(4189)
        && VersionYaml.resource("vanilla-health.yml").containsKey(n.string("id"))) {
      n.putIfAbsent("HandItems", emptyItems(2));
      n.putIfAbsent("ArmorItems", emptyItems(4));
      if (!n.string("id").equals("minecraft:armor_stand")) {
        n.putIfAbsent("HandDropChances", new Nbt.ListTag(5, List.of(.085f, .085f)));
        n.putIfAbsent("ArmorDropChances", new Nbt.ListTag(5, List.of(.085f, .085f, .085f, .085f)));
        if (n.containsKey("body_armor_item")) n.putIfAbsent("body_armor_drop_chance", .085f);
      }
    }
    rules.fields(kind, n);
    rules.legacyFields(kind, n, path, loss);
    if (kind.equals("block-entity") && to <= 3700 && n.get("components") instanceof Nbt.Compound components) {
      for (var component : components.entrySet())
        if (component.getKey().equals("minecraft:custom_name"))
          put(n, "CustomName", component.getValue(), path);
        else loss.add("block-entity-component", component.getKey(), "skip", path);
      n.remove("components");
    }
    if(kind.equals("entity")) {
      String entity=n.string("id").replaceFirst("^minecraft:","");
      for(String field:List.of("variant","sound_variant"))if(n.get(field) instanceof String id) {
        String type=entity+"_"+field;
        if(Set.of("cat","frog","wolf","painting","cow","pig","chicken","zombie_nautilus").contains(entity)) {
          String fallback=field.equals("sound_variant")?"minecraft:classic":entity.equals("wolf")?"minecraft:pale":entity.equals("painting")?"minecraft:kebab":entity.equals("cat")?"minecraft:tabby":"minecraft:temperate";
          n.put(field,rules.variant(type,id,fallback,path,loss));
          if(!rules.hasVariant(type)||entity.equals("wolf")&&to<=3700)n.remove(field);
        }
      }
    }
    if (kind.equals("player")) {
      rules.fields("entity", n);
      rules.legacyFields("entity", n, path, loss);
    }
    if (to <= 3465 && n.get("ActiveEffects") instanceof Nbt.ListTag effects)
      n.put("ActiveEffects", LegacyItemComponents.effects(effects, rules, loss, path));
    if (kind.equals("block-entity") && to <= 4903
        && Set.of("minecraft:furnace", "minecraft:smoker", "minecraft:blast_furnace",
            "minecraft:brewing_stand").contains(n.string("id"))) {
      if (n.containsKey("speed_multiplier")) {
        if (n.get("speed_multiplier") instanceof Number x && x.doubleValue() != 1)
          loss.add("field", "furnace speed_multiplier=" + x, "1", path);
        n.remove("speed_multiplier");
      }
      if (to <= 3955 && n.remove("lit_total_time") instanceof Number duration
          && duration.intValue() != 0)
        loss.add("block-entity-field", "lit_total_time=" + duration, "recalculate from fuel", path);
    }
    if(kind.equals("block-entity") && to <= 3955 && n.containsKey("lock")) {
      Object value=n.remove("lock");
      Object name=value instanceof Nbt.Compound predicate?predicate.compound("components").get("minecraft:custom_name"):value;
      if(value instanceof Nbt.Compound p && p.size()==1 && p.compound("components").size()==1 && name instanceof Nbt.Compound text && text.size()==1 && text.get("text") instanceof String s)n.put("Lock",s);
      else if(value instanceof Nbt.Compound p && p.size()==1 && p.compound("components").size()==1 && name instanceof String s && from>4189)n.put("Lock",s);
      else if(name instanceof String s && value instanceof String)n.put("Lock",s);
      else loss.add("block-entity-field","lock predicate","skip",path);
    }
    if (to <= 4189 && kind.equals("entity")) {
      for (var field :
          Map.of(
                  "block_pos",
                  List.of("TileX", "TileY", "TileZ"),
                  "anchor_pos",
                  List.of("AX", "AY", "AZ"),
                  "home_pos",
                  List.of("HomePosX", "HomePosY", "HomePosZ"),
                  "bound_pos",
                  List.of("BoundX", "BoundY", "BoundZ"))
              .entrySet())
        if (n.get(field.getKey()) instanceof int[] p && p.length == 3) {
          for (int i = 0; i < 3; i++) n.put(field.getValue().get(i), p[i]);
          n.remove(field.getKey());
        }
      if (n.string("id").contains("minecart") && n.containsKey("DisplayState"))
        n.put("CustomDisplayTile", (byte) 1);
      if (n.get("equipment") instanceof Nbt.Compound)
        throw new IOException("equipment 反向 codec 未完成");
      if (n.get("potion_duration_scale") instanceof Number scale && scale.doubleValue() != 1) {
        loss.add("field", "potion_duration_scale=" + scale, "1", path);
        n.remove("potion_duration_scale");
      }
      if (n.string("id").equals("minecraft:area_effect_cloud") && n.integer("Duration", 0) == -1) {
        loss.add(
            "field",
            "area_effect_cloud infinite duration",
            String.valueOf(Integer.MAX_VALUE),
            path);
        n.put("Duration", Integer.MAX_VALUE);
      }
      if (n.get("fall_distance") instanceof Number distance)
        n.put("FallDistance", distance.floatValue());
      n.remove("fall_distance");
      if (n.get("sleeping_pos") instanceof int[] pos && pos.length == 3) {
        n.put("SleepingX", pos[0]);
        n.put("SleepingY", pos[1]);
        n.put("SleepingZ", pos[2]);
        n.remove("sleeping_pos");
      }
      if (n.get("data") instanceof Nbt.Compound custom
          && !custom.isEmpty()
          && !n.string("id").equals("minecraft:marker")) {
        loss.add("field", "entity custom_data", "skip", path);
        n.remove("data");
      }
    }
    if (to <= 4189 && kind.equals("player")) {
      var inventory = new ArrayList<Object>(n.list("Inventory").values());
      var slots = Map.of("ArmorItems", List.of(100, 101, 102, 103), "HandItems", List.of(0, 150));
      for (var slot : slots.entrySet()) {
        var items = n.list(slot.getKey()).values();
        for (int i = 0; i < items.size(); i++) {
          var item = (Nbt.Compound) items.get(i);
          if (item.isEmpty() || item.string("id").equals("minecraft:air")) continue;
          int index = slot.getValue().get(i);
          if (slot.getKey().equals("HandItems") && i == 0) index = n.integer("SelectedItemSlot", 0);
          int finalIndex = index;
          inventory.removeIf(
              x ->
                  ((Nbt.Compound) x).integer("Slot", 0) == finalIndex
                      || (((Nbt.Compound) x).integer("Slot", 0) & 255) == finalIndex);
          item.put("Slot", (byte) index);
          inventory.add(item);
        }
        n.remove(slot.getKey());
      }
      n.put("Inventory", new Nbt.ListTag(10, inventory));
      if (n.get("respawn") instanceof Nbt.Compound respawn) {
        if (respawn.get("pos") instanceof int[] p && p.length == 3) {
          n.put("SpawnX", p[0]);
          n.put("SpawnY", p[1]);
          n.put("SpawnZ", p[2]);
        }
        n.put("SpawnAngle", respawn.getOrDefault("angle", 0f));
        n.put("SpawnDimension", respawn.getOrDefault("dimension", "minecraft:overworld"));
        n.put("SpawnForced", respawn.getOrDefault("forced", (byte) 0));
        n.remove("respawn");
      }
      if (n.get("entered_nether_pos") instanceof Nbt.ListTag list && list.values().size() == 3) {
        n.put(
            "enteredNetherPosition",
            new Nbt.Compound()
                .with("x", list.values().get(0))
                .with("y", list.values().get(1))
                .with("z", list.values().get(2)));
        n.remove("entered_nether_pos");
      }
      for (String field : List.of("ArmorItem", "body_armor_item", "SaddleItem"))
        if (n.containsKey(field)) {
          loss.add("player-equipment", field, "skip", path);
          n.remove(field);
        }
    }
    if (kind.equals("entity")) VanillaEntityDefaults.materialize(n, to);
    if (to <= 4189) {
      for (String textKey : List.of("text", "CustomName"))
        if (n.containsKey(textKey) && (from > 4189 || !(n.get(textKey) instanceof String)))
          n.put(textKey, json(n.get(textKey)));
      for (String side : List.of("front_text", "back_text"))
        if (n.get(side) instanceof Nbt.Compound text) {
          for (String key : List.of("messages", "filtered_messages"))
            if (text.get(key) instanceof Nbt.ListTag l && (from > 4189 || l.type() != 8))
              text.put(
                  key, new Nbt.ListTag(8, l.values().stream().map(x -> (Object) json(x)).toList()));
        }
    }
    if (to <= 4189 && n.containsKey("CustomName") && !(n.get("CustomName") instanceof String))
      n.put("CustomName", json(n.get("CustomName")));
    String attributesKey = to <= 3839 ? "Attributes" : "attributes";
    if (to <= 3955 && n.get(attributesKey) instanceof Nbt.ListTag a) {
      var values = new ArrayList<Object>();
      for (Object value : a.values()) {
        var c = (Nbt.Compound) value;
        String id = rules.attribute(c.string("id").isEmpty() ? c.string("Name") : c.string("id"));
        if (!rules.known("attributes", id)) {
          loss.add("attribute", id, "skip", path);
          continue;
        }
        if (to <= 3839) {
          c.put("Name", id);
          if (c.containsKey("base")) c.put("Base", c.remove("base"));
          c.remove("id");
        } else {
          c.put("id", id);
          if (c.containsKey("Base")) c.put("base", c.remove("Base"));
          c.remove("Name");
        }
        values.add(c);
      }
      n.put(attributesKey, new Nbt.ListTag(10, values));
    }
    // 陶罐的新版裝飾是 item stack；舊版只接受四個 id。
    if (to <= 4903
        && kind.equals("block-entity")
        && n.string("id").equals("minecraft:decorated_pot"))
      if (n.get("sherds") instanceof Nbt.Compound decorations)
        n.put("sherds", decorations(decorations, path));
    if (to <= 4903
        && kind.equals("block-entity")
        && n.string("id").equals("minecraft:decorated_pot"))
      for (String field : List.of("sherds", "decorations"))
        if (n.get(field) instanceof Nbt.ListTag l && l.type() == 10) {
          var ids = new ArrayList<Object>();
          for (Object x : l.values()) {
            var item = (Nbt.Compound) x;
            String id = rules.id("item", item.string("id"), path + "/" + field, loss);
            ids.add(id.equals("skip") ? "minecraft:brick" : id);
            if (item.containsKey("components"))
              loss.add("field", "pot decoration components", "skip", path);
          }
          n.put(field, new Nbt.ListTag(8, ids));
        }
    return (Nbt.Compound) walk(n, path, 0);
  }

  private float number(Nbt.Compound n, String key, float fallback) {
    return n.get(key) instanceof Number x ? x.floatValue() : fallback;
  }

  private static Nbt.ListTag emptyItems(int count) {
    var items = new ArrayList<Object>();
    for (int i = 0; i < count; i++) items.add(new Nbt.Compound());
    return new Nbt.ListTag(10, items);
  }

  private String attribute(String id) {
    return id.startsWith("minecraft:") && !id.contains(".")
        ? "minecraft:generic." + id.substring(10)
        : id;
  }

  private Object walk(Object value, String path, int depth) throws IOException {
    if (depth > 64) throw new IOException("反向 NBT 遞迴超過限制");
    if (value instanceof Nbt.Compound c) {
      if (depth > 0
          && !c.string("id").isEmpty()
          && (c.containsKey("count")
              || c.containsKey("Count")
              || c.containsKey("components")
                  && c.keySet().stream()
                      .allMatch(
                          k -> Set.of("id", "count", "Count", "Slot", "components").contains(k))))
        return item(c, path, depth);
      var out = new Nbt.Compound();
      for (var e : c.entrySet()) {
        if (Set.of("BlockState", "block_state", "inBlockState", "carriedBlockState", "DisplayState")
                .contains(e.getKey())
            && (e.getValue() instanceof String || e.getValue() instanceof Nbt.Compound)) {
          var state = BlockStateNbt.read(e.getValue(), from);
          out.put(
              e.getKey(),
              BlockStateNbt.write(rules.block(state, path + "/" + e.getKey(), loss), to));
        } else if (e.getKey().equals("Passengers")
            && e.getValue() instanceof Nbt.ListTag passengers) {
          var list = new ArrayList<Object>();
          for (Object p : passengers.values()) {
            var converted = convert((Nbt.Compound) p, "entity", path + "/Passengers");
            if (converted != null) list.add(converted);
          }
          out.put(e.getKey(), new Nbt.ListTag(10, list));
        } else out.put(e.getKey(), walk(e.getValue(), path + "/" + e.getKey(), depth + 1));
      }
      return out;
    }
    if (value instanceof Nbt.ListTag l) {
      var out = new ArrayList<Object>();
      for (int i = 0; i < l.values().size(); i++)
        out.add(walk(l.values().get(i), path + "/" + i, depth + 1));
      return new Nbt.ListTag(l.type(), out);
    }
    return Nbt.copy(value);
  }

  private Nbt.Compound item(Nbt.Compound input, String path, int depth) throws IOException {
    var n = Nbt.copy(input);
    String id = rules.id("item", n.string("id"), path, loss);
    if (id.equals("skip")) id = "minecraft:air";
    n.put("id", id);
    int count = n.integer("count", n.integer("Count", 1));
    var components = n.compound("components");
    if (rules.crosses(4903) && id.endsWith("_sign")) {
      var be = Nbt.copy(components.compound("minecraft:block_entity_data"));
      boolean changed = false;
      for (var mapping : rules.signComponents().entrySet())
        if (components.containsKey(mapping.getKey())) {
          Object value = components.remove(mapping.getKey());
          put(be, mapping.getValue(), mapping.getValue().equals("is_waxed") ? (byte) 1 : value, path);
          changed = true;
        }
      if (changed) {
        be.putIfAbsent("id", id.endsWith("_hanging_sign") ? "minecraft:hanging_sign" : "minecraft:sign");
        be.putIfAbsent("allow_op_features", (byte) 0);
        components.put("minecraft:block_entity_data", be);
      }
    }
    if (to >= 3839) {
      var out = new Nbt.Compound();
      for (var e : components.entrySet()) {
        if (!rules.known("component", e.getKey().replaceFirst("^!", ""))) {
          loss.add("component", e.getKey(), "skip", path);
          continue;
        }
        Object v = Nbt.copy(e.getValue());
        String key = e.getKey();
        if (Set.of("minecraft:enchantments", "minecraft:stored_enchantments").contains(key)
            && v instanceof Nbt.Compound enchantments) {
          var levels =
              enchantments.containsKey("levels") ? enchantments.compound("levels") : enchantments;
          for (String enchantment : new ArrayList<>(levels.keySet()))
            if (!rules.known("enchantments", enchantment)) {
              loss.add("enchantment", enchantment, "skip", path);
              levels.remove(enchantment);
            }
          if (enchantments.containsKey("levels")) enchantments.put("levels", levels);
        }
        if (to <= 4903 && key.equals("minecraft:pot_decorations") && v instanceof Nbt.Compound d)
          v = decorations(d, path);
        if (to <= 4189) {
          if(key.equals("minecraft:attribute_modifiers")
              && (v instanceof Nbt.ListTag || v instanceof Nbt.Compound)) {
            var wrapper = v instanceof Nbt.Compound c ? c : new Nbt.Compound();
            var list = v instanceof Nbt.ListTag l ? l : wrapper.list("modifiers");
            var modifiers=new ArrayList<Object>();
            for(Object value:list.values()){
              var modifier=Nbt.copy((Nbt.Compound)value);
              String attribute=rules.attribute(modifier.string("type"));
              if(!rules.known("attributes",attribute)){loss.add("attribute",modifier.string("type"),"skip",path);continue;}
              modifier.put("type",attribute);
              if(to<=3839){String modifierId=modifier.string("id");UUID uuid=UUID.nameUUIDFromBytes(modifierId.getBytes(java.nio.charset.StandardCharsets.UTF_8));modifier.put("uuid",new int[]{(int)(uuid.getMostSignificantBits()>>32),(int)uuid.getMostSignificantBits(),(int)(uuid.getLeastSignificantBits()>>32),(int)uuid.getLeastSignificantBits()});modifier.put("name",modifierId);modifier.remove("id");}
              if(modifier.containsKey("display")){loss.add("component-field","attribute_modifiers/display","skip",path);modifier.remove("display");}
              modifiers.add(modifier);
            }
            v=wrapper.with("modifiers",new Nbt.ListTag(10,modifiers));
          }
          if (Set.of("minecraft:custom_name", "minecraft:item_name").contains(key)
              && (from > 4189 || !(v instanceof String))) v = json(v);
          if (key.equals("minecraft:lore") && v instanceof Nbt.ListTag l)
            v =
                new Nbt.ListTag(
                    8, l.values().stream()
                        .map(x -> from <= 4189 && x instanceof String ? x : (Object) json(x))
                        .toList());
          if (from > 4189 && key.equals("minecraft:written_book_content") && v instanceof Nbt.Compound book)
            for (Object page : book.list("pages").values())
              if (page instanceof Nbt.Compound filtered)
                for (String field : List.of("raw", "filtered"))
                  if (filtered.containsKey(field)) filtered.put(field, json(filtered.get(field)));
          if (Set.of("minecraft:enchantments", "minecraft:stored_enchantments").contains(key)
              && v instanceof Nbt.Compound c
              && !c.containsKey("levels")) v = new Nbt.Compound().with("levels", c);
          if (key.equals("minecraft:dyed_color") && v instanceof Number)
            v = new Nbt.Compound().with("rgb", v);
          if (to <= 3955 && key.equals("minecraft:custom_model_data")
              && v instanceof Nbt.Compound c) {
            Integer model = legacyModelData(c, path);
            if (model == null) continue;
            v = model;
          }
        }
        Object converted = key.equals("minecraft:custom_data") ? v
            : key.equals("minecraft:block_entity_data") && v instanceof Nbt.Compound data
                ? convert(data, "block-entity", path + "/" + key)
                : walk(v, path + "/" + key, depth + 1);
        if (converted != null) out.put(key, converted);
      }
      if (out.isEmpty()) n.remove("components");
      else n.put("components", out);
      n.put("count", count);
      n.remove("Count");
      return n;
    }
    var tag = Nbt.copy(components.compound("minecraft:custom_data"));
    if (n.containsKey("tag")) tag = Nbt.copy(n.compound("tag"));
    for (var e : components.entrySet()) {
      String key = e.getKey();
      Object v = e.getValue();
      String mapped = rules.components().get(key);
      if (key.equals("minecraft:custom_data")) continue;
      if (mapped != null) {
        if (key.equals("minecraft:custom_model_data") && v instanceof Nbt.Compound c) {
          Integer model = legacyModelData(c, path);
          if (model == null) continue;
          v = model;
        }
        Object converted =
            key.equals("minecraft:block_entity_data") && v instanceof Nbt.Compound c
                ? convert(c, "block-entity", path + "/" + key)
                : walk(v, path + "/" + key, depth + 1);
        if (converted != null) put(tag, mapped, converted, path);
      } else
        switch (key) {
          case "minecraft:custom_name" ->
              putDisplay(tag, "Name", from <= 4189 && v instanceof String ? v : json(v), path);
          case "minecraft:lore" -> {
            var l = (Nbt.ListTag) v;
            putDisplay(
                tag,
                "Lore",
                new Nbt.ListTag(
                    8,
                    l.values().stream()
                        .map(x -> from <= 4189 && x instanceof String ? (Object) x : json(x))
                        .toList()),
                path);
          }
          case "minecraft:dyed_color" ->
              putDisplay(tag, "color", v instanceof Nbt.Compound c ? c.get("rgb") : v, path);
          case "minecraft:unbreakable" -> put(tag, "Unbreakable", (byte) 1, path);
          case "minecraft:enchantments", "minecraft:stored_enchantments" -> {
            var c = (Nbt.Compound) v;
            var levels = c.containsKey("levels") ? c.compound("levels") : c;
            var list = new ArrayList<Object>();
            for (var en : levels.entrySet()) {
              if (en.getValue() instanceof Number level) {
                if (rules.known("enchantments", en.getKey()))
                  list.add(
                      new Nbt.Compound()
                          .with("id", en.getKey())
                          .with("lvl", (short) level.intValue()));
                else loss.add("enchantment", en.getKey(), "skip", path);
              }
            }
            put(
                tag,
                key.equals("minecraft:enchantments") ? "Enchantments" : "StoredEnchantments",
                new Nbt.ListTag(10, list),
                path);
          }
          case "minecraft:container" -> {
            var list = new ArrayList<Object>();
            for (Object value : ((Nbt.ListTag) v).values()) {
              var slot = (Nbt.Compound) value;
              var converted = item(slot.compound("item"), path + "/container", depth + 1);
              converted.put("Slot", (byte) slot.integer("slot", 0));
              list.add(converted);
            }
            var be = tag.compound("BlockEntityTag");
            be.put("Items", new Nbt.ListTag(10, list));
            tag.put("BlockEntityTag", be);
          }
          case "minecraft:potion_contents" -> {
            var c = (Nbt.Compound) v;
            if (c.containsKey("potion")) put(tag, "Potion", c.get("potion"), path);
            if (c.containsKey("custom_color"))
              put(tag, "CustomPotionColor", c.get("custom_color"), path);
            if (c.containsKey("custom_effects"))
              put(
                  tag,
                  "CustomPotionEffects",
                  to <= 3465
                      ? LegacyItemComponents.effects(c.list("custom_effects"), rules, loss, path)
                      : c.get("custom_effects"),
                  path);
          }
          case "minecraft:hide_additional_tooltip", "minecraft:hide_tooltip" ->
              put(tag, "HideFlags", key.equals("minecraft:hide_tooltip") ? 127 : 32, path);
          default -> {
            if (!LegacyItemComponents.convert(key, v, tag, rules, loss, path, from, to))
              loss.add("component", key, "skip", path);
          }
        }
    }
    // DFU removes the zero damage component as a default. Legacy ItemStack saves
    // Damage:0 even for an undamaged tool, so restore it before capture or apply.
    if (rules.known("damageable-items", id)) tag.putIfAbsent("Damage", 0);
    if (count > 127) {
      loss.add("item-count", id + "=" + count, "127", path);
      count = 127;
    }
    n.put("Count", (byte) count);
    n.remove("count");
    n.remove("components");
    if (!tag.isEmpty()) n.put("tag", tag);
    return n;
  }

  private void put(Nbt.Compound n, String key, Object v, String path) throws IOException {
    if (n.containsKey(key)
        && !Arrays.equals(
            Nbt.write(new Nbt.Compound().with("v", n.get(key))),
            Nbt.write(new Nbt.Compound().with("v", v))))
      loss.add("custom-data-collision", key, "component value", path);
    n.put(key, v);
  }

  private Integer legacyModelData(Nbt.Compound value, String path) throws IOException {
    var floats = value.list("floats").values();
    if (floats.size() == 1 && floats.getFirst() instanceof Number number
        && Double.isFinite(number.doubleValue())
        && number.doubleValue() == number.intValue()
        && value.keySet().stream()
            .allMatch(k -> k.equals("floats") || value.list(k).values().isEmpty()))
      return number.intValue();
    loss.add("component", "minecraft:custom_model_data", "skip", path);
    return null;
  }

  private Nbt.ListTag decorations(Nbt.Compound n, String path) throws IOException {
    var ids = new ArrayList<Object>();
    for (String side : List.of("back", "left", "right", "front")) {
      var item = n.compound(side);
      String id = item.string("id");
      if (id.isEmpty()) id = "minecraft:brick";
      id = rules.id("item", id, path + "/sherds/" + side, loss);
      ids.add(id.equals("skip") ? "minecraft:brick" : id);
      if (!item.compound("components").isEmpty())
        loss.add("field", "pot decoration components", "skip", path + "/" + side);
    }
    return new Nbt.ListTag(8, ids);
  }

  private void putDisplay(Nbt.Compound n, String key, Object v, String path) throws IOException {
    var d = n.compound("display");
    put(d, key, v, path);
    n.put("display", d);
  }

  public static String json(Object value) {
    if (value instanceof Nbt.Compound c) {
      if (c.size() == 1 && c.containsKey("")) return json(c.get(""));
      var out = Nbt.copy(c);
      if (out.get("click_event") instanceof Nbt.Compound event) {
        out.remove("click_event");
        String field =
            switch (event.string("action")) {
              case "open_url" -> "url";
              case "run_command", "suggest_command" -> "command";
              case "change_page" -> "page";
              default -> "value";
            };
        if (event.containsKey(field)) event.put("value", String.valueOf(event.remove(field)));
        out.put("clickEvent", event);
      }
      if (out.get("hover_event") instanceof Nbt.Compound event) {
        out.remove("hover_event");
        if (event.string("action").equals("show_text")) {
          event.put("contents", event.remove("value"));
        } else if (event.string("action").equals("show_entity")) {
          var contents = new Nbt.Compound().with("type", event.remove("id"));
          Object uuid = event.remove("uuid");
          if (uuid instanceof int[] p && p.length == 4)
            uuid =
                new UUID(
                        ((long) p[0] << 32) | (p[1] & 0xffffffffL),
                        ((long) p[2] << 32) | (p[3] & 0xffffffffL))
                    .toString();
          contents.put("id", uuid);
          if (event.containsKey("name")) contents.put("name", event.remove("name"));
          event.put("contents", contents);
        } else if (event.string("action").equals("show_item")) {
          var item = Nbt.copy(event);
          item.remove("action");
          event = new Nbt.Compound().with("action", "show_item").with("contents", item);
        }
        out.put("hoverEvent", event);
      }
      return "{"
          + out.entrySet().stream()
              .map(
                  e ->
                      quote(e.getKey())
                          + ":"
                          + (Set.of(
                                          "bold",
                                          "italic",
                                          "underlined",
                                          "strikethrough",
                                          "obfuscated",
                                          "interpret")
                                      .contains(e.getKey())
                                  && e.getValue() instanceof Number n
                              ? String.valueOf(n.intValue() != 0)
                              : json(e.getValue())))
              .collect(java.util.stream.Collectors.joining(","))
          + "}";
    }
    if (value instanceof Nbt.ListTag l)
      return "["
          + l.values().stream()
              .map(ReverseNbt::json)
              .collect(java.util.stream.Collectors.joining(","))
          + "]";
    if (value instanceof String s) return quote(s);
    if (value instanceof int[] a)
      return Arrays.stream(a)
          .mapToObj(String::valueOf)
          .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    return String.valueOf(value);
  }

  private static String quote(String s) {
    var b = new StringBuilder("\"");
    for (char c : s.toCharArray())
      switch (c) {
        case '\\' -> b.append("\\\\");
        case '"' -> b.append("\\\"");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 32) b.append(String.format("\\u%04x", (int) c));
          else b.append(c);
        }
      }
    return b.append('"').toString();
  }
}
