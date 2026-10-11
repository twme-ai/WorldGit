package org.worldgit.core.version;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.worldgit.core.anvil.*;

/** Player files stay outside history, but must move/convert with a whole-world migration. */
final class PlayerDataMigration {
  static final List<String> ROOTS = List.of("players", "playerdata", "advancements", "stats");

  static ConversionReport prepare(Path world, Path destination, int from, int to, Path config)
      throws IOException {
    var rules = new ReverseRules(from, to, config);
    var loss = new ConversionReport.Collector();
    NbtUpgrader dfu = null;
    long total = 0;
    int count = 0;
    try {
      // 不把同時殘留的另一種版型當成空資料；否則整世界替換會刪掉它。
      for (String name : from >= MinecraftVersion.NAMESPACED_STORAGE
          ? List.of("playerdata", "advancements", "stats") : List.of("players")) {
        Path unexpected = world.resolve(name);
        if (Files.isSymbolicLink(unexpected)) throw new IOException("玩家目錄不可為符號連結");
        if (Files.isDirectory(unexpected) && !SavedData.files(unexpected).isEmpty())
          throw new IOException("兩種版本的玩家資料目錄同時存在；請先合併或移出：" + unexpected);
      }
      for (String kind : List.of("data", "advancements", "stats")) {
        Path source =
            world.resolve(
                from >= MinecraftVersion.NAMESPACED_STORAGE
                    ? "players/" + kind
                    : kind.equals("data") ? "playerdata" : kind);
        if (!Files.exists(source)) continue;
        if (Files.isSymbolicLink(source)) throw new IOException("玩家目錄不可為符號連結");
        for (Path file : SavedData.files(source)) {
          if (Files.isSymbolicLink(file)) throw new IOException("玩家資料不可為符號連結");
          if (++count > 100000
              || (total += Files.size(file)) > 128L * 1024 * 1024
              || Files.size(file) > 4 * 1024 * 1024)
            throw new IOException("玩家資料超過遷移限制（10 萬檔／128 MiB／單檔 4 MiB）");
          Path relative = source.relativize(file);
          Path output =
              destination
                  .resolve(
                      to >= MinecraftVersion.NAMESPACED_STORAGE
                          ? "players/" + kind
                          : kind.equals("data") ? "playerdata" : kind)
                  .resolve(relative);
          Files.createDirectories(output.getParent());
          if (kind.equals("data")
              && (file.toString().endsWith(".dat") || file.toString().endsWith(".dat_old"))) {
            var n = WorldLayout.readGzip(file);
            int actual = n.integer("DataVersion", from);
            if (actual < 0) throw new IOException("玩家 DataVersion 無效");
            if (actual < to) {
              if (dfu == null) dfu = new DfuProcess(MinecraftVersion.byDataVersion(to));
              n = dfu.upgrade("PLAYER", n, actual, to);
            } else if (actual > to)
              n =
                  new ReverseNbt(new ReverseRules(actual, to, config), loss, actual, to)
                      .convert(n, "player", relative.toString());
            n.put("DataVersion", to);
            try (var zipped = new GZIPOutputStream(Files.newOutputStream(output))) {
              zipped.write(Nbt.write(n));
            }
          } else if (!kind.equals("data") && file.toString().endsWith(".json")) {
            var data = VersionYaml.parse(Files.readString(file));
            if (from < to) {
              if (dfu == null) dfu = new DfuProcess(MinecraftVersion.byDataVersion(to));
              @SuppressWarnings("unchecked")
              var fixed =
                  (Map<String, Object>)
                      toJson(
                          dfu.upgrade(
                              kind.equals("stats") ? "STATS" : "ADVANCEMENTS",
                              (Nbt.Compound) toNbt(data),
                              data.get("DataVersion") instanceof Number n ? n.intValue() : from,
                              to),
                          "");
              data = fixed;
            }
            if (from > to) {
              if (kind.equals("advancements"))
                for (String key : new ArrayList<>(data.keySet()))
                  if (!key.equals("DataVersion") && !rules.known("advancements", key)) {
                    loss.add("advancement", key, "skip", relative.toString());
                    data.remove(key);
                  }
              if (kind.equals("stats") && data.get("stats") instanceof Map<?, ?> groups)
                for (var group : groups.entrySet())
                  if (group.getValue() instanceof Map<?, ?> entries) {
                    String type =
                        switch (String.valueOf(group.getKey())) {
                          case "minecraft:mined" -> "block";
                          case "minecraft:killed", "minecraft:killed_by" -> "entity";
                          case "minecraft:custom" -> "custom-stats";
                          default -> "item";
                        };
                    @SuppressWarnings("unchecked")
                    var values = (Map<String, Object>) entries;
                    for (String key : new ArrayList<>(values.keySet())) {
                      String renamed = rules.rename(type, key);
                      Object value = values.get(key);
                      if (!rules.known(type, renamed)) {
                        loss.add(
                            "statistic", group.getKey() + "/" + key, "skip", relative.toString());
                        values.remove(key);
                      } else if (!renamed.equals(key)) {
                        values.remove(key);
                        values.merge(
                            renamed,
                            value,
                            (a, b) -> ((Number) a).longValue() + ((Number) b).longValue());
                      }
                    }
                  }
            }
            data.put("DataVersion", to);
            Files.writeString(output, json(data));
          } else {
            if (from > to) loss.add("player-file", relative.toString(), "skip", kind);
            else Files.copy(file, output);
          }
        }
      }
      Path players = world.resolve("players");
      if (from >= MinecraftVersion.NAMESPACED_STORAGE && Files.isDirectory(players))
        try (var children = Files.list(players)) {
          for (Path p : children.toList())
            if (!Set.of("data", "advancements", "stats").contains(p.getFileName().toString()))
              throw new IOException("不認識的玩家資料，拒絕略過：" + p);
        }
      return loss.report(from, to, rules.fingerprint);
    } finally {
      if (dfu != null) dfu.close();
    }
  }

  private static Object toNbt(Object value) throws IOException {
    if (value instanceof Map<?, ?> map) {
      var result = new Nbt.Compound();
      for (var e : map.entrySet()) result.put(String.valueOf(e.getKey()), toNbt(e.getValue()));
      return result;
    }
    if (value instanceof Boolean b) return (byte) (b ? 1 : 0);
    if (value instanceof String || value instanceof Number) return value;
    if (value instanceof List<?> list) {
      var wrapped = new ArrayList<Object>();
      for (Object v : list) {
        Object n = toNbt(v);
        wrapped.add(n instanceof Nbt.Compound ? n : new Nbt.Compound().with("", n));
      }
      return new Nbt.ListTag(10, wrapped);
    }
    throw new IOException("玩家 JSON 含不支援的 null／資料型別");
  }

  private static Object toJson(Object value, String key) {
    if (value instanceof Nbt.Compound c) {
      if (c.size() == 1 && c.containsKey("")) return toJson(c.get(""), key);
      var out = new LinkedHashMap<String, Object>();
      c.forEach((k, v) -> out.put(k, toJson(v, k)));
      return out;
    }
    if (value instanceof Nbt.ListTag l)
      return l.values().stream().map(v -> toJson(v, key)).toList();
    if (value instanceof Byte b && key.equals("done")) return b != 0;
    return value;
  }

  private static String json(Object value) throws IOException {
    if (value instanceof Map<?, ?> map) {
      var values = new ArrayList<String>();
      for (var e : map.entrySet())
        values.add(ReverseNbt.json(String.valueOf(e.getKey())) + ":" + json(e.getValue()));
      return "{" + String.join(",", values) + "}";
    }
    if (value instanceof List<?> list) {
      var values = new ArrayList<String>();
      for (Object v : list) values.add(json(v));
      return "[" + String.join(",", values) + "]";
    }
    if (value instanceof String s) return ReverseNbt.json(s);
    if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
    throw new IOException("玩家 JSON 含不支援的 null／資料型別");
  }
}
