package org.worldgit.core.config;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.worldgit.core.anvil.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 離線版本 tag + 已啟用 datapack；後面的 pack 優先，遞迴參照在覆寫完成後解析。 */
public final class EntityTagRegistry implements EntitySemantics {
  private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
  private static final Pattern RESOURCE =
      Pattern.compile("data/([a-z0-9_.-]+)/tags/entity_type/([a-z0-9_./-]+)\\.json");
  private static final int MAX_RESOURCE = 1_048_576, MAX_TOTAL = 8_388_608, MAX_TAGS = 2048;

  private record Value(String id, boolean required) {}

  private final Map<String, Set<String>> tags;
  private final List<String> warnings;
  private final String fingerprint;

  private EntityTagRegistry(
      Map<String, Set<String>> tags, List<String> warnings, String fingerprint) {
    this.tags = Map.copyOf(tags);
    this.warnings = List.copyOf(warnings);
    this.fingerprint = fingerprint;
  }

  public String fingerprint() {
    return fingerprint;
  }

  public List<String> warnings() {
    return warnings;
  }

  @Override
  public boolean persistent(Nbt.Compound entity) {
    return EntitySemantics.OFFLINE.persistent(entity);
  }

  @Override
  public boolean inTag(String tag, String type) {
    Set<String> values = tags.get(tag);
    if (values == null)
      throw new IllegalArgumentException(
          "找不到離線 entity tag #" + tag + "；請檢查已啟用的 datapack，或由版本 adapter 提供 registry");
    return values.contains(type);
  }

  /**
   * 由平台 adapter 提供「模組內建 datapack」的 entity tag 資源（例如 Fabric 的 mod id 同時是 pack 名稱）。
   * 回傳 tag id（{@code namespace:path}）對 JSON bytes；pack 不認得時回傳 null。
   */
  @FunctionalInterface
  public interface PackResolver {
    Map<String, byte[]> tags(String pack) throws IOException;
  }

  public static EntityTagRegistry load(Path world, int dataVersion) throws IOException {
    return load(world, dataVersion, null);
  }

  public static EntityTagRegistry load(Path world, int dataVersion, PackResolver resolver)
      throws IOException {
    var definitions = new TreeMap<String, List<Value>>();
    var warnings = new ArrayList<String>();
    var level = WorldLayout.readGzip(world.resolve("level.dat")).compound("Data");
    var datapacks = level.compound("DataPacks");
    if (datapacks.containsKey("Enabled") && !(datapacks.get("Enabled") instanceof Nbt.ListTag))
      throw new IOException("DataPacks.Enabled 必須是字串清單");
    List<Object> packs =
        datapacks.containsKey("Enabled") ? datapacks.list("Enabled").values() : List.of("vanilla");
    int total = 0;
    for (Object packValue : packs) {
      if (!(packValue instanceof String pack)) throw new IOException("DataPacks.Enabled 必須是字串清單");
      if (pack.equals("vanilla")) {
        try (var in =
            EntityTagRegistry.class.getResourceAsStream(
                "/org/worldgit/entity-tags/" + dataVersion + ".yml")) {
          if (in == null) throw new IOException("離線 entity tag 尚未支援 DataVersion " + dataVersion);
          var root =
              mapping(
                  parse(in.readNBytes(MAX_RESOURCE + 1), "vanilla tags " + dataVersion),
                  "vanilla tags");
          for (var e : root.entrySet())
            definitions.put(
                identifier(e.getKey(), false, "vanilla tags"),
                values(e.getValue(), "vanilla tags"));
        }
      } else if (pack.startsWith("file/")) {
        Path root = world.resolve("datapacks").toAbsolutePath().normalize();
        Path path = root.resolve(pack.substring(5)).normalize();
        if (!path.startsWith(root) || path.equals(root))
          throw new IOException("datapack 路徑越界：" + pack);
        if (!Files.exists(path)) {
          warnings.add("啟用的 datapack 不存在：" + pack + "；離線 tag registry 可能不完整。");
          continue;
        }
        if (Files.isDirectory(path)) {
          try (var files = Files.walk(path)) {
            var entries =
                files
                    .filter(Files::isRegularFile)
                    .filter(
                        file ->
                            tagId(path.relativize(file).toString().replace(File.separatorChar, '/'))
                                != null)
                    .limit(MAX_TAGS + 1)
                    .sorted()
                    .toList();
            if (entries.size() > MAX_TAGS) throw new IOException(path + "：entity tag 檔案過多");
            for (Path file : entries) {
              String id = tagId(path.relativize(file).toString().replace(File.separatorChar, '/'));
              if (id != null) {
                byte[] bytes;
                try (var in = Files.newInputStream(file)) {
                  bytes = in.readNBytes(MAX_RESOURCE + 1);
                }
                total = budget(total, bytes.length);
                merge(definitions, id, bytes, file.toString());
              }
            }
          }
        } else {
          try (var zip = new ZipFile(path.toFile())) {
            var entries =
                zip.stream()
                    .filter(e -> !e.isDirectory() && tagId(e.getName()) != null)
                    .limit(MAX_TAGS + 1)
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            if (entries.size() > MAX_TAGS) throw new IOException(path + "：entity tag 檔案過多");
            for (var entry : entries) {
              byte[] bytes;
              try (var in = zip.getInputStream(entry)) {
                bytes = in.readNBytes(MAX_RESOURCE + 1);
              }
              total = budget(total, bytes.length);
              merge(definitions, tagId(entry.getName()), bytes, path + "!" + entry.getName());
            }
          }
        }
        // 這兩版的內建 feature packs 與 Paper 本身沒有 entity_type tag 資料。
      } else if (resolver != null && resolver.tags(pack) != null) {
        for (var e : new TreeMap<>(resolver.tags(pack)).entrySet()) {
          total = budget(total, e.getValue().length);
          merge(definitions, e.getKey(), e.getValue(), pack + "!" + e.getKey());
        }
      } else if (pack.equals("fabric") || pack.startsWith("fabric-")) {
        // Fabric API 的內建 pack 只有 c: 慣例 tag；沒有 adapter 時（CLI 離線）略過並記錄，
        // 只有引用 #c:… 的忽略規則會因「找不到 tag」而明確失敗。
        warnings.add("Fabric 內建 datapack " + pack + " 的 entity tag 離線未載入（#c:… tag 無法使用）。");
      } else if (!Set.of(
              "paper", "minecart_improvements", "redstone_experiments", "trade_rebalance")
          .contains(pack)) {
        throw new IOException("離線無法解析內建/模組 datapack：" + pack + "；請由版本 adapter 提供 registry");
      }
      if (definitions.size() > MAX_TAGS) throw new IOException("entity tags 超過 " + MAX_TAGS);
    }
    var resolved = new TreeMap<String, Set<String>>();
    int resolvedValues = 0;
    for (String tag : definitions.keySet()) {
      resolve(tag, definitions, resolved, new LinkedHashSet<>());
      resolvedValues += resolved.get(tag).size();
      if (resolvedValues > 100_000) throw new IOException("entity tag 展開後超過 100000 筆");
    }
    // 包含啟用清單及缺少的 pack；registry 改變必須讓 capture 重建，不能沿用舊篩選結果。
    var canonical = new Nbt.Compound().with("packs", new Nbt.ListTag(8, packs));
    var nbtTags = new Nbt.Compound();
    resolved.forEach(
        (tag, types) ->
            nbtTags.put(tag, new Nbt.ListTag(8, new ArrayList<>(new TreeSet<>(types)))));
    canonical.put("tags", nbtTags);
    canonical.put("warnings", new Nbt.ListTag(8, new ArrayList<>(warnings)));
    try {
      String fingerprint =
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(Nbt.write(canonical)));
      return new EntityTagRegistry(resolved, warnings, fingerprint);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private static int budget(int total, int size) throws IOException {
    if (size > MAX_RESOURCE || total + size > MAX_TOTAL)
      throw new IOException("entity tag 資料超過大小限制");
    return total + size;
  }

  private static String tagId(String path) {
    var match = RESOURCE.matcher(path);
    return match.matches() ? match.group(1) + ":" + match.group(2) : null;
  }

  private static Object parse(byte[] data, String source) throws IOException {
    if (data.length > MAX_RESOURCE) throw new IOException(source + "：entity tag 超過 1 MiB");
    var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setNestingDepthLimit(8);
    options.setCodePointLimit(MAX_RESOURCE);
    try {
      return new Yaml(new SafeConstructor(options)).load(new String(data, StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      throw new IOException(source + "：tag 語法錯誤：" + e.getMessage(), e);
    }
  }

  private static Map<?, ?> mapping(Object value, String source) throws IOException {
    if (!(value instanceof Map<?, ?> map)) throw new IOException(source + "：tag 必須是物件");
    return map;
  }

  private static String identifier(Object value, boolean reference, String source)
      throws IOException {
    if (!(value instanceof String id))
      throw new IOException(source + "：無效的 entity type/tag id：" + value);
    boolean tag = reference && id.startsWith("#");
    String bare = tag ? id.substring(1) : id;
    if (!bare.contains(":")) bare = "minecraft:" + bare;
    if (!ID.matcher(bare).matches())
      throw new IOException(source + "：無效的 entity type/tag id：" + value);
    return tag ? "#" + bare : bare;
  }

  private static List<Value> values(Object value, String source) throws IOException {
    if (!(value instanceof List<?> list) || list.size() > 4096)
      throw new IOException(source + "：values 必須是 ≤ 4096 筆的清單");
    var result = new ArrayList<Value>();
    for (Object entry : list) {
      if (entry instanceof String) result.add(new Value(identifier(entry, true, source), true));
      else {
        var map = mapping(entry, source);
        if (!Set.of("id", "required").containsAll(map.keySet()))
          throw new IOException(source + "：未知 values 欄位");
        Object required = map.containsKey("required") ? map.get("required") : true;
        if (!(required instanceof Boolean flag)) throw new IOException(source + "：required 必須是布林值");
        result.add(new Value(identifier(map.get("id"), true, source), flag));
      }
    }
    return List.copyOf(result);
  }

  private static void merge(
      Map<String, List<Value>> definitions, String id, byte[] data, String source)
      throws IOException {
    var map = mapping(parse(data, source), source);
    if (!Set.of("replace", "values").containsAll(map.keySet()))
      throw new IOException(source + "：未知 tag 欄位");
    Object replace = map.containsKey("replace") ? map.get("replace") : false;
    if (!(replace instanceof Boolean flag)) throw new IOException(source + "：replace 必須是布林值");
    var combined = new ArrayList<Value>();
    if (!flag) combined.addAll(definitions.getOrDefault(id, List.of()));
    combined.addAll(values(map.get("values"), source));
    if (combined.size() > 4096) throw new IOException(source + "：tag 合併後超過 4096 筆");
    definitions.put(id, List.copyOf(combined));
  }

  private static Set<String> resolve(
      String tag,
      Map<String, List<Value>> definitions,
      Map<String, Set<String>> resolved,
      LinkedHashSet<String> active)
      throws IOException {
    if (resolved.containsKey(tag)) return resolved.get(tag);
    if (!active.add(tag)) throw new IOException("entity tag 循環參照：" + active + " → " + tag);
    if (active.size() > 64) throw new IOException("entity tag 參照深度超過 64");
    var result = new HashSet<String>();
    for (var value : definitions.get(tag)) {
      if (!value.id.startsWith("#")) result.add(value.id);
      else {
        String target = value.id.substring(1);
        if (definitions.containsKey(target))
          result.addAll(resolve(target, definitions, resolved, active));
        else if (value.required) throw new IOException("entity tag #" + tag + " 缺少必要參照 #" + target);
      }
    }
    if (result.size() > 4096) throw new IOException("entity tag #" + tag + " 展開後超過 4096 筆");
    active.remove(tag);
    var frozen = Set.copyOf(result);
    resolved.put(tag, frozen);
    return frozen;
  }
}
