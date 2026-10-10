package org.worldgit.hub.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.stereotype.Service;
import org.worldgit.core.diff.ChangeKind;
import org.worldgit.core.diff.DiffEngine;
import org.worldgit.core.diff.WorldDiff;
import org.worldgit.core.model.CommitMetadata;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.store.ObjectStore;
import org.worldgit.core.store.RefStore;
import org.worldgit.hub.account.AccountService;
import org.worldgit.hub.account.Models.WorldRow;
import org.worldgit.hub.git.RepoCache;
import org.worldgit.hub.history.Dto.*;
import org.worldgit.hub.storage.RepoStorage;

/**
 * 世界的歷史：每個維度 repo 的 commit 依 WorldGit-Snapshot trailer 合成「一次存檔一列」，
 * 並判斷是否為部分推送（決定 #18）。重負載結果（diff 統計）以內容定址快取。
 */
@Service
public class HistoryService {
  public static final double TOLERANCE = 2.0;
  private static final int MAX_HISTORY = 20_000;
  private static final Pattern HEX = Pattern.compile("^[0-9a-fA-F]{4,40}$");
  private static final Pattern MANIFEST = Pattern.compile("^\\s*'([^']+)'\\s*:", Pattern.MULTILINE);

  private final RepoStorage storage;
  private final RepoCache repos;
  private final AccountService accounts;
  private final ObjectMapper json;
  private final Map<String, List<CommitInfo>> logCache =
      Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<CommitInfo>> e) {
          return size() > 64;
        }
      });
  private final Map<String, CommitDetail> detailCache =
      Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CommitDetail> e) {
          return size() > 256;
        }
      });

  private final Map<String, Map<String, String>> snapshotCache =
      Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Map<String, String>> e) {
          return size() > 32;
        }
      });

  public HistoryService(RepoStorage storage, RepoCache repos, AccountService accounts, ObjectMapper json) {
    this.storage = storage;
    this.repos = repos;
    this.accounts = accounts;
    this.json = json;
  }

  public static CommitInfo info(RefStore.Commit c) {
    CommitMetadata m = c.metadata();
    var co = m.contributions().stream().map(x -> x.author().name()).distinct().toList();
    return new CommitInfo(
        c.id(), c.parents(), c.tree(), m.time().toEpochMilli(),
        new Person(m.author().name(), m.author().email()),
        new Person(m.committer().name(), m.committer().email()),
        m.message(), m.auto(), m.source().name().toLowerCase(Locale.ROOT), m.mcDataVersion(),
        m.dimension().value(), m.snapshot().toString(), co);
  }

  /** 該維度完整歷史（新到舊）；以 head id 快取。 */
  public List<CommitInfo> log(WorldRow w, DimensionId dim) throws IOException {
    try (var h = repos.open(w.ownerSlug(), w.slug(), dim)) {
      String head = h.store().head();
      if (head == null) return List.of();
      String key = w.id() + "/" + dim + "/" + head;
      List<CommitInfo> cached = logCache.get(key);
      if (cached != null) return cached;
      var list = h.store().log(MAX_HISTORY).stream().map(HistoryService::info).toList();
      logCache.put(key, list);
      return list;
    }
  }

  /** 指定分支的歷史（新到舊）；branch 為 null 時等同 {@link #log(WorldRow, DimensionId)}（HEAD）。沒有該分支回傳空清單。 */
  public List<CommitInfo> log(WorldRow w, DimensionId dim, String branch) throws IOException {
    if (branch == null) return log(w, dim);
    try (var h = repos.open(w.ownerSlug(), w.slug(), dim)) {
      ObjectId tip = Refs.tip(h.repository(), branch);
      if (tip == null) return List.of();
      String key = w.id() + "/" + dim + "/" + branch + "/" + tip.name();
      List<CommitInfo> cached = logCache.get(key);
      if (cached != null) return cached;
      var list = Refs.log(h.repository(), tip, MAX_HISTORY).stream().map(HistoryService::info).toList();
      logCache.put(key, list);
      return list;
    }
  }

  public Optional<CommitInfo> head(WorldRow w,DimensionId d)throws IOException {
    if(!storage.exists(w.ownerSlug(),w.slug(),d))return Optional.empty();
    try(var h=repos.open(w.ownerSlug(),w.slug(),d)){String id=h.store().head();return id==null?Optional.empty():Optional.of(info(h.store().readCommit(id)));}
  }

  /** 分支 head 的 commit（沒有該分支或維度 repo 不存在時為空）。 */
  public Optional<CommitInfo> branchHead(WorldRow w, DimensionId dim, String branch) throws IOException {
    if (!Refs.validBranch(branch) || !storage.exists(w.ownerSlug(), w.slug(), dim)) return Optional.empty();
    try (var h = repos.open(w.ownerSlug(), w.slug(), dim)) {
      ObjectId tip = Refs.tip(h.repository(), branch);
      if (tip == null) return Optional.empty();
      try (var walk = new RevWalk(h.repository())) {
        return Optional.of(info(Refs.commit(walk.parseCommit(tip))));
      }
    }
  }

  /** 某次存檔（snapshot）在該維度的 commit（所有分支中最新的一個）。 */
  public Optional<CommitInfo> commitForSnapshot(WorldRow w, DimensionId dim, String snapshot) throws IOException {
    if (!storage.exists(w.ownerSlug(), w.slug(), dim)) return Optional.empty();
    try (var h = repos.open(w.ownerSlug(), w.slug(), dim)) {
      var tips = Refs.branches(h.repository());
      var sig = new StringBuilder(w.id()).append('/').append(dim);
      tips.forEach((n, id) -> sig.append('/').append(n).append('=').append(id.name()));
      String key = sig.toString();
      Map<String, String> index = snapshotCache.get(key);
      if (index == null) snapshotCache.put(key, index = Refs.snapshotIndex(h.repository(), MAX_HISTORY));
      String id = index.get(snapshot.toLowerCase(Locale.ROOT));
      if (id == null) return Optional.empty();
      try (var walk = new RevWalk(h.repository())) {
        return Optional.of(info(Refs.commit(walk.parseCommit(ObjectId.fromString(id)))));
      }
    }
  }

  public List<DimensionState> dimensions(WorldRow w) throws IOException {
    var out = new ArrayList<DimensionState>();
    var existing = new LinkedHashSet<>(storage.dimensions(w.ownerSlug(), w.slug()));
    for (DimensionId d : declared(w, existing)) {
      if (!existing.contains(d)) {
        out.add(new DimensionState(d.value(), d.directoryName(), null, null, 0));
        continue;
      }
      var l = log(w, d);
      out.add(new DimensionState(d.value(), d.directoryName(), l.isEmpty() ? null : l.get(0).id(),
          l.isEmpty() ? null : l.get(0).time(), l.size()));
    }
    return out;
  }

  /** 主世界 HEAD 的 dimensions 清單 ∪ 實際存在的 repo；主世界永遠視為預期存在。 */
  List<DimensionId> declared(WorldRow w, Collection<DimensionId> existing) throws IOException {
    var set = new TreeSet<DimensionId>(existing);
    set.add(DimensionId.OVERWORLD);
    if (existing.contains(DimensionId.OVERWORLD)) {
      try (var h = repos.open(w.ownerSlug(), w.slug(), DimensionId.OVERWORLD)) {
        String head = h.store().head();
        if (head != null) {
          var entry = h.store().readTree(h.store().readCommit(head).tree()).get("dimensions");
          if (entry != null) {
            Matcher m = MANIFEST.matcher(new String(h.store().readBlob(entry.id()), StandardCharsets.UTF_8));
            while (m.find()) {
              try {
                set.add(new DimensionId(m.group(1)));
              } catch (IllegalArgumentException ignored) {
                // 清單中的壞項目不影響顯示
              }
            }
          }
        }
      }
    }
    return new ArrayList<>(set);
  }

  /** 世界的預設分支以主世界 HEAD 為準，其他維度也讀同名分支。 */
  String defaultBranch(WorldRow w, Collection<DimensionId> existing) throws IOException {
    var heads = new TreeMap<DimensionId, String>();
    var names = new TreeSet<String>();
    for (DimensionId d : existing) {
      try (var h = repos.open(w.ownerSlug(), w.slug(), d)) {
        heads.put(d, Refs.headBranch(h.repository()));
        names.addAll(Refs.branches(h.repository()).keySet());
      }
    }
    return Refs.defaultBranch(heads, names);
  }

  public SnapshotPage snapshots(WorldRow w, int limit, Long before, boolean includeAuto) throws IOException {
    return snapshots(w, limit, before, includeAuto, null);
  }

  /** branch 為 null＝預設（HEAD）；指定分支時只看該分支的歷史，分支在任何維度都不存在則 NoSuchElementException（404）。 */
  public SnapshotPage snapshots(WorldRow w, int limit, Long before, boolean includeAuto, String branch) throws IOException {return snapshots(w,limit,before,includeAuto,branch,null);}
  public SnapshotPage snapshots(WorldRow w,int limit,Long before,boolean includeAuto,String branch,DimensionId dimension)throws IOException {
    var existing = storage.dimensions(w.ownerSlug(), w.slug()).stream().filter(d->dimension==null || d.equals(dimension)).toList();
    var declared = declared(w, existing);
    boolean explicitBranch = branch != null;
    if (branch == null) branch = defaultBranch(w, existing);
    var rows=new ArrayList<SnapshotRow>();boolean found=existing.isEmpty();
    for(DimensionId d:existing) {
      var commits=log(w,d,explicitBranch?branch:null);found|=!commits.isEmpty();
      for(var c:commits)rows.add(new SnapshotRow(c.snapshot(),c.time(),c.message(),c.author(),c.auto(),c.source(),Map.of(d.value(),c),false,List.of(),null));
    }
    if(!found && explicitBranch)throw new NoSuchElementException("找不到分支 "+branch);
    rows.sort(Comparator.comparingLong(SnapshotRow::time).reversed());
    var filtered = rows.stream()
        .filter(r -> before == null || r.time() < before)
        .filter(r -> includeAuto || !r.auto())
        .toList();
    boolean more = filtered.size() > limit;
    var page = filtered.subList(0, Math.min(limit, filtered.size()));
    return new SnapshotPage(page, more && !page.isEmpty() ? page.get(page.size() - 1).time() : null,
        declared.stream().map(DimensionId::value).toList());
  }

  /** commit（完整 id 或前綴）→ CommitInfo。 */
  public Optional<CommitInfo> find(WorldRow w, DimensionId dim, String rev) throws IOException {
    if (!storage.exists(w.ownerSlug(), w.slug(), dim)) return Optional.empty();
    if (rev == null || rev.equals("HEAD")) {
      try (var h = repos.open(w.ownerSlug(), w.slug(), dim)) {
        String head = h.store().head();
        return head == null ? Optional.empty() : Optional.of(info(h.store().readCommit(head)));
      }
    }
    return findAnywhere(w, dim, rev);
  }

  /** 不在預設分支歷史上的 commit（其他分支）：在該 repo 的物件庫以前綴解析。只接受 4–40 位十六進位。 */
  private Optional<CommitInfo> findAnywhere(WorldRow w, DimensionId dim, String rev) throws IOException {
    if (!HEX.matcher(rev).matches()) return Optional.empty();
    try (var h = repos.open(w.ownerSlug(), w.slug(), dim); var reader = h.repository().newObjectReader()) {
      var ids = reader.resolve(org.eclipse.jgit.lib.AbbreviatedObjectId.fromString(rev.toLowerCase(Locale.ROOT)));
      if (ids.size() > 1) throw new IllegalArgumentException("commit 前綴不唯一，請使用完整 id");
      if (ids.isEmpty()) return Optional.empty();
      try (var walk = new RevWalk(h.repository())) {
        return Optional.of(info(Refs.commit(walk.parseCommit(ids.iterator().next()))));
      } catch (org.eclipse.jgit.errors.IncorrectObjectTypeException | org.eclipse.jgit.errors.MissingObjectException e) {
        return Optional.empty();
      }
    }
  }

  public CommitDetail detail(WorldRow w, DimensionId dim, String rev, String mcVersion) throws IOException {
    CommitInfo c = find(w, dim, rev).orElseThrow(() -> new NoSuchElementException("找不到 commit " + rev));
    String key = org.worldgit.core.anvil.SavedData.NORMALIZATION + "/" + w.id() + "/" + dim + "/" + c.id();
    CommitDetail cached = detailCache.get(key);
    if (cached != null) return cached;
    Path disk = storage.cacheDir().resolve("stats").resolve(org.worldgit.core.anvil.SavedData.NORMALIZATION).resolve(w.id()).resolve(dim.directoryName() + "-" + c.id() + ".json");
    if (Files.isRegularFile(disk)) {
      try {
        CommitDetail d = json.readValue(disk.toFile(), CommitDetail.class);
        detailCache.put(key, d);
        return d;
      } catch (IOException ignored) {
        // 損毀的快取重新計算
      }
    }
    CommitDetail d = compute(w, dim, c, mcVersion);
    Files.createDirectories(disk.getParent());
    Path tmp = disk.resolveSibling(disk.getFileName() + ".tmp");
    json.writeValue(tmp.toFile(), d);
    Files.move(tmp, disk, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    detailCache.put(key, d);
    return d;
  }

  private CommitDetail compute(WorldRow w, DimensionId dim, CommitInfo c, String mcVersion) throws IOException {
    String parent = c.parents().isEmpty() ? null : c.parents().get(0);
    try (var h = repos.open(w.ownerSlug(), w.slug(), dim)) {
      ObjectStore store = h.store();
      String parentTree = parent == null ? null : h.store().readCommit(parent).tree();
      if (parent == null) {
        // 第一個 commit：全部都是新增。不逐格解碼（大世界會非常慢），只列出 chunk 與 section 數。
        var chunks = new ArrayList<int[]>();
        int sections = 0;
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (var region : store.readTree(c.tree()).values()) {
          if (region.kind() != ObjectStore.Kind.TREE || !region.name().startsWith("r.")) continue;
          for (var chunk : store.readTree(region.id()).values()) {
            if (chunk.kind() != ObjectStore.Kind.TREE || !chunk.name().startsWith("c.")) continue;
            int[] p = org.worldgit.hub.data.TreeNav.coords(chunk.name(), "c");
            int x = p[0], z = p[1];
            chunks.add(new int[] {x, z, 0, 0, 0, 0});
            b[0] = Math.min(b[0], x); b[1] = Math.min(b[1], z); b[2] = Math.max(b[2], x); b[3] = Math.max(b[3], z);
          }
        }
        return new CommitDetail(c, null, true, 0, 0, 0, chunks.size(), sections, 0, 0, 0, List.of(), chunks,
            chunks.isEmpty() ? new int[] {0, 0, 0, 0} : b, List.of(), mcVersion);
      }
      return diffDetail(store, dim, c, parent, parentTree, mcVersion);
    }
  }

  private CommitDetail diffDetail(ObjectStore store, DimensionId dim, CommitInfo c, String parent, String parentTree, String mcVersion) throws IOException {
    WorldDiff diff = new DiffEngine(store).compare(dim, parentTree, c.tree(), TOLERANCE, DiffEngine.Detail.SUMMARY);
    var perChunk = new TreeMap<org.worldgit.core.model.ChunkPos, int[]>();
    for (var s : diff.sections()) {
      int[] v = perChunk.computeIfAbsent(s.chunk(), k -> new int[6]);
      v[2] += (int) s.counts().added(); v[3] += (int) s.counts().removed(); v[4] += (int) s.counts().modified();
    }
    for (var bc : diff.biomes()) perChunk.computeIfAbsent(bc.chunk(), k -> new int[6])[5] |= 2;
    int ea = 0, er = 0, em = 0;
    var ents = new ArrayList<EntityChangeInfo>();
    for (var e : diff.entities()) {
      if (e.kind() == ChangeKind.ADDED) ea++; else if (e.kind() == ChangeKind.REMOVED) er++; else em++;
      var snap = e.after() != null ? e.after() : e.before();
      if (e.beforeChunk() != null) perChunk.computeIfAbsent(e.beforeChunk(), k -> new int[6])[5] |= 1;
      if (e.afterChunk() != null) perChunk.computeIfAbsent(e.afterChunk(), k -> new int[6])[5] |= 1;
      if (ents.size() < 200) ents.add(new EntityChangeInfo(e.uuid().toString(), e.kind().name().toLowerCase(Locale.ROOT),
          snap.data().string("id"), e.before() == null ? null : e.before().position(), e.after() == null ? null : e.after().position()));
    }
    var chunks = new ArrayList<int[]>();
    int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
    for (var en : perChunk.entrySet()) {
      int[] v = en.getValue();
      chunks.add(new int[] {en.getKey().x(), en.getKey().z(), v[2], v[3], v[4], v[5]});
      b[0] = Math.min(b[0], en.getKey().x()); b[1] = Math.min(b[1], en.getKey().z());
      b[2] = Math.max(b[2], en.getKey().x()); b[3] = Math.max(b[3], en.getKey().z());
    }
    var counts = diff.counts();
    return new CommitDetail(c, parent, false, counts.added(), counts.removed(), counts.modified(), chunks.size(),
        diff.sections().size(), ea, er, em, ents, chunks, chunks.isEmpty() ? new int[] {0, 0, 0, 0} : b,
        diff.metadata().stream().map(m -> m.kind().name().toLowerCase(Locale.ROOT) + " " + m.path()).limit(50).toList(), mcVersion);
  }
}
