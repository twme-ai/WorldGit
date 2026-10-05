package org.worldgit.core.capture;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.normalize.EntityNormalizer;
import org.worldgit.core.service.OperationState;
import org.worldgit.core.store.*;

/** 各維度 UUID 集合；平台持有 repo 鎖後記錄事件，不依類型猜測自然生成來源。 */
public final class PlayerTouchedEntities {
  public static final String FILE = "player-touched.yml";
  public static final int MAX_ENTITIES = 100000;
  private PlayerTouchedEntities() {}
  public static SortedSet<UUID> read(Path repo) throws IOException {
    Path path = repo.resolve(FILE);
    if (Files.exists(path) && Files.size(path) > 8 * 1024 * 1024) throw new IOException("玩家觸及集合超過 8 MiB");
    return Files.exists(path) ? parse(Files.readAllBytes(path)) : new TreeSet<>();
  }
  public static SortedSet<UUID> parse(byte[] bytes) throws IOException {
    if (bytes.length > 8 * 1024 * 1024) throw new IOException("玩家觸及集合超過 8 MiB");
    var options = new org.yaml.snakeyaml.LoaderOptions(); options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0); options.setNestingDepthLimit(4); options.setCodePointLimit(8 * 1024 * 1024);
    try {
      Object document = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(options))
          .load(new String(bytes, StandardCharsets.UTF_8));
      if (!(document instanceof Map<?, ?> map)) throw new IOException("玩家觸及集合必須是 mapping");
      var typed = new LinkedHashMap<String, Object>();
      for (var entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key)) throw new IOException("玩家觸及集合鍵必須是文字");
        typed.put(key, entry.getValue());
      }
      return parse(typed);
    } catch (RuntimeException ex) { throw new IOException("玩家觸及集合 YAML 無效", ex); }
  }
  public static org.worldgit.core.model.ChunkSnapshot filter(org.worldgit.core.model.ChunkSnapshot chunk, Set<UUID> touched) {
    var entities = chunk.entities().stream().filter(e -> touched.contains(e.uuid())).toList();
    return new org.worldgit.core.model.ChunkSnapshot(chunk.pos(), chunk.dataVersion(), chunk.sections(), chunk.biomes(), entities, chunk.ticks(), chunk.structures());
  }

  private static SortedSet<UUID> parse(Map<String, Object> map) throws IOException {
    var result = new TreeSet<UUID>(); if (map.isEmpty()) return result;
    if (!map.keySet().equals(Set.of("version", "uuids")) || !Integer.valueOf(1).equals(map.get("version"))
        || !(map.get("uuids") instanceof List<?> list) || list.size() > MAX_ENTITIES)
      throw new IOException("player-touched.yml 格式或數量無效");
    for (Object value : list) try { result.add(UUID.fromString((String)value)); }
      catch (RuntimeException ex) { throw new IOException("觸及實體 UUID 無效", ex); }
    return result;
  }
  public static byte[] bytes(Set<UUID> uuids) throws IOException {
    if (uuids.size() > MAX_ENTITIES) throw new IOException("玩家觸及實體上限 100000");
    StringBuilder text = new StringBuilder("version: 1\nuuids:");
    if (uuids.isEmpty()) text.append(" []\n");
    else { text.append('\n'); for (UUID id : new TreeSet<>(uuids)) text.append("- ").append(id).append('\n'); }
    return text.toString().getBytes(StandardCharsets.UTF_8);
  }
  public static void write(Path repo, Set<UUID> uuids) throws IOException {
    org.worldgit.core.anvil.RegionFile.atomicWrite(repo.resolve(FILE), bytes(uuids));
  }
  public static void collect(Nbt.Compound root, Set<UUID> uuids) {
    uuids.add(EntityNormalizer.uuid(root));
    for (Object passenger : root.list("Passengers").values()) collect((Nbt.Compound)passenger, uuids);
  }
  public static void touch(Path repo, Nbt.Compound root) throws IOException {
    var uuids = read(repo); collect(root, uuids); write(repo, uuids);
  }
  public static String reconcile(ObjectStore objects, String tree) throws IOException {
    return reconcile(objects, tree, List.of());
  }
  public static String reconcile(ObjectStore objects, String tree, Collection<String> histories) throws IOException {
    if (TreeEditor.find(objects, tree, FILE) == null) return tree;
    var uuids = new TreeSet<UUID>();
    for (var entity : new org.worldgit.core.diff.DiffEngine(objects).entities(tree).values()) collect(entity.entity().data(), uuids);
    // 被 ignore 排除但仍存在的觸及 UUID 不可因合併失去資格；歷史中曾有實體、結果已刪除的 UUID 才移除。
    var hidden = new TreeSet<UUID>(); var known = new TreeSet<UUID>();
    for (String history : histories) {
      var entry = TreeEditor.find(objects, history, FILE);
      if (entry != null) hidden.addAll(parse(objects.readBlob(entry.id())));
      for(var entity : new org.worldgit.core.diff.DiffEngine(objects).entities(history).values()) collect(entity.entity().data(),known);
    }
    hidden.removeAll(known); uuids.addAll(hidden);
    var editor = new TreeEditor(objects, tree); editor.putBlob(FILE, bytes(uuids)); return editor.write();
  }
  public static void restore(ObjectStore objects, String tree, Path repo) throws IOException {
    var entry = TreeEditor.find(objects, tree, FILE);
    if (entry == null) Files.deleteIfExists(repo.resolve(FILE));
    else { byte[] bytes = objects.readBlob(entry.id()); parse(bytes); org.worldgit.core.anvil.RegionFile.atomicWrite(repo.resolve(FILE), bytes); }
  }
}
