package org.worldgit.hub.history;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.worldgit.core.diff.ChangeKind;
import org.worldgit.core.diff.DiffEngine;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.core.model.DimensionId;
import org.worldgit.hub.account.Models.WorldRow;
import org.worldgit.hub.assets.AssetService;
import org.worldgit.hub.git.RepoCache;
import org.worldgit.hub.history.Dto.*;
import org.worldgit.hub.storage.RepoStorage;

/** 任意 a→b；直接使用 core SUMMARY，清單有跨維度總上限，未配對維度不推測成刪除（#H2）。 */
@Service
public class CompareService {
  public static final int MAX_CHUNKS = 2000;
  public static final int MAX_SECTIONS = 6000;
  private static final Pattern HEX = Pattern.compile("^[0-9a-fA-F]{4,40}$");
  private final RepoStorage storage;
  private final HistoryService history;
  private final AssetService assets;
  private final RepoCache repos;

  public CompareService(RepoStorage storage, HistoryService history, AssetService assets, RepoCache repos) {
    this.storage = storage;
    this.history = history;
    this.assets = assets;
    this.repos = repos;
  }

  record Resolved(String spec, String kind, Map<DimensionId, CommitInfo> commits) {}

  Resolved resolve(WorldRow w,String spec,DimensionId dim) throws IOException {
    if(spec==null || spec.isBlank() || spec.length()>100)throw new IllegalArgumentException("revision 無效");
    var c=spec.equals("HEAD")?history.find(w,dim,"HEAD"):history.branchHead(w,dim,spec);
    if(c.isEmpty())c=history.find(w,dim,spec);
    if(c.isEmpty())throw new NoSuchElementException("找不到維度 revision："+dim+" / "+spec);
    return new Resolved(spec,Refs.validBranch(spec) && history.branchHead(w,dim,spec).isPresent()?"branch":"commit",Map.of(dim,c.get()));
  }
  Resolved resolve(WorldRow w, String spec) throws IOException {
    if (spec == null || spec.isBlank() || spec.length() > 100) throw new IllegalArgumentException("比較的分支或 commit 無效");
    var existing = storage.dimensions(w.ownerSlug(), w.slug());
    BranchService.checkDimensions(existing.size());
    var found = new TreeMap<DimensionId, CommitInfo>();
    String branch = spec.equals("HEAD") ? history.defaultBranch(w, existing) : spec;
    for (DimensionId d : existing) (spec.equals("HEAD")?history.head(w,d):history.branchHead(w,d,branch)).ifPresent(c -> found.put(d,c));
    if (!found.isEmpty()) return new Resolved(spec, "branch", found);
    if (HEX.matcher(spec).matches()) {
      DimensionId source = null;
      CommitInfo commit = null;
      for (DimensionId d : existing) {
        var c = history.find(w, d, spec);
        if (c.isPresent()) {
          if (commit != null) throw new IllegalArgumentException("commit 前綴跨維度不唯一，請使用完整 id");
          source = d;
          commit = c.get();
        }
      }
      if (commit != null) {
        found.put(source, commit);

        return new Resolved(spec, "commit", found);
      }
    }
    throw new NoSuchElementException("找不到分支或 commit：" + spec);
  }

  private static RevInfo info(Resolved r) {
    CommitInfo lead = r.commits().getOrDefault(DimensionId.OVERWORLD, r.commits().values().iterator().next());
    long time = r.commits().values().stream().mapToLong(CommitInfo::time).max().orElse(0);
    var ids = new LinkedHashMap<String, String>();
    r.commits().forEach((d, c) -> ids.put(d.value(), c.id()));
    return new RevInfo(r.spec(), r.kind(), lead.snapshot(), time, lead.message(), lead.author(), ids);
  }

  public CompareResult compare(WorldRow w, String aSpec, String bSpec) throws IOException {
    return compareResolved(w,resolve(w,aSpec),resolve(w,bSpec));
  }
  public CompareResult compare(WorldRow w,String a,String b,DimensionId d) throws IOException {
    return compareResolved(w,resolve(w,a,d),resolve(w,b,d));
  }
  private CompareResult compareResolved(WorldRow w,Resolved a,Resolved b) throws IOException {
    var dims = new TreeSet<DimensionId>(a.commits().keySet());
    dims.addAll(b.commits().keySet());
    var out = new ArrayList<CompareDimension>();
    long added = 0, removed = 0, modified = 0;
    int chunks = 0, chunkSlots = MAX_CHUNKS, sectionSlots = MAX_SECTIONS;
    boolean identical = true;
    for (DimensionId d : dims) {
      CommitInfo ca = a.commits().get(d), cb = b.commits().get(d);
      CompareDimension row;
      if (ca == null || cb == null) row = empty(d, ca == null ? "only-b" : "only-a", ca, cb);
      else if (ca.tree().equals(cb.tree())) row = empty(d, "same", ca, cb);
      else row = diff(w, d, ca, cb, chunkSlots, sectionSlots);
      chunkSlots -= row.changedChunks().size();
      sectionSlots -= row.changedSections().size();
      identical &= row.status().equals("same");
      added += row.added(); removed += row.removed(); modified += row.modified();
      chunks += row.chunkCount();
      out.add(row);
    }
    return new CompareResult(info(a), info(b), out, added, removed, modified, chunks, identical);
  }

  private String version(CommitInfo c) { return c == null ? null : assets.versionFor(c.dataVersion()).id(); }

  private CompareDimension empty(DimensionId d, String status, CommitInfo a, CommitInfo b) {
    return new CompareDimension(d.value(), d.directoryName(), status, a, b, 0, 0, 0, 0, 0, 0, 0, 0,
        List.of(), List.of(), false, List.of(), false, new int[] {0, 0, 0, 0}, List.of(), version(b), version(a));
  }

  private CompareDimension diff(WorldRow w, DimensionId d, CommitInfo a, CommitInfo b, int chunkSlots, int sectionSlots) throws IOException {
    try (var h = repos.open(w.ownerSlug(), w.slug(), d)) {
      var diff = new DiffEngine(h.store()).compare(d, a.tree(), b.tree(), HistoryService.TOLERANCE, DiffEngine.Detail.SUMMARY);
      var perChunk = new TreeMap<ChunkPos, int[]>();
      var sections = new ArrayList<int[]>();
      for (var s : diff.sections()) {
        var v = perChunk.computeIfAbsent(s.chunk(), k -> new int[6]);
        v[2] += (int) s.counts().added(); v[3] += (int) s.counts().removed(); v[4] += (int) s.counts().modified();
        if (sections.size() < sectionSlots) sections.add(new int[] {s.chunk().x(), s.chunk().z(), s.sectionY(),
            (int) s.counts().added(), (int) s.counts().removed(), (int) s.counts().modified()});
      }
      for (var bc : diff.biomes()) perChunk.computeIfAbsent(bc.chunk(), k -> new int[6])[5] |= 2;
      int ea = 0, er = 0, em = 0;
      var entities = new ArrayList<EntityChangeInfo>();
      for (var e : diff.entities()) {
        if (e.kind() == ChangeKind.ADDED) ea++; else if (e.kind() == ChangeKind.REMOVED) er++; else em++;
        if (e.beforeChunk() != null) perChunk.computeIfAbsent(e.beforeChunk(), k -> new int[6])[5] |= 1;
        if (e.afterChunk() != null) perChunk.computeIfAbsent(e.afterChunk(), k -> new int[6])[5] |= 1;
        var snap = e.after() != null ? e.after() : e.before();
        if (entities.size() < 200) entities.add(new EntityChangeInfo(e.uuid().toString(), e.kind().name().toLowerCase(Locale.ROOT),
            snap.data().string("id"), e.before() == null ? null : e.before().position(), e.after() == null ? null : e.after().position()));
      }
      // chunk metadata（例如 heightmaps／structures）也算變動的 chunk。
      for (var c : diff.chunks()) perChunk.computeIfAbsent(c, k -> new int[6]);
      var chunks = new ArrayList<int[]>();
      int[] bounds = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
      for (var en : perChunk.entrySet()) {
        int x = en.getKey().x(), z = en.getKey().z();
        if (chunks.size() < chunkSlots) {
          int[] v = en.getValue();
          chunks.add(new int[] {x, z, v[2], v[3], v[4], v[5]});
        }
        bounds[0] = Math.min(bounds[0], x); bounds[1] = Math.min(bounds[1], z);
        bounds[2] = Math.max(bounds[2], x); bounds[3] = Math.max(bounds[3], z);
      }
      var c = diff.counts();
      return new CompareDimension(d.value(), d.directoryName(), diff.empty() ? "same" : "changed", a, b, c.added(), c.removed(), c.modified(),
          perChunk.size(), diff.sections().size(), ea, er, em, entities, chunks, perChunk.size() > chunks.size(), sections,
          diff.sections().size() > sections.size(), perChunk.isEmpty() ? new int[] {0, 0, 0, 0} : bounds,
          diff.metadata().stream().map(m -> m.kind().name().toLowerCase(Locale.ROOT) + " " + m.path()).limit(50).toList(), version(b), version(a));
    }
  }
}
