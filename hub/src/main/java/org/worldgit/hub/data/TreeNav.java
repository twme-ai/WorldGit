package org.worldgit.hub.data;

import java.io.IOException;
import java.util.*;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.core.store.ObjectStore;

/** 世界 tree（r.X.Z / c.X.Z / 檔案）的導覽輔助；一次請求內快取 region tree。 */
public final class TreeNav {
  private final ObjectStore store;
  private final String root;
  private final Map<String, SortedMap<String, ObjectStore.Entry>> regionCache = new HashMap<>();
  private SortedMap<String, ObjectStore.Entry> rootEntries;

  TreeNav(ObjectStore store, String rootTree) {
    this.store = store;
    this.root = rootTree;
  }

  SortedMap<String, ObjectStore.Entry> root() throws IOException {
    if (rootEntries == null) rootEntries = store.readTree(root);
    return rootEntries;
  }

  /** root 底下所有 region：名稱 r.X.Z → tree id。 */
  Map<String, String> regions() throws IOException {
    var out = new TreeMap<String, String>();
    for (var e : root().values()) if (e.kind() == ObjectStore.Kind.TREE && e.name().startsWith("r.")) { regionCoords(e.name()); out.put(e.name(), e.id()); }
    return out;
  }

  SortedMap<String, ObjectStore.Entry> region(String regionName) throws IOException {
    var e = root().get(regionName);
    if (e == null || e.kind() != ObjectStore.Kind.TREE) return null;
    var cached = regionCache.get(e.id());
    if (cached == null) regionCache.put(e.id(), cached = store.readTree(e.id()));
    return cached;
  }

  /** chunk 的檔案（s.Y.bin、biomes.bin、entities.bin…）；不存在回傳 null。 */
  SortedMap<String, ObjectStore.Entry> chunk(ChunkPos pos) throws IOException {
    var region = region(pos.regionName());
    if (region == null) return null;
    var e = region.get(pos.chunkName());
    return e == null || e.kind() != ObjectStore.Kind.TREE ? null : store.readTree(e.id());
  }

  static int[] regionCoords(String name) {
    return coords(name, "r");
  }

  public static int[] coords(String name, String prefix) {
    String[] p = name.split("\\.");
    try {
      if (p.length != 3 || !p[0].equals(prefix)) throw new IllegalArgumentException();
      return new int[] {Integer.parseInt(p[1]), Integer.parseInt(p[2])};
    } catch (IllegalArgumentException e) { throw new IllegalArgumentException("世界 tree 座標無效"); }
  }
}
