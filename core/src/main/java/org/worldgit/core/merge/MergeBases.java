package org.worldgit.core.merge;

import java.io.IOException;
import java.util.*;
import org.worldgit.core.store.RefStore;

/** 所有最佳共同祖先；無共同歷史以空 tree 為 base，criss-cross 明確拒絕。 */
public final class MergeBases {
  private MergeBases() {}

  public static List<String> best(RefStore refs, String ours, String theirs) throws IOException {
    var a = ancestors(refs, ours);
    var b = ancestors(refs, theirs);
    var common = new TreeSet<>(a.keySet());
    common.retainAll(b.keySet());
    var dominated = new HashSet<String>();
    for (String id : common)
      for (String parent : a.get(id).parents()) {
        var queue = new ArrayDeque<String>();
        queue.add(parent);
        while (!queue.isEmpty()) {
          String p = queue.remove();
          if (!dominated.add(p)) continue;
          queue.addAll(a.get(p).parents());
        }
      }
    common.removeAll(dominated);
    return List.copyOf(common);
  }

  public static String unique(RefStore refs, String ours, String theirs) throws IOException {
    var bases = best(refs, ours, theirs);
    if (bases.size() > 1)
      throw new IOException("criss-cross 有多個 merge-base；請先建立明確的共同整合基底：" + bases);
    return bases.isEmpty() ? null : bases.getFirst();
  }

  private static Map<String, RefStore.Commit> ancestors(RefStore refs, String start)
      throws IOException {
    var result = new HashMap<String, RefStore.Commit>();
    var queue = new ArrayDeque<String>();
    if (start != null) queue.add(start);
    while (!queue.isEmpty()) {
      String id = queue.remove();
      if (result.containsKey(id)) continue;
      if (result.size() >= 200_000) throw new IOException("merge-base 歷史超過 200000 commits");
      var c = refs.readCommit(id);
      result.put(id, c);
      queue.addAll(c.parents());
    }
    return result;
  }
}
