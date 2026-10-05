package org.worldgit.hub.history;

import java.io.IOException;
import java.util.*;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.stereotype.Service;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.normalize.DecodeBudget;
import org.worldgit.hub.account.Models.WorldRow;
import org.worldgit.hub.git.RepoCache;
import org.worldgit.hub.history.Dto.*;
import org.worldgit.hub.storage.RepoStorage;

/** 同名分支跨維度合併；ahead／behind 先合併可達 snapshot 集合，再取差集（#H1）。 */
@Service
public class BranchService {
  static final int COUNT_LIMIT = 20_000;
  public static final int MAX_DIMENSIONS = 32;
  private final RepoStorage storage;
  private final RepoCache repos;
  private final HistoryService history;

  public BranchService(RepoStorage storage, RepoCache repos, HistoryService history) {
    this.storage = storage;
    this.repos = repos;
    this.history = history;
  }

  private record DimRefs(DimensionId dim, SortedMap<String, ObjectId> branches, String headBranch) {}
  private record Reach(Set<String> snapshots, boolean truncated) {}

  public BranchPage list(WorldRow w,String base,DimensionId dimension) throws IOException {
    return listSelected(w,base,dimension);
  }
  public BranchPage list(WorldRow w, String base) throws IOException {return listSelected(w,base,null);}
  private BranchPage listSelected(WorldRow w,String base,DimensionId dimension) throws IOException {
    var existing = storage.dimensions(w.ownerSlug(), w.slug()).stream().filter(d->dimension==null || d.equals(dimension)).toList();
    checkDimensions(existing.size());
    var declared = existing.stream().map(DimensionId::value).toList();
    checkDimensions(declared.size());
    var refs = new ArrayList<DimRefs>();
    var names = new TreeSet<String>();
    for (DimensionId d : existing) {
      try (var h = repos.open(w.ownerSlug(), w.slug(), d)) {
        var branches = Refs.branches(h.repository());
        refs.add(new DimRefs(d, branches, Refs.headBranch(h.repository())));
        names.addAll(branches.keySet());
        if (names.size() > Refs.MAX_BRANCHES) throw new DecodeBudget.Exceeded("分支數上限 " + Refs.MAX_BRANCHES);
      }
    }
    var headsByDim = new TreeMap<DimensionId, String>();
    refs.forEach(r -> headsByDim.put(r.dim(), r.headBranch()));
    String def = Refs.defaultBranch(headsByDim, names);
    String against = base == null || base.isBlank() ? def : base;
    if (base != null && !base.isBlank() && !names.contains(base)) throw new NoSuchElementException("找不到分支 " + base);
    var reaches = new HashMap<String, Reach>();
    // 同一 tip 的多個分支共用走訪結果；只有此次請求保留，避免跨 ref 更新的快取失效問題。
    var tips = new HashMap<String, Refs.Counts>();
    for (String name : names) {
      var snaps = new HashSet<String>();
      boolean truncated = false;
      for (var r : refs) {
        var id = r.branches().get(name);
        if (id == null) continue;
        String key = r.dim() + "/" + id.name();
        var count = tips.get(key);
        if (count == null) {
          try (var h = repos.open(w.ownerSlug(), w.slug(), r.dim())) {
            count = Refs.reachable(h.repository(), id, COUNT_LIMIT);
          }
          tips.put(key, count);
        }
        for(String commitId:count.only())snaps.add(r.dim()+"/"+commitId);
        truncated |= count.truncated();
      }
      reaches.put(name, new Reach(snaps, truncated));
    }
    var rows = new ArrayList<BranchRow>();
    for (String name : names) {
      var heads = new LinkedHashMap<String, BranchHead>();
      CommitInfo lead = null;
      for (var r : refs) {
        ObjectId tip = r.branches().get(name);
        if (tip == null) continue;
        try (var h = repos.open(w.ownerSlug(), w.slug(), r.dim()); var walk = new RevWalk(h.repository())) {
          var c = HistoryService.info(Refs.commit(walk.parseCommit(tip)));
          heads.put(r.dim().value(), new BranchHead(c.id(), c.time(), c.snapshot(), c.auto(), c.message(), c.author()));
          if (lead == null || c.time() > lead.time() || (c.time() == lead.time() && r.dim().equals(DimensionId.OVERWORLD))) lead = c;
        }
      }
      var missing = declared.stream().filter(d -> !heads.containsKey(d)).toList();
      var reach = reaches.get(name);
      var target = reaches.get(against);
      boolean compared = !name.equals(against) && target != null;
      Integer ahead = compared ? difference(reach.snapshots(), target.snapshots()) : null;
      Integer behind = compared ? difference(target.snapshots(), reach.snapshots()) : null;
      boolean aligned = heads.values().stream().map(BranchHead::snapshot).distinct().count() <= 1;
      rows.add(new BranchRow(name, name.equals(def), heads, missing.isEmpty(), aligned, missing, lead.time(),
          lead.message(), lead.author(), lead.snapshot(), ahead, behind, compared && (reach.truncated() || target.truncated())));
    }
    rows.sort(Comparator.comparing((BranchRow r) -> !r.isDefault())
        .thenComparing(Comparator.comparingLong(BranchRow::time).reversed()).thenComparing(BranchRow::name));
    return new BranchPage(def, against, rows, declared);
  }

  public BranchPage list(WorldRow w) throws IOException { return list(w, null); }

  public static void checkDimensions(int size) throws DecodeBudget.Exceeded {
    if (size > MAX_DIMENSIONS) throw new DecodeBudget.Exceeded("維度數上限 " + MAX_DIMENSIONS);
  }

  private static int difference(Set<String> a, Set<String> b) {
    int count = 0;
    for (String s : a) if (!b.contains(s)) count++;
    return count;
  }

}
