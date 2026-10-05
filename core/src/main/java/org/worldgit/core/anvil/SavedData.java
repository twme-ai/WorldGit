package org.worldgit.core.anvil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 不認識的維度 saved-data 保留完整 NBT，名稱編碼為單一 tree entry。 */
public final class SavedData {
  private SavedData() {}
  public static Map<String, byte[]> capture(Path directory, String prefix) throws IOException {
    var result = new TreeMap<String, byte[]>(); Path data = directory.resolve("data");
    if (!Files.isDirectory(data)) return result;
    long bytes = 0;
    for (Path file : files(data)) {
      String name = file.getFileName().toString();
      if (!name.endsWith(".dat")
          || Set.of("game_rules.dat", "world_border.dat", "world_gen_settings.dat").contains(name)
          || prefix.isEmpty() && (name.matches("map_\\d+\\.dat")
              || Set.of("scoreboard.dat", "custom_boss_events.dat").contains(name))) continue;
      if (Files.isSymbolicLink(file)) throw new IOException("saved-data 不接受符號連結");
      var nbt = WorldLayout.readGzip(file);
      String runtime = runtimeField(file);
      if (runtime != null) nbt.compound("data").remove(runtime);
      byte[] value = Nbt.write(nbt); bytes += value.length;
      if (result.size() >= 4096 || bytes > 128L * 1024 * 1024) throw new IOException("saved-data 超過 4096 檔／128 MiB");
      String relative = directory.relativize(file).toString().replace(java.io.File.separatorChar, '/');
      result.put(prefix + "saved." + Base64.getUrlEncoder().withoutPadding().encodeToString(relative.getBytes(StandardCharsets.UTF_8)) + ".nbt", value);
    }
    return result;
  }
  /** 只排除遊戲時鐘；襲擊清單、next_id、出生點與難度等內容仍完整追蹤。 */
  private static String runtimeField(Path file) {
    String path = file.toString().replace(java.io.File.separatorChar, '/');
    if (path.endsWith("/data/minecraft/raids.dat")) return "tick";
    if (path.endsWith("/data/paper/level_override.dat") || path.endsWith("/data/paper/level_overrides.dat")) return "game_time";
    return null;
  }
  /** 套用時保留既有時鐘；新組裝的世界補 codec 必要的零值。 */
  public static Nbt.Compound materialize(Path file, Nbt.Compound target, Nbt.Compound current) {
    String field = runtimeField(file);
    if (field != null && !target.compound("data").containsKey(field)) {
      var data = target.compound("data");
      Object fallback = field.equals("game_time") ? (Object) 0L : 0;
      data.put(field, current.compound("data").getOrDefault(field, fallback));
      target.put("data", data);
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
