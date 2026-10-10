package org.worldgit.core.anvil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 不認識的維度 saved-data 保留完整 NBT，名稱編碼為單一 tree entry。 */
public final class SavedData {
  private SavedData() {}
  /** 暫態規則也用於歷史 tree；不可只在磁碟 capture 排除。 */
  public static final String NORMALIZATION = "runtime-saved-data-v2";
  private static final Set<String> RUNTIME_FILES = Set.of(
      "data/minecraft/world_clocks.dat", "data/minecraft/weather.dat",
      "data/minecraft/wandering_trader.dat");

  /** 解碼中立 tree 名稱，不將模組的同名檔誤判成 vanilla saved-data。 */
  public static String relativePath(String name) {
    int start = name.lastIndexOf("saved.");
    if (start < 0 || !name.endsWith(".nbt")) return null;
    try {
      String path = new String(Base64.getUrlDecoder().decode(name.substring(start + 6, name.length() - 4)), StandardCharsets.UTF_8);
      return path.startsWith("data/") && path.endsWith(".dat") && !path.contains("..")
          && !path.contains("\\") && !path.contains(":") ? path : null;
    } catch (IllegalArgumentException invalid) { return null; }
  }

  public static boolean transientEntry(String name) {
    String path = relativePath(name);
    return path != null && RUNTIME_FILES.contains(path);
  }

  /** 錯誤訊息保留維度資訊，檔名使用可讀路徑；不合法編碼仍顯示原 key。 */
  public static String displayName(String name) {
    String path = relativePath(name);
    if (path == null) return name;
    int start = name.lastIndexOf("saved.");
    return start == 0 ? path : name.substring(0, start - 1) + ": " + path;
  }

  /** null 表示整檔暫態；其他資料僅剝除已知 runtime 欄位。 */
  public static byte[] normalize(String name, byte[] bytes) throws IOException {
    String path = relativePath(name);
    if (path == null) return bytes;
    if (RUNTIME_FILES.contains(path)) return null;
    String field = runtimeField(path);
    if (field == null && !path.equals("data/minecraft/stopwatches.dat")) return bytes;
    var root = Nbt.read(bytes);
    var data = root.compound("data");
    if (field != null) data.remove(field);
    if (path.equals("data/minecraft/random_sequences.dat")) {
      // RNG 游標不追蹤；/random reset 的設定仍保留，預設檔的首次建立沒有差異。
      if (data.integer("salt", 0) == 0) data.remove("salt");
      for (String key : List.of("include_world_seed", "include_sequence_id"))
        if (!data.containsKey(key) || data.integer(key, 1) == 1) data.remove(key);
    }
    if (path.equals("data/minecraft/stopwatches.dat")) {
      var watches = data.compound("stopwatches");
      watches.replaceAll((key, value) -> 0L);
      if (watches.isEmpty()) data.remove("stopwatches");
    }
    root.put("data", data);
    if (data.isEmpty() && root.keySet().stream().allMatch(key -> Set.of("data", "DataVersion").contains(key))) return null;
    return Nbt.write(root);
  }
  public static Map<String, byte[]> capture(Path directory, String prefix) throws IOException {
    var result = new TreeMap<String, byte[]>(); Path data = directory.resolve("data");
    if (!Files.isDirectory(data)) return result;
    long bytes = 0;
    for (Path file : files(data)) {
      String relative = directory.relativize(file).toString().replace(java.io.File.separatorChar, '/');
      String name = file.getFileName().toString();
      if (!name.endsWith(".dat")
          || RUNTIME_FILES.contains(relative)
          || Set.of("game_rules.dat", "world_border.dat", "world_gen_settings.dat").contains(name)
          || prefix.isEmpty() && (name.matches("map_\\d+\\.dat")
              || Set.of("scoreboard.dat", "custom_boss_events.dat").contains(name))) continue;
      if (Files.isSymbolicLink(file)) throw new IOException("saved-data 不接受符號連結");
      String key = prefix + "saved." + Base64.getUrlEncoder().withoutPadding().encodeToString(relative.getBytes(StandardCharsets.UTF_8)) + ".nbt";
      byte[] value = normalize(key, Nbt.write(WorldLayout.readGzip(file)));
      if (value == null) continue;
      bytes += value.length;
      if (result.size() >= 4096 || bytes > 128L * 1024 * 1024) throw new IOException("saved-data 超過 4096 檔／128 MiB");
      result.put(key, value);
    }
    return result;
  }
  /** 只排除遊戲時鐘；襲擊清單、next_id、出生點與難度等內容仍完整追蹤。 */
  private static String runtimeField(String path) {
    if (path.equals("data/minecraft/raids.dat")) return "tick";
    if (path.equals("data/minecraft/random_sequences.dat")) return "sequences";
    if (path.equals("data/paper/level_override.dat") || path.equals("data/paper/level_overrides.dat")) return "game_time";
    return null;
  }
  /** 套用時保留既有時鐘；新組裝的世界補 codec 必要的零值。 */
  public static Nbt.Compound materialize(Path file, Nbt.Compound target, Nbt.Compound current) {
    String path = file.toString().replace(java.io.File.separatorChar, '/');
    int start = path.lastIndexOf("/data/");
    if (start >= 0) path = path.substring(start + 1);
    if (RUNTIME_FILES.contains(path)) return Nbt.copy(current);
    String field = runtimeField(path);
    if (field != null) {
      var data = target.compound("data");
      Object fallback = field.equals("sequences") ? new Nbt.Compound() : field.equals("game_time") ? (Object) 0L : 0;
      data.put(field, Nbt.copy(current.compound("data").getOrDefault(field, fallback)));
      if (field.equals("sequences")) {
        data.putIfAbsent("salt", 0);
        data.putIfAbsent("include_world_seed", (byte) 1);
        data.putIfAbsent("include_sequence_id", (byte) 1);
      }
      target.put("data", data);
    }
    if (path.equals("data/minecraft/stopwatches.dat")) {
      var watches = target.compound("data").compound("stopwatches");
      watches.replaceAll((key, value) -> Nbt.copy(current.compound("data").compound("stopwatches").getOrDefault(key, 0L)));
      target.compound("data").put("stopwatches", watches);
    }
    return target;
  }
  public static List<Path> files(Path root) throws IOException {
    var result = new ArrayList<Path>();
    Files.walkFileTree(root, new SimpleFileVisitor<>() {
      @Override public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
        return WorldLayout.excluded(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
      }
      @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
        if (attrs.isRegularFile() || attrs.isSymbolicLink()) result.add(file);
        return FileVisitResult.CONTINUE;
      }
    });
    result.sort(Comparator.naturalOrder()); return result;
  }
  public static Path path(Path directory, String encoded) throws IOException {
    String relative;
    try { relative = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8); }
    catch (IllegalArgumentException ex) { throw new IOException("saved-data 名稱編碼無效", ex); }
    Path target = directory.resolve(relative).normalize();
    if (!relative.startsWith("data/") || !relative.endsWith(".dat") || relative.contains("\\")
        || relative.contains(":") || relative.contains("../") || WorldLayout.excluded(Path.of(relative))
        || !target.startsWith(directory.resolve("data"))) throw new IOException("saved-data 路徑無效");
    for (Path part = target; part != null && part.startsWith(directory); part = part.getParent())
      if (Files.isSymbolicLink(part)) throw new IOException("saved-data 路徑包含符號連結");
    return target;
  }
}
