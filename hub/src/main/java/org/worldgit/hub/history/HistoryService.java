package org.worldgit.hub.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

  public HistoryService(RepoStorage storage, RepoCache repos, AccountService accounts, ObjectMapper json) {
    this.storage = storage;
    this.repos = repos;
    this.accounts = accounts;
    this.json = json;
  }

  static CommitInfo info(RefStore.Commit c) {
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

  public SnapshotPage snapshots(WorldRow w, int limit, Long before, boolean includeAuto) throws IOException {
    var existing = storage.dimensions(w.ownerSlug(), w.slug());
    var declared = declared(w, existing);
    var groups = new LinkedHashMap<String, Map<String, CommitInfo>>();
    var rejected = new HashMap<String, String>();
    for (DimensionId d : existing) {
      for (CommitInfo c : log(w, d)) groups.computeIfAbsent(c.snapshot(), k -> new LinkedHashMap<>()).put(d.value(), c);
    }
    for (var e : accounts.pushEvents(w.id(), 200)) {
      if ("REJECTED".equals(e.status()) && e.snapshot() != null) rejected.put(e.snapshot(), e.message());
    }
    var rows = new ArrayList<SnapshotRow>();
    for (var g : groups.entrySet()) {
      var commits = g.getValue();
      CommitInfo lead = commits.getOrDefault(DimensionId.OVERWORLD.value(), commits.values().iterator().next());
      long time = commits.values().stream().mapToLong(CommitInfo::time).max().orElse(0);
      var missing = new ArrayList<String>();
      for (DimensionId d : declared) {
        if (commits.containsKey(d.value())) continue;
        // 沒有該維度的 commit：repo 尚未推送（不存在或沒有 HEAD）才算缺少；
        // repo 已有歷史而這次存檔沒有 commit，表示該維度那次沒有變動。
        boolean pushed = existing.contains(d) && !log(w, d).isEmpty();
        if (!pushed) missing.add(d.value());
      }
      String reason = rejected.get(g.getKey());
      boolean partial = !missing.isEmpty() || reason != null;
      rows.add(new SnapshotRow(g.getKey(), time, lead.message(), lead.author(), lead.auto(), lead.source(),
          commits, partial, missing, reason != null ? "push 被拒絕：" + reason : !missing.isEmpty() ? "尚未推送的維度：" + String.join(", ", missing) : null));
    }
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
    if (rev == null || rev.equals("HEAD")) return log(w, dim).stream().findFirst();
    for (CommitInfo c : log(w, dim)) if (c.id().startsWith(rev)) return Optional.of(c);
    return Optional.empty();
  }

  public CommitDetail detail(WorldRow w, DimensionId dim, String rev, String mcVersion) throws IOException {
    CommitInfo c = find(w, dim, rev).orElseThrow(() -> new NoSuchElementException("找不到 commit " + rev));
    String key = w.id() + "/" + dim + "/" + c.id();
    CommitDetail cached = detailCache.get(key);
    if (cached != null) return cached;
    Path disk = storage.cacheDir().resolve("stats").resolve(w.id()).resolve(dim.directoryName() + "-" + c.id() + ".json");
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
}
