package org.worldgit.core.version;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.model.BlockState;

/** 資料驅動的版本邊界、registry 與替代設定。未知自訂 id 必須出現在損失清單。 */
public final class ReverseRules {
  private final Map<String, Object> data, registry, variants;
  private final Map<String, String> fallbacks = new TreeMap<>(), overrides = new TreeMap<>();
  private final int from, to;
  public final String fingerprint;

  @SuppressWarnings("unchecked")
  public ReverseRules(int from, int to, Path config) throws IOException {
    this.from = from;
    this.to = to;
    data = VersionYaml.resource("reverse.yml");
    registry = VersionYaml.resource(to + ".yml");
    @SuppressWarnings("unchecked")
    var entries = (Map<String,Object>)VersionYaml.resource("variants.yml").get(String.valueOf(to));
    variants = entries;
    ((Map<String, String>) data.get("fallbacks")).forEach(fallbacks::put);
    byte[] bytes =
        config != null && Files.isRegularFile(config) ? Files.readAllBytes(config) : new byte[0];
    if (bytes.length > 0) {
      var root = VersionYaml.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
      if (root.get("conversion") instanceof Map<?, ?> c) {
        if (c.get("fallbacks") instanceof Map<?, ?> m)
          for (var e : m.entrySet()) fallbacks.put((String) e.getKey(), (String) e.getValue());
        if (c.get("replacements") instanceof Map<?, ?> m)
          for (var e : m.entrySet()) overrides.put((String) e.getKey(), (String) e.getValue());
      }
    }
    fingerprint = data.get("revision") + "-" + MojangRuntime.sha1(bytes);
  }

  boolean crosses(int boundary) {
    return from > boundary && to <= boundary;
  }

  int effectId(String id) {
    Object value = ((Map<?, ?>) registry.getOrDefault("effect-ids", Map.of())).get(id);
    return value instanceof Number n ? n.intValue() : -1;
  }

  String attribute(String id) {
    if (known("attributes", id)) return id;
    Object entries = registry.get("attributes");
    if (entries instanceof List<?> values)
      for (Object value : values) {
        String candidate = (String) value;
        if (candidate.startsWith("minecraft:")
            && candidate.endsWith("." + id.replaceFirst("^minecraft:", ""))) return candidate;
      }
    return id;
  }

  @SuppressWarnings("unchecked")
  public Map<String, String> components() {
    return (Map<String, String>) data.get("components-to-nbt");
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> list(String key) {
    return (List<Map<String, Object>>) data.get(key);
  }

  public String rename(String kind, String id) {
    for (var r : list("renames"))
      if (kind.equals(r.get("kind"))
          && id.equals(r.get("from"))
          && crosses(((Number) r.get("boundary")).intValue())) id = (String) r.get("to");
    return id;
  }

  public boolean known(String kind, String id) {
    Object entries =
        registry.get(
            switch (kind) {
              case "block" -> "blocks";
              case "item" -> "items";
              case "entity" -> "entities";
              case "biome" -> "biomes";
              case "block-entity" -> "block-entities";
              case "component" -> "components";
              default -> kind;
            });
    return entries instanceof Map<?, ?> m
        ? m.containsKey(id)
        : entries instanceof List<?> l && l.contains(id);
  }

  public String id(String kind, String id, String location, ConversionReport.Collector loss)
      throws IOException {
    String renamed = rename(kind, id);
    if (known(kind, renamed)) return renamed;
    String replacement =
        overrides.getOrDefault(kind + ":" + id, fallbacks.getOrDefault(kind, "skip"));
    if (!replacement.equals("skip") && !known(kind, replacement))
      throw new IOException("替代 " + kind + " id 不存在於目標版本：" + replacement);
    loss.add(kind, id, replacement, location);
    return replacement;
  }
  String variant(String kind,String id,String fallback,String path,ConversionReport.Collector loss) throws IOException {
    Object values=variants.get(kind);
    if(values instanceof List<?> list&&list.contains(id))return id;
    String next=overrides.getOrDefault("variant:"+id,fallbacks.getOrDefault(kind,fallback));
    if(values instanceof List<?> list&&!list.contains(next))throw new IOException("替代 variant 不存在於目標版本："+next);
    if(!id.equals(next))loss.add("variant",kind+"/"+id,next,path);
    return next;
  }
  boolean hasVariant(String kind){return variants.containsKey(kind);}

  boolean canHost(String block, String blockEntity) throws IOException {
    Object values = VersionYaml.resource("block-entity-hosts.yml").get(blockEntity);
    if (values instanceof List<?> list)
      for (Object value : list) {
        String pattern = String.valueOf(value);
        if (block.equals(pattern)
            || pattern.startsWith("*:") && block.endsWith(pattern.substring(2))) return true;
      }
    return block.equals(blockEntity);
  }

  @SuppressWarnings("unchecked")
  public BlockState block(BlockState state, String location, ConversionReport.Collector loss)
      throws IOException {
    if (state.air()) return state;
    String id = id("block", state.name(), location, loss);
    if (id.equals("skip")) return BlockState.AIR;
    var def = (Map<String, Object>) ((Map<?, ?>) registry.get("blocks")).get(id);
    var props = (Map<String, List<String>>) def.get("properties");
    var defaults = (Map<String, String>) def.get("default");
    var out = new TreeMap<>(defaults);
    for (var e : state.properties().entrySet()) {
      if (props.containsKey(e.getKey()) && props.get(e.getKey()).contains(e.getValue()))
        out.put(e.getKey(), e.getValue());
      else
        loss.add(
            "block-property",
            state.name() + "[" + e.getKey() + "=" + e.getValue() + "]",
            id + out,
            location);
    }
    return new BlockState(id, out);
  }

  public void fields(String kind, Nbt.Compound n) {
    for (var r : list("fields"))
      if (kind.equals(r.get("kind"))
          && (r.get("id").equals("*") || n.string("id").equals(r.get("id")))
          && crosses(((Number) r.get("boundary")).intValue())) {
        String a = (String) r.get("from"), b = (String) r.get("to");
        if (!a.equals(b) && n.containsKey(a)) n.put(b, n.remove(a));
      }
    if (data.get("defaults") instanceof List<?> defaults)
      for (Object entry : defaults) {
        var r = (Map<?, ?>) entry;
        if (kind.equals(r.get("kind"))
            && n.string("id").equals(r.get("id"))
            && crosses(((Number) r.get("boundary")).intValue()))
          n.putIfAbsent((String) r.get("field"), r.get("value"));
      }
  }

  /** 舊版的隱含值與數值寬度；非等價值必須列入損失，不能靠原版載入時丟棄欄位。 */
  void legacyFields(String kind, Nbt.Compound n, String path, ConversionReport.Collector loss)
      throws IOException {
    for (var rule : list("omit-empty-fields"))
      if (kind.equals(rule.get("kind"))
          && ("*".equals(rule.get("id")) || n.string("id").equals(rule.get("id")))
          && crosses(((Number) rule.get("boundary")).intValue())) {
        String key = (String) rule.get("field");
        if (n.get(key) instanceof Nbt.Compound value && value.isEmpty()) n.remove(key);
      }
    for (String group : List.of("implicit-fields", "numeric-fields"))
      if (data.get(group) instanceof List<?> entries)
        for (Object entry : entries) {
          var rule = (Map<?, ?>) entry;
          boolean id = "*".equals(rule.get("id")) || n.string("id").equals(rule.get("id"))
              || rule.get("ids") instanceof List<?> ids && ids.contains(n.string("id"));
          if (!kind.equals(rule.get("kind")) || !id
              || !crosses(((Number) rule.get("boundary")).intValue())) continue;
          if (group.equals("implicit-fields")) {
            String key = (String) rule.get("field");
            if (!n.containsKey(key) && !rule.containsKey("source-default")) continue;
            Object value = n.containsKey(key) ? n.remove(key) : rule.get("source-default");
            Object implicit = rule.get("value");
            boolean equal = value instanceof Number a && implicit instanceof Number b
                ? a.doubleValue() == b.doubleValue() : Objects.equals(value, implicit);
            if (rule.get("equivalent") instanceof List<?> values)
              equal |= values.stream().anyMatch(v -> value instanceof Number a && v instanceof Number b
                  ? a.doubleValue() == b.doubleValue() : Objects.equals(value, v));
            if (!equal) loss.add(kind + "-field", n.string("id") + "/" + key + "=" + value,
                "implicit " + implicit, path);
          } else {
            for (Object field : (List<?>) rule.get("fields")) {
              String key = (String) field;
              if (!(n.get(key) instanceof Number value)) continue;
              boolean small = rule.get("type").equals("byte");
              long min = small ? Byte.MIN_VALUE : Short.MIN_VALUE;
              long max = small ? Byte.MAX_VALUE : Short.MAX_VALUE;
              long narrowed = Math.clamp(value.longValue(), min, max);
              if (value.doubleValue() != narrowed)
                loss.add(kind + "-field", n.string("id") + "/" + key + "=" + value,
                    Long.toString(narrowed), path);
              if (small) n.put(key, (byte) narrowed); else n.put(key, (short) narrowed);
            }
          }
        }
  }

  @SuppressWarnings("unchecked")
  Map<String, String> signComponents() {
    return (Map<String, String>) data.getOrDefault("sign-components", Map.of());
  }
}
