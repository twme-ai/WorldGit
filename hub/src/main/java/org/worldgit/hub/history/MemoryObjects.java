package org.worldgit.hub.history;

import java.io.IOException;
import java.util.*;
import org.eclipse.jgit.lib.*;
import org.worldgit.core.normalize.DecodeBudget;
import org.worldgit.core.store.ObjectStore;

/** 只讀 repo 上的候選物件層；Git hash 用 formatter 計算，從不取得 inserter。 */
final class MemoryObjects implements ObjectStore {
  static final long MAX_BYTES = 8L << 20;
  record Saved(Map<String, byte[]> blobs, Map<String, SortedMap<String, Entry>> trees, long bytes) {}
  private final ObjectStore source;
  private final Map<String, byte[]> blobs;
  private final Map<String, SortedMap<String, Entry>> trees;
  private long bytes;

  MemoryObjects(ObjectStore source) { this(source, new Saved(Map.of(), Map.of(), 0)); }
  MemoryObjects(ObjectStore source, Saved saved) {
    this.source = source;
    blobs = new HashMap<>(saved.blobs()); trees = new HashMap<>(saved.trees()); bytes = saved.bytes();
  }
  Saved saved() { return new Saved(Map.copyOf(blobs), Map.copyOf(trees), bytes); }
  private void reserve(long amount) throws IOException {
    DecodeBudget.decoded(amount); DecodeBudget.objects(1);
    if (bytes + amount > MAX_BYTES) throw new DecodeBudget.Exceeded("合併候選物件上限 8 MiB");
    bytes += amount;
  }
  @Override public String writeBlob(byte[] data) throws IOException {
    DecodeBudget.work(data.length);
    String id = new ObjectInserter.Formatter().idFor(Constants.OBJ_BLOB, data).name();
    if (!blobs.containsKey(id)) { reserve(data.length + 96L); blobs.put(id, data.clone()); }
    return id;
  }
  @Override public byte[] readBlob(String id) throws IOException {
    byte[] data = blobs.get(id);
    if (data == null) return source.readBlob(id);
    DecodeBudget.read(data.length); DecodeBudget.objects(1);
    return data.clone();
  }
  @Override public String writeTree(Collection<Entry> entries) throws IOException {
    var ordered = new ArrayList<>(entries);
    ordered.sort(Comparator.comparing(e -> e.name() + (e.kind() == Kind.TREE ? "/" : "")));
    var formatter = new TreeFormatter();
    var map = new TreeMap<String, Entry>();
    long size = 96;
    for (var e : ordered) {
      DecodeBudget.work(1); size += 160L + e.name().length() * 2L;
      if (map.put(e.name(), e) != null) throw new IllegalArgumentException("重複 tree entry");
      formatter.append(e.name(), e.kind() == Kind.TREE ? FileMode.TREE : FileMode.REGULAR_FILE, ObjectId.fromString(e.id()));
    }
    String id = formatter.computeId(new ObjectInserter.Formatter()).name();
    if (!trees.containsKey(id)) { reserve(size); trees.put(id, Collections.unmodifiableSortedMap(map)); }
    return id;
  }
  @Override public SortedMap<String, Entry> readTree(String id) throws IOException {
    var tree = trees.get(id);
    if (tree == null) return source.readTree(id);
    DecodeBudget.objects(1); DecodeBudget.work(tree.size());
    return tree;
  }
  @Override public void flush() {}
}
