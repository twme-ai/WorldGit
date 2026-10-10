package org.worldgit.core.merge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.anvil.SavedData;
import org.worldgit.core.config.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;
import org.worldgit.core.store.*;

/** 已正規化快照套用新增排除規則；不從沒有保存的歷史資料猜測被重新納入的內容。 */
public final class TreeFilter {
  private TreeFilter() {}

  /** 只讀取 metadata 子樹；即使 ignore 相同，也必須正規化舊歷史的 runtime。 */
  public static String runtime(ObjectStore store, String tree) throws IOException {
    if (tree == null) return null;
    var editor = new TreeEditor(store, tree);
    boolean changed = false;
    for (String name : List.of("world-meta", "dimension-meta")) {
      var metadata = store.readTree(tree).get(name);
      if (metadata == null || metadata.kind() != ObjectStore.Kind.TREE) continue;
      for (var entry : store.readTree(metadata.id()).values()) {
        if (entry.kind() != ObjectStore.Kind.BLOB || SavedData.relativePath(entry.name()) == null) continue;
        String path = name + "/" + entry.name();
        if (SavedData.transientEntry(entry.name())) { editor.remove(path); changed = true; continue; }
        byte[] raw = store.readBlob(entry.id()), normalized = SavedData.normalize(entry.name(), raw);
        if (!Arrays.equals(raw, normalized)) {
          if (normalized == null) editor.remove(path); else editor.putBlob(path, normalized);
          changed = true;
        }
      }
    }
    return changed ? editor.write() : tree;
  }

  public static String rules(ObjectStore store, String tree) throws IOException {
    var e = TreeEditor.find(store, tree, ".wgignore");
    return e == null ? "" : new String(store.readBlob(e.id()), StandardCharsets.UTF_8);
  }

  public static String filter(
      ObjectStore store, String tree, String ruleText, EntitySemantics semantics)
      throws IOException {
    tree = runtime(store, tree);
    if (rules(store, tree).equals(ruleText)) return tree;
    var rules = IgnoreRules.parse(ruleText);
    var editor = new TreeEditor(store, tree);
    for (var r : store.readTree(tree).values()) {
      if (r.name().startsWith("r.") && r.kind() == ObjectStore.Kind.TREE)
        for (var c : store.readTree(r.id()).values()) {
          String path = r.name() + "/" + c.name();
          String[] p = c.name().split("\\.");
          var pos = new ChunkPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
          for (var e : store.readTree(c.id()).values()) {
            String file = path + "/" + e.name();
            byte[] blob = store.readBlob(e.id());
            if (e.name().matches("s\\.-?\\d+\\.bin")) {
              int sy = Integer.parseInt(e.name().substring(2, e.name().length() - 4));
              var section = SnapshotCodec.section(blob);
              var blocks = new ArrayList<>(section.blocks());
              var bes = section.blockEntities();
              for (int i = 0; i < 4096; i++) {
                if (rules.ignoredBlock(
                    pos.x() * 16 + (i & 15), sy * 16 + (i >> 8), pos.z() * 16 + ((i >> 4) & 15))) {
                  blocks.set(i, BlockState.AIR);
                  bes.remove(i);
                } else if (bes.containsKey(i)) {
                  var be = Nbt.read(bes.get(i));
                  String type = be.string("id");
                  be.keySet().removeIf(k -> rules.ignoredField(type, k, false));
                  bes.put(i, Nbt.write(be));
                }
              }
              var s = new Section(blocks, bes);
              if (s.empty()) editor.remove(file);
              else editor.putBlob(file, SnapshotCodec.section(s));
            } else if (e.name().equals("entities.bin")) {
              var entities =
                  SnapshotCodec.entities(blob).stream()
                      .filter(v -> !rules.ignoredEntity(v.data(), semantics))
                      .map(v -> EntityNormalizer.normalize(v.data(), rules))
                      .toList();
              if (entities.isEmpty()) editor.remove(file);
              else editor.putBlob(file, SnapshotCodec.entities(entities));
            } else if (e.name().equals("biomes.bin")) {
              var biomes = SnapshotCodec.biomes(blob);
              var out = new TreeMap<Integer, List<String>>();
              for (var b : biomes.entrySet()) {
                var values = new ArrayList<>(b.getValue());
                for (int i = 0; i < 64; i++)
                  if (rules.ignoredBlock(
                      pos.x() * 16 + (i & 3) * 4,
                      b.getKey() * 16 + (i >> 4) * 4,
                      pos.z() * 16 + ((i >> 2) & 3) * 4)) values.set(i, "");
                if (values.stream().anyMatch(v -> !v.isEmpty())) out.put(b.getKey(), values);
              }
              if (out.isEmpty()) editor.remove(file);
              else editor.putBlob(file, SnapshotCodec.biomes(out));
            } else if (e.name().equals("ticks.bin")) {
              var ticks = Nbt.read(SnapshotCodec.nbt(4, blob, true));
              for (String key : List.of("block_ticks", "fluid_ticks")) {
                var values =
                    ticks.list(key).values().stream()
                        .filter(
                            v -> {
                              var t = (Nbt.Compound) v;
                              return !rules.ignoredBlock(
                                  t.integer("x", 0), t.integer("y", 0), t.integer("z", 0));
                            })
                        .toList();
                if (values.isEmpty()) ticks.remove(key);
                else ticks.put(key, new Nbt.ListTag(10, values));
              }
              if (ticks.isEmpty()) editor.remove(file);
              else editor.putBlob(file, SnapshotCodec.nbt(4, Nbt.write(ticks)));
            }
          }
        }
      if (Set.of("world-meta", "dimension-meta").contains(r.name())) {
        var files = new TreeMap<String, byte[]>();
        for (var e : store.readTree(r.id()).values()) files.put(e.name(), store.readBlob(e.id()));
        editor.replaceTree(r.name(), MetadataNormalizer.normalize(files, rules));
      }
    }
    editor.putBlob(".wgignore", ruleText.getBytes(StandardCharsets.UTF_8));
    return editor.write();
  }
}
