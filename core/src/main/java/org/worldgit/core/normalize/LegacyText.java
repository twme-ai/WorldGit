package org.worldgit.core.normalize;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.*;
import org.worldgit.core.anvil.Nbt;

/** 舊版文字 JSON 的語意正規化；只處理原版文字欄位，custom_data 保持不透明。 */
final class LegacyText {
  private LegacyText() {}

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ObjectReader READER = JSON.reader()
      .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  static Nbt.Compound normalize(Nbt.Compound input) {
    var out = Nbt.copy(input);
    text(out, "CustomName");
    if (out.string("id").equals("minecraft:text_display")) text(out, "text");
    for (String side : List.of("front_text", "back_text"))
      if (out.get(side) instanceof Nbt.Compound sign)
        for (String key : List.of("messages", "filtered_messages")) texts(sign, key);
    items(out);
    return out;
  }

  private static void items(Nbt.Compound value) {
    boolean stack = !value.string("id").isEmpty()
        && (value.containsKey("Count") || value.containsKey("count")
            || value.containsKey("components"));
    if (stack) {
      var tag = value.compound("tag");
      var display = tag.compound("display");
      text(display, "Name");
      texts(display, "Lore");
      if (value.string("id").equals("minecraft:written_book")) {
        texts(tag, "pages");
        if (tag.get("filtered_pages") instanceof Nbt.Compound pages)
          for (String key : new ArrayList<>(pages.keySet())) text(pages, key);
      }
      if (tag.get("BlockEntityTag") instanceof Nbt.Compound be)
        tag.put("BlockEntityTag", normalize(be));
      if (tag.get("EntityTag") instanceof Nbt.Compound entity)
        tag.put("EntityTag", normalize(entity));
      var components = value.compound("components");
      for (String key : List.of("minecraft:custom_name", "minecraft:item_name"))
        text(components, key);
      texts(components, "minecraft:lore");
      if (components.get("minecraft:written_book_content") instanceof Nbt.Compound book)
        for (Object page : book.list("pages").values())
          if (page instanceof Nbt.Compound filtered) {
            text(filtered, "raw");
            text(filtered, "filtered");
          }
      for (var entry : components.entrySet()) {
        if (entry.getKey().equals("minecraft:custom_data")) continue;
        Object nested = entry.getValue();
        if (Set.of("minecraft:block_entity_data", "minecraft:entity_data")
            .contains(entry.getKey()) && nested instanceof Nbt.Compound data)
          entry.setValue(normalize(data));
        else nestedItems(nested);
      }
      return;
    }
    for (var entry : value.entrySet()) {
      if (Set.of("data", "minecraft:custom_data").contains(entry.getKey())) continue;
      if (entry.getKey().equals("Passengers") && entry.getValue() instanceof Nbt.ListTag list)
        entry.setValue(new Nbt.ListTag(list.type(), list.values().stream()
            .map(p -> (Object) normalize((Nbt.Compound) p)).toList()));
      else nestedItems(entry.getValue());
    }
  }

  private static void nestedItems(Object value) {
    if (value instanceof Nbt.Compound compound) items(compound);
    else if (value instanceof Nbt.ListTag list)
      for (Object element : list.values()) nestedItems(element);
  }

  private static void text(Nbt.Compound value, String key) {
    if (value.get(key) instanceof String json) value.put(key, canonical(json));
  }

  private static void texts(Nbt.Compound value, String key) {
    if (value.get(key) instanceof Nbt.ListTag list && list.type() == 8)
      value.put(key, new Nbt.ListTag(8, list.values().stream()
          .map(v -> (Object) canonical((String) v)).toList()));
  }

  private static String canonical(String text) {
    try {
      JsonNode node = READER.readTree(text);
      if (node == null || !(node.isTextual() || node.isObject() || node.isArray())) return text;
      return JSON.writeValueAsString(sorted(node, true));
    } catch (IOException | IllegalArgumentException e) {
      // 不以正規化猜測或修復原版無法解碼的資料。
      return text;
    }
  }

  private static JsonNode sorted(JsonNode node, boolean component) {
    if (component && node.isObject() && node.size() == 1 && node.path("text").isTextual())
      return node.get("text");
    if (node.isObject()) {
      var result = JSON.createObjectNode();
      var keys = new TreeSet<String>();
      node.fieldNames().forEachRemaining(keys::add);
      for (String key : keys) {
        boolean childComponent = component && Set.of("extra", "with", "separator").contains(key)
            || key.equals("contents") && node.path("action").asText().equals("show_text");
        result.set(key, sorted(node.get(key), childComponent));
      }
      return result;
    }
    if (node.isArray()) {
      var result = JSON.createArrayNode();
      for (JsonNode child : node) result.add(sorted(child, component));
      return result;
    }
    return node;
  }
}
