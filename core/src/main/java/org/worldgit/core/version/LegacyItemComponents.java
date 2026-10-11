package org.worldgit.core.version;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.worldgit.core.anvil.Nbt;

/** Legacy item tags whose shape differs from their data-component encoding. */
final class LegacyItemComponents {
  private LegacyItemComponents() {}

  static boolean convert(
      String key,
      Object value,
      Nbt.Compound tag,
      ReverseRules rules,
      ConversionReport.Collector loss,
      String path,
      int from,
      int to)
      throws java.io.IOException {
    switch (key) {
      case "minecraft:writable_book_content", "minecraft:written_book_content" -> {
        var book = (Nbt.Compound) value;
        var pages = new ArrayList<Object>();
        var filtered = new Nbt.Compound();
        int index = 0;
        for (Object page : book.list("pages").values()) {
          Object raw = page, filter = null;
          if (page instanceof Nbt.Compound p && p.containsKey("raw")) {
            raw = p.get("raw");
            filter = p.get("filtered");
          }
          boolean written = key.equals("minecraft:written_book_content");
          pages.add(written && from > 4189 ? ReverseNbt.json(raw) : String.valueOf(raw));
          if (filter != null)
            filtered.put(
                String.valueOf(index),
                written && from > 4189 ? ReverseNbt.json(filter) : String.valueOf(filter));
          index++;
        }
        tag.put("pages", new Nbt.ListTag(8, pages));
        if (!filtered.isEmpty()) tag.put("filtered_pages", filtered);
        for (String field : List.of("author", "generation", "resolved"))
          if (book.containsKey(field)) tag.put(field, book.get(field));
        if (book.get("title") instanceof Nbt.Compound title) {
          tag.put("title", title.getOrDefault("raw", ""));
          if (title.containsKey("filtered")) tag.put("filtered_title", title.get("filtered"));
        } else if (book.containsKey("title")) tag.put("title", book.get("title"));
      }
      case "minecraft:profile" -> {
        var profile = (Nbt.Compound) value;
        var old = new Nbt.Compound();
        if (profile.containsKey("name")) old.put("Name", profile.get("name"));
        if (profile.containsKey("id")) old.put("Id", profile.get("id"));
        var props = new Nbt.Compound();
        for (Object p : profile.list("properties").values()) {
          var property = (Nbt.Compound) p;
          var values = new ArrayList<>(props.list(property.string("name")).values());
          var v = new Nbt.Compound().with("Value", property.get("value"));
          if (property.containsKey("signature")) v.put("Signature", property.get("signature"));
          values.add(v);
          props.put(property.string("name"), new Nbt.ListTag(10, values));
        }
        if (!props.isEmpty()) old.put("Properties", props);
        tag.put("SkullOwner", old);
      }
      case "minecraft:firework_explosion" -> tag.put("Explosion", explosion((Nbt.Compound) value));
      case "minecraft:fireworks" -> {
        var c = (Nbt.Compound) value;
        var values =
            c.list("explosions").values().stream()
                .map(x -> (Object) explosion((Nbt.Compound) x))
                .toList();
        tag.put(
            "Fireworks",
            new Nbt.Compound()
                .with("Flight", (byte) c.integer("flight_duration", 1))
                .with("Explosions", new Nbt.ListTag(10, values)));
      }
      case "minecraft:attribute_modifiers" -> {
        var modifiers =
            value instanceof Nbt.ListTag list ? list : ((Nbt.Compound) value).list("modifiers");
        var values = new ArrayList<Object>();
        for (Object object : modifiers.values()) {
          var modifier = (Nbt.Compound) object;
          String type = rules.attribute(modifier.string("type"));
          if (!rules.known("attributes", type)) {
            loss.add("attribute", modifier.string("type"), "skip", path);
            continue;
          }
          String id = modifier.string("id");
          UUID uuid = UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
          Object oldUuid =
              modifier.getOrDefault(
                  "uuid",
                  new int[] {
                    (int) (uuid.getMostSignificantBits() >> 32),
                    (int) uuid.getMostSignificantBits(),
                    (int) (uuid.getLeastSignificantBits() >> 32),
                    (int) uuid.getLeastSignificantBits()
                  });
          int operation =
              List.of("add_value", "add_multiplied_base", "add_multiplied_total")
                  .indexOf(modifier.string("operation"));
          var old =
              new Nbt.Compound()
                  .with("AttributeName", type)
                  .with("Name", modifier.getOrDefault("name", id))
                  .with("UUID", oldUuid)
                  .with("Amount", modifier.getOrDefault("amount", 0d))
                  .with("Operation", Math.max(0, operation));
          String slot = modifier.string("slot");
          if (!slot.isEmpty() && !slot.equals("any")) {
            if (Set.of("mainhand", "offhand", "head", "chest", "legs", "feet").contains(slot))
              old.put("Slot", slot);
            else {
              loss.add("attribute-slot", slot, "skip", path);
              continue;
            }
          }
          values.add(old);
        }
        tag.put("AttributeModifiers", new Nbt.ListTag(10, values));
      }
      case "minecraft:base_color" -> {
        var be = tag.compound("BlockEntityTag");
        be.put("Base", color(String.valueOf(value)));
        tag.put("BlockEntityTag", be);
      }
      case "minecraft:banner_patterns" -> {
        var patterns = new ArrayList<Object>();
        var names = VersionYamlUnchecked.bannerNames();
        for (Object valuePattern : ((Nbt.ListTag) value).values()) {
          var pattern = (Nbt.Compound) valuePattern;
          String id = pattern.string("pattern"), old = names.get(id);
          if (old == null) {
            loss.add("banner-pattern", id, "skip", path);
            continue;
          }
          patterns.add(
              new Nbt.Compound()
                  .with("Pattern", old)
                  .with("Color", color(pattern.string("color"))));
        }
        var be = tag.compound("BlockEntityTag");
        be.put("Patterns", new Nbt.ListTag(10, patterns));
        tag.put("BlockEntityTag", be);
      }
      case "minecraft:pot_decorations" -> {
        var ids = new ArrayList<Object>();
        if (value instanceof Nbt.ListTag list) ids.addAll(list.values());
        else if (value instanceof Nbt.Compound c)
          for (String side : List.of("back", "left", "right", "front")) {
            var item = c.compound(side);
            String id = item.string("id");
            if (id.isEmpty()) id = "minecraft:brick";
            ids.add(id);
            if (!item.compound("components").isEmpty())
              loss.add("field", "pot decoration components", "skip", path);
          }
        var filtered = new ArrayList<Object>();
        for (Object id : ids) {
          String string = String.valueOf(id);
          String renamed = rules.rename("item", string);
          if (!rules.known("item", renamed)) {
            loss.add("item", string, "minecraft:brick", path);
            renamed = "minecraft:brick";
          }
          filtered.add(renamed);
        }
        var be = tag.compound("BlockEntityTag");
        be.put("sherds", new Nbt.ListTag(8, filtered));
        tag.put("BlockEntityTag", be);
      }
      default -> {
        return false;
      }
    }
    return true;
  }

  static Nbt.ListTag effects(
      Nbt.ListTag effects, ReverseRules rules, ConversionReport.Collector loss, String path)
      throws java.io.IOException {
    var out = new ArrayList<Object>();
    for (Object value : effects.values()) {
      var c = (Nbt.Compound) value;
      int id = rules.effectId(c.string("id"));
      if (id < 0) {
        loss.add("effect", c.string("id"), "skip", path);
        continue;
      }
      var old = new Nbt.Compound().with("Id", (byte) id);
      var fields =
          Map.of(
              "amplifier",
              "Amplifier",
              "duration",
              "Duration",
              "ambient",
              "Ambient",
              "show_particles",
              "ShowParticles",
              "show_icon",
              "ShowIcon");
      for (var f : fields.entrySet())
        if (c.containsKey(f.getKey())) old.put(f.getValue(), c.get(f.getKey()));
      if (c.get("hidden_effect") instanceof Nbt.Compound hidden) {
        var converted = effects(new Nbt.ListTag(10, List.of(hidden)), rules, loss, path);
        if (!converted.values().isEmpty()) old.put("HiddenEffect", converted.values().getFirst());
      }
      out.add(old);
    }
    return new Nbt.ListTag(10, out);
  }

  private static int color(String name) {
    return Math.max(
        0,
        List.of(
                "white",
                "orange",
                "magenta",
                "light_blue",
                "yellow",
                "lime",
                "pink",
                "gray",
                "light_gray",
                "cyan",
                "purple",
                "blue",
                "brown",
                "green",
                "red",
                "black")
            .indexOf(name));
  }

  private static Nbt.Compound explosion(Nbt.Compound c) {
    var out =
        new Nbt.Compound()
            .with(
                "Type",
                (byte)
                    Math.max(
                        0,
                        List.of("small_ball", "large_ball", "star", "creeper", "burst")
                            .indexOf(c.string("shape"))));
    for (var e :
        Map.of(
                "colors",
                "Colors",
                "fade_colors",
                "FadeColors",
                "has_trail",
                "Trail",
                "has_twinkle",
                "Flicker")
            .entrySet()) if (c.containsKey(e.getKey())) out.put(e.getValue(), c.get(e.getKey()));
    return out;
  }

  private static class VersionYamlUnchecked {
    @SuppressWarnings("unchecked")
    static Map<String, String> bannerNames() {
      try {
        return (Map<String, String>) (Map<?, ?>) VersionYaml.resource("banner-patterns.yml");
      } catch (java.io.IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
    }
  }
}
