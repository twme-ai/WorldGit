package org.worldgit.hub.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import org.worldgit.core.diff.DiffEngine;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.normalize.SnapshotCodec;
import org.worldgit.hub.account.Models.WorldRow;
import org.worldgit.hub.assets.AssetService;
import org.worldgit.hub.git.RepoCache;
import org.worldgit.hub.history.Dto.CommitInfo;
import org.worldgit.hub.history.HistoryService;
import org.worldgit.hub.storage.RepoStorage;

/** 3D 檢視器與地圖需要的資料：chunk 視窗、diff 視窗、實體、俯視 tile。 */
@Service
public class DataService {
  public static final int MAX_WINDOW_CHUNKS = 1024;
  private static final String TILE_VERSION = "t1";
  private final RepoCache repos;
  private final RepoStorage storage;
  private final HistoryService history;
  private final AssetService assets;
  private final ObjectMapper json;
  private final Semaphore tileSlots = new Semaphore(2);
  private final ConcurrentHashMap<String, Object> tileLocks = new ConcurrentHashMap<>();

  public DataService(RepoCache repos, RepoStorage storage, HistoryService history, AssetService assets, ObjectMapper json) {
    this.repos = repos;
    this.storage = storage;
    this.history = history;
    this.assets = assets;
    this.json = json;
  }

  public record Window(int x0, int z0, int x1, int z1) {
    public Window {
      if (x1 < x0 || z1 < z0) throw new IllegalArgumentException("視窗範圍無效");
      if ((long) x1 - x0 + 1 > MAX_WINDOW_CHUNKS || (long) z1 - z0 + 1 > MAX_WINDOW_CHUNKS
          || ((long) x1 - x0 + 1) * ((long) z1 - z0 + 1) > MAX_WINDOW_CHUNKS)
        throw new IllegalArgumentException("視窗最多 " + MAX_WINDOW_CHUNKS + " 個 chunk，請分批請求");
    }

    List<ChunkPos> chunks() {
      var out = new ArrayList<ChunkPos>();
      for (long x = x0; x <= x1; x++) for (long z = z0; z <= z1; z++) out.add(new ChunkPos((int) x, (int) z));
      return out;
    }
  }

  private CommitInfo commit(WorldRow w, DimensionId dim, String rev) throws IOException {
    return history.find(w, dim, rev).orElseThrow(() -> new NoSuchElementException("找不到 commit " + rev));
  }

  public byte[] chunks(WorldRow w, DimensionId dim, String rev, Window win) throws IOException {
    CommitInfo c = commit(w, dim, rev);
    try (var h = repos.open(w.ownerSlug(), w.slug(), dim)) {
      return ChunkWire.chunks(h.store(), new TreeNav(h.store(), c.tree()), win.chunks());
    }
  }

  /** 該 commit 相對於第一個 parent 的方塊差異（視窗內）。沒有 parent 時回傳空 diff。 */
  public byte[] diff(WorldRow w, DimensionId dim, String rev, String base, Window win) throws IOException {
    CommitInfo c = commit(w, dim, rev);
    String baseId = base != null && !base.isBlank() ? commit(w, dim, base).id() : c.parents().isEmpty() ? null : c.parents().get(0);
    try (var h = repos.open(w.ownerSlug(), w.slug(), dim)) {
      if (baseId == null) return ChunkWire.diff(new org.worldgit.core.diff.WorldDiff(dim, List.of(), List.of(), List.of(), List.of()));
      String baseTree = h.store().readCommit(baseId).tree();
      var diff = new DiffEngine(h.store()).compare(dim, baseTree, c.tree(), HistoryService.TOLERANCE, DiffEngine.Detail.BLOCKS, new HashSet<>(win.chunks()));
      return ChunkWire.diff(diff);
    }
  }

  /** 視窗內的實體（JSON）；若 commit 有 parent，附上每個 UUID 的 diff 種類。 */
  public ArrayNode entities(WorldRow w, DimensionId dim, String rev, Window win) throws IOException {
    CommitInfo c = commit(w, dim, rev);
    var out = json.createArrayNode();
    try (var h = repos.open(w.ownerSlug(), w.slug(), dim)) {
      var nav = new TreeNav(h.store(), c.tree());
      Map<UUID, String> kinds = new HashMap<>();
      var removed = new ArrayList<org.worldgit.core.diff.WorldDiff.EntityChange>();
      if (!c.parents().isEmpty()) {
        String baseTree = h.store().readCommit(c.parents().get(0)).tree();
        var diff = new DiffEngine(h.store()).compare(dim, baseTree, c.tree(), HistoryService.TOLERANCE, DiffEngine.Detail.SUMMARY, new HashSet<>(win.chunks()));
        for (var e : diff.entities()) {
          kinds.put(e.uuid(), e.kind().name().toLowerCase(Locale.ROOT));
          if (e.after() == null) removed.add(e);
        }
      }
      for (ChunkPos pos : win.chunks()) {
        var files = nav.chunk(pos);
        var entry = files == null ? null : files.get("entities.bin");
        if (entry == null) continue;
        for (var e : SnapshotCodec.entities(h.store().readBlob(entry.id()))) addEntity(out, e, kinds.get(e.uuid()));
      }
      for (var e : removed) addEntity(out, e.before(), "removed");
    }
    return out;
  }

  private void addEntity(ArrayNode out, org.worldgit.core.model.EntitySnapshot e, String kind) {
    var d = e.data();
    double[] p = e.position();
    var o = out.addObject();
    o.put("uuid", e.uuid().toString()).put("id", d.string("id")).put("x", p[0]).put("y", p[1]).put("z", p[2]);
    if (kind != null) o.put("kind", kind);
    Object name = d.get("CustomName");
    if (name != null) o.put("name", String.valueOf(name));
  }

  // ---- 俯視 tile ----

  public record TileRef(int rx, int rz, int chunks, String id) {}

  public List<TileRef> tiles(WorldRow w, DimensionId dim, String rev) throws IOException {
    CommitInfo c = commit(w, dim, rev);
    try (var h = repos.open(w.ownerSlug(), w.slug(), dim)) {
      var nav = new TreeNav(h.store(), c.tree());
      var out = new ArrayList<TileRef>();
      for (var r : nav.regions().entrySet()) {
        int[] rc = TreeNav.regionCoords(r.getKey());
        out.add(new TileRef(rc[0], rc[1], h.store().readTree(r.getValue()).size(), r.getValue()));
      }
      return out;
    }
  }

  public record TileData(byte[] png, byte[] heights, String etag) {}

  public Optional<TileData> tile(WorldRow w, DimensionId dim, String rev, int rx, int rz) throws IOException, InterruptedException {
    CommitInfo c = commit(w, dim, rev);
    String version = assets.versionFor(c.dataVersion()).id();
    try (var h = repos.open(w.ownerSlug(), w.slug(), dim)) {
      var region = new TreeNav(h.store(), c.tree()).regions().get("r." + rx + "." + rz);
      if (region == null) return Optional.empty();
      String key = region + "-" + version + "-" + TILE_VERSION;
      Path dir = storage.cacheDir().resolve("tiles");
      Path png = dir.resolve(key + ".png"), hts = dir.resolve(key + ".h");
      if (!Files.isRegularFile(png) || !Files.isRegularFile(hts)) {
        synchronized (tileLocks.computeIfAbsent(key, k -> new Object())) {
          if (!Files.isRegularFile(png) || !Files.isRegularFile(hts)) {
            tileSlots.acquire();
            try {
              var result = new TileRenderer(h.store(), assets.mapColors(version), assets.biomeColors(version)).render(region, rx, rz);
              Files.createDirectories(dir);
              write(hts, result.heights());
              write(png, result.png());
            } finally {
              tileSlots.release();
            }
          }
        }
      }
      return Optional.of(new TileData(Files.readAllBytes(png), Files.readAllBytes(hts), key));
    }
  }

  private static void write(Path target, byte[] data) throws IOException {
    Path tmp = target.resolveSibling(target.getFileName() + "." + System.nanoTime() + ".tmp");
    Files.write(tmp, data);
    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }
}
