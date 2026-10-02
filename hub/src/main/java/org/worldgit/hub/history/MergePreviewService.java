package org.worldgit.hub.history;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.apply.BlockBox;
import org.worldgit.core.config.EntitySemantics;
import org.worldgit.core.merge.*;
import org.worldgit.core.merge.MergeReport.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;
import org.worldgit.core.store.*;
import org.worldgit.hub.account.Models.WorldRow;
import org.worldgit.hub.assets.AssetService;
import org.worldgit.hub.data.MergeViewData;
import org.worldgit.hub.data.DataService.Window;
import org.worldgit.hub.git.RepoCache;
import org.worldgit.hub.storage.RepoStorage;

/** Phase 3 唯讀合併：候選 objects 與快取只存在記憶體，不使用 WorldOperations。 */
@Service
public class MergePreviewService {
  public static final int MAX_REGIONS = 2000, MAX_ATOMS = 100_000, MAX_CACHE_ENTRIES = 8;
  public static final long MAX_CACHE_BYTES = 32L << 20;
  public record Problem(String dimension, String code, String reason) {}
  public record RegionInfo(int id, String dimension, BlockBox bounds, int blockCount,
      List<String> oursAuthors, List<String> theirsAuthors, boolean redstone, int boundaryHints,
      Map<String, Long> kinds) {}
  public record DimensionInfo(String dimension, String repo, String ours, String theirs, String base,
      String status, String mcVersion, int automaticallyMergedSections, int boundaryHints, int[] bounds) {}
  public record Report(String fingerprint, boolean canMerge, boolean zeroIntervention,
      int automaticallyMergedSections, List<RegionInfo> regions, List<DimensionInfo> dimensions,
      List<RuleDifference> ruleDifferences, List<Problem> problems, List<String> warnings) {}
  private record Candidate(DimensionId dim, String base, String ours, String theirs, String result,
      MergeReport report, MemoryObjects.Saved objects) {}
  private record Cached(Report report, Map<DimensionId, Candidate> candidates, long bytes) {}
  private final CompareService compare;
  private final HistoryService history;
  private final RepoStorage storage;
  private final RepoCache repos;
  private final AssetService assets;
  private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(16, .75f, true);
  private long cacheBytes;

  public MergePreviewService(CompareService compare, HistoryService history, RepoStorage storage, RepoCache repos, AssetService assets) {
    this.compare = compare; this.history = history; this.storage = storage; this.repos = repos; this.assets = assets;
  }

  /** commit 的 group ref 優先；舊歷史按入口 first-parent 的 snapshot 回溯，與 core 相同。 */
  private CompareService.Resolved resolve(WorldRow w, String spec) throws IOException {
    var r = compare.resolve(w, spec);
    if (!r.kind().equals("commit")) return r;
    var lead = r.commits().values().stream().filter(c -> c.id().startsWith(spec.toLowerCase(Locale.ROOT))).findFirst().orElseThrow();
    var found = new TreeMap<DimensionId, Dto.CommitInfo>();
    for (var dim : storage.dimensions(w.ownerSlug(), w.slug())) {
      try (var h = repos.open(w.ownerSlug(), w.slug(), dim)) {
        var ref = h.repository().exactRef("refs/worldgit/groups/" + lead.snapshot());
        if (ref != null && ref.getObjectId() != null) { found.put(dim, HistoryService.info(h.store().readCommit(ref.getObjectId().name()))); continue; }
      }
      String cursor = lead.id();
      for (int n = 0; cursor != null; n++) {
        if (n >= 20_000) throw new DecodeBudget.Exceeded("snapshot 配對歷史上限 20000");
        var c = history.find(w, new DimensionId(lead.dimension()), cursor).orElseThrow();
        var match = history.commitForSnapshot(w, dim, c.snapshot());
        if (match.isPresent()) { found.put(dim, match.get()); break; }
        cursor = c.parents().isEmpty() ? null : c.parents().getFirst();
      }
      if (!found.containsKey(dim)) throw new IllegalArgumentException("無法配對 snapshot 的維度 " + dim + "；請使用同步分支");
    }
    // 入口本身以指定 commit 為準。
    found.put(new DimensionId(lead.dimension()), lead);
    return new CompareService.Resolved(spec, r.kind(), found);
  }

  private synchronized Cached compute(WorldRow w, String oursSpec, String theirsSpec) throws IOException {
    var ours = resolve(w, oursSpec); var theirs = resolve(w, theirsSpec);
    StringBuilder signature = new StringBuilder(w.id());
    for (var side : List.of(ours, theirs)) {
      signature.append('|'); side.commits().forEach((d,c) -> signature.append(d).append('=').append(c.id()).append(';'));
    }
    String key = new org.eclipse.jgit.lib.ObjectInserter.Formatter().idFor(org.eclipse.jgit.lib.Constants.OBJ_BLOB,
        signature.toString().getBytes(StandardCharsets.UTF_8)).name();
    var hit = cache.get(key);
    if (hit != null) return hit;
    var regions = new ArrayList<RegionInfo>(); var dims = new ArrayList<DimensionInfo>();
    var problems = new ArrayList<Problem>(); var differences = new ArrayList<RuleDifference>();
    var warnings = new LinkedHashSet<String>(); var candidates = new TreeMap<DimensionId, Candidate>();
    var allDims = new TreeSet<>(ours.commits().keySet()); allDims.addAll(theirs.commits().keySet());
    int automatic = 0, nextId = 1, atoms = 0; long bytes = 0;
    for (var dim : allDims) {
      var oc = ours.commits().get(dim); var tc = theirs.commits().get(dim);
      if (oc == null) { problems.add(new Problem(dim.value(), "missing-ours", "目前端缺少維度，Hub 無法建立來源新增的維度")); continue; }
      try (var h = repos.open(w.ownerSlug(), w.slug(), dim)) {
        var store = new MemoryObjects(h.store());
        String empty = store.writeTree(List.of());
        String baseId;
        try { baseId = tc == null ? oc.id() : MergeBases.unique(h.store(), oc.id(), tc.id()); }
        catch (DecodeBudget.Exceeded e) { throw e; }
        catch (IOException e) { problems.add(new Problem(dim.value(), "merge-base", e.getMessage())); continue; }
        var bc = baseId == null ? null : h.store().readCommit(baseId);
        if ((tc != null && oc.dataVersion() != tc.dataVersion()) || (bc != null && bc.metadata().mcDataVersion() != oc.dataVersion())) {
          problems.add(new Problem(dim.value(), "data-version", "DataVersion 不同（base=" + (bc == null ? "空" : bc.metadata().mcDataVersion()) + ", ours=" + oc.dataVersion() + ", theirs=" + (tc == null ? "缺少" : tc.dataVersion()) + "）；請先在世界複本升級並重新提交")); continue;
        }
        String rawBase = bc == null ? empty : bc.tree(), rawTheirs = tc == null ? oc.tree() : tc.tree();
        if (!Objects.equals(packs(store, oc.tree()), packs(store, rawTheirs)) || (bc != null && !Objects.equals(packs(store, oc.tree()), packs(store, rawBase)))) {
          problems.add(new Problem(dim.value(), "data-packs", "DataPacks 清單不同，需先整合資料包後重試")); continue;
        }
        String br = TreeFilter.rules(store, rawBase), or = TreeFilter.rules(store, oc.tree()), tr = TreeFilter.rules(store, rawTheirs), rules;
        try { rules = IgnoreRuleMerge.merge(br, or, tr); }
        catch (IOException e) {
          differences.add(new RuleDifference(dim, br, or, tr, null));
          problems.add(new Problem(dim.value(), "rules", e.getMessage())); continue;
        }
        if (!or.equals(tr) || !br.equals(or)) differences.add(new RuleDifference(dim, br, or, tr, rules));
        String bt = TreeFilter.filter(store, rawBase, rules, EntitySemantics.OFFLINE),
            ot = TreeFilter.filter(store, oc.tree(), rules, EntitySemantics.OFFLINE),
            tt = TreeFilter.filter(store, rawTheirs, rules, EntitySemantics.OFFLINE);
        var merged = new MergeEngine(store, dim, bt, ot, tt, authors(h.store().readCommit(oc.id())), tc == null ? List.of() : authors(h.store().readCommit(tc.id()))).merge(1);
        var numbered = new ArrayList<Region>();
        for (var r : merged.report().regions()) {
          atoms += r.atoms().size();
          if (atoms > MAX_ATOMS || regions.size() >= MAX_REGIONS) throw new DecodeBudget.Exceeded("衝突區域／atoms 上限 2000／100000");
          var n = new Region(nextId++, dim, r.bounds(), r.blockCount(), r.oursAuthors(), r.theirsAuthors(), r.redstone(), r.choice(), false, r.atoms());
          numbered.add(n);
          var kinds = new TreeMap<String, Long>(); n.atoms().forEach(a -> kinds.merge(a.kind().name().toLowerCase(Locale.ROOT), 1L, Long::sum));
          regions.add(new RegionInfo(n.id(), dim.value(), n.bounds(), n.blockCount(), n.oursAuthors(), n.theirsAuthors(), n.redstone(), hints(n.bounds(), merged.report().updateShapes()), kinds));
        }
        var report = new MergeReport(merged.report().automaticallyMergedSections(), numbered, List.of(), merged.report().updateShapes(), merged.report().warnings());
        var bounds = bounds(store, merged.tree());
        automatic += report.automaticallyMergedSections();
        dims.add(new DimensionInfo(dim.value(), dim.directoryName(), oc.id(), tc == null ? null : tc.id(), baseId,
            tc == null ? "keep-ours" : baseId == null ? "empty-base" : "merged", assets.versionFor(oc.dataVersion()).id(), report.automaticallyMergedSections(), report.updateShapes().size(), bounds));
        // core 的舊 warning 文字仍提 updateShape；Hub 僅呈現 #46 的提示語意。
        if (!report.updateShapes().isEmpty()) warnings.add("交界提示僅供檢查，維持快照 state，不自動更新鄰居形狀。");
        if (report.warnings().stream().anyMatch(s -> s.contains("紅石"))) warnings.add("含紅石元件，建議在遊戲內測試。");
        bytes += store.saved().bytes() + 256L + numbered.stream().mapToLong(r -> 256L + r.atoms().size() * 256L).sum();
        if (bytes > MAX_CACHE_BYTES) throw new DecodeBudget.Exceeded("合併預覽記憶體上限 32 MiB");
        candidates.put(dim, new Candidate(dim, bt, ot, tt, merged.tree(), report, store.saved()));
      }
    }
    warnings.add("歷史未追蹤的內容無法重新取回；Hub 只預覽已保存的快照。作者為來源提交摘要。");
    var report = new Report(key, problems.isEmpty(), problems.isEmpty() && regions.isEmpty(), automatic,
        List.copyOf(regions), List.copyOf(dims), List.copyOf(differences), List.copyOf(problems), List.copyOf(warnings));
    bytes += 1024L + differences.stream().mapToLong(r -> (r.base().length() + r.ours().length() + r.theirs().length() + (r.merged() == null ? 0 : r.merged().length())) * 2L).sum();
    if (bytes > MAX_CACHE_BYTES) throw new DecodeBudget.Exceeded("合併預覽記憶體上限 32 MiB");
    var result = new Cached(report, Map.copyOf(candidates), bytes);
    while (!cache.isEmpty() && (cache.size() >= MAX_CACHE_ENTRIES || cacheBytes + bytes > MAX_CACHE_BYTES)) {
      var iterator = cache.entrySet().iterator(); var old = iterator.next(); cacheBytes -= old.getValue().bytes(); iterator.remove();
    }
    cache.put(key, result); cacheBytes += bytes;
    return result;
  }

  public Report report(WorldRow w, String ours, String theirs) throws IOException { return compute(w, ours, theirs).report(); }
  public synchronized int cacheEntries() { return cache.size(); }
  public synchronized long cacheBytes() { return cacheBytes; }
  public synchronized void clearCache() { cache.clear(); cacheBytes = 0; }

  public Object view(WorldRow w, String ours, String theirs, String fingerprint, DimensionId dim, String view,
      String choicesText, Window window, String kind) throws IOException {
    var cached = compute(w, ours, theirs);
    if (!cached.report().fingerprint().equals(fingerprint)) throw new IllegalArgumentException("分支 tip 已改變，請重新載入合併預覽；舊選擇不套到新快照");
    if (!cached.report().canMerge()) throw new IllegalArgumentException("此合併有前提衝突，請先處理報告原因");
    var candidate = cached.candidates().get(dim);
    if (candidate == null) throw new NoSuchElementException("沒有此維度的合併預覽");
    var choices = choices(choicesText, cached.report().regions());
    try (var h = repos.open(w.ownerSlug(), w.slug(), dim)) {
      var store = new MemoryObjects(h.store(), candidate.objects());
      String tree = candidate.result();
      if (view.equals("selected") || Set.of("ours", "theirs", "base").contains(view)) {
        for (var r : candidate.report().regions()) {
          String choice = view.equals("selected") ? choices.getOrDefault(r.id(), "manual") : view;
          String source = switch (choice) { case "theirs" -> candidate.theirs(); case "base" -> candidate.base(); default -> candidate.ours(); };
          tree = MergeEngine.select(store, tree, source, r);
        }
      } else if (!view.equals("auto")) throw new IllegalArgumentException("預覽模式無效");
      return MergeViewData.build(store, dim, candidate.base(), tree, candidate.report().regions(), window, kind,
          kind.equals("summary") ? MergeEngine.updateShapes(store, dim, candidate.base(), candidate.ours(), candidate.theirs(), tree) : List.of());
    }
  }
  private static Map<Integer, String> choices(String text, List<RegionInfo> regions) {
    if (text == null || text.isBlank()) return Map.of();
    if (text.length() > 32_000) throw new IllegalArgumentException("選擇清單過長");
    var ids = new HashSet<Integer>(); regions.forEach(r -> ids.add(r.id()));
    var out = new TreeMap<Integer, String>();
    for (String item : text.split(",")) {
      String[] pair = item.split(":", -1);
      if (pair.length != 2 || !Set.of("ours", "theirs", "base", "manual").contains(pair[1])) throw new IllegalArgumentException("區域選擇無效");
      int id = Integer.parseInt(pair[0]);
      if (!ids.contains(id) || out.put(id, pair[1]) != null) throw new IllegalArgumentException("未知或重複區域 id");
    }
    return out;
  }
  private static List<String> authors(RefStore.Commit c) {
    var out = new TreeSet<String>(); out.add(c.metadata().author().git()); c.metadata().contributions().forEach(a -> out.add(a.author().git())); return List.copyOf(out);
  }
  private static String packs(ObjectStore s, String tree) throws IOException {
    var e = TreeEditor.find(s, tree, "world-meta/level.nbt");
    if (e == null) return null;
    var n = Nbt.read(s.readBlob(e.id())); var d = n.get("Data") instanceof Nbt.Compound c ? c : n;
    if (d.get("DataPacks") == null) return null;
    var out = new Nbt.Compound(); out.put("DataPacks", d.get("DataPacks"));
    return Base64.getEncoder().encodeToString(Nbt.write(out));
  }
  private static int hints(BlockBox b, List<Cell> cells) {
    if (b == null) return 0;
    return (int) cells.stream().filter(c -> (long)c.x() >= (long)b.minX()-1 && (long)c.x() <= (long)b.maxX()+1 && (long)c.y() >= (long)b.minY()-1 && (long)c.y() <= (long)b.maxY()+1 && (long)c.z() >= (long)b.minZ()-1 && (long)c.z() <= (long)b.maxZ()+1).count();
  }
  private static int[] bounds(ObjectStore s, String tree) throws IOException {
    int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
    for (var r : s.readTree(tree).values()) if (r.kind() == ObjectStore.Kind.TREE && r.name().startsWith("r."))
      for (var c : s.readTree(r.id()).values()) {
        DecodeBudget.work(1); String[] p = c.name().split("\\."); int x = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
        b[0]=Math.min(b[0],x); b[1]=Math.min(b[1],z); b[2]=Math.max(b[2],x); b[3]=Math.max(b[3],z);
      }
    return b[0] == Integer.MAX_VALUE ? new int[]{0,0,0,0} : b;
  }
}
