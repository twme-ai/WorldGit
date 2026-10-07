package org.worldgit.fabric.logic;

import java.util.*;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.core.model.CommitMetadata;
import org.worldgit.core.model.DimensionId;

/**
 * 玩家改動歸屬（chunk 粒度）：記錄「誰在哪個 chunk 放置／破壞過方塊」。commit 時帶入 Contribution，
 * 成功後只清除當時擷取的那一代，之後新發生的歸屬不會被誤清（與 DirtyChunkTracker 的 generation 同一原則）。
 */
public final class Attribution {
  private record Key(DimensionId dimension, ChunkPos chunk) {}

  private static final class Touch {
    final Map<UUID, String> players = new LinkedHashMap<>();
    final Set<String> causes = new TreeSet<>();
    long generation;
  }

  /** 擷取當下的不可變視圖，附各 chunk 的 generation 供 acknowledge。 */
  public static final class Snapshot {
    private final Map<Key, Long> generations;
    private final Map<Key, Map<UUID, String>> players;
    private final Map<Key, Set<String>> causes;

    private Snapshot(
        Map<Key, Long> generations, Map<Key, Map<UUID, String>> players, Map<Key, Set<String>> causes) {
      this.generations = generations;
      this.players = players;
      this.causes = causes;
    }

    public boolean isEmpty() {
      return generations.isEmpty();
    }

    public int chunkCount() {
      return generations.size();
    }

    public Set<UUID> players() {
      var result = new LinkedHashSet<UUID>();
      players.values().forEach(m -> result.addAll(m.keySet()));
      return result;
    }

    public Map<UUID, String> playerNames() {
      var result = new LinkedHashMap<UUID, String>();
      players.values().forEach(result::putAll);
      return result;
    }

    public boolean touchedBy(UUID player) {
      return players.values().stream().anyMatch(m -> m.containsKey(player));
    }

    /** 某維度的歸屬：每位玩家一筆 Contribution，含他碰過的 chunk 與原因（place、break…）。 */
    public List<CommitMetadata.Contribution> contributions(
        DimensionId dimension, String emailDomain) {
      var chunks = new LinkedHashMap<UUID, Set<ChunkPos>>();
      var names = new LinkedHashMap<UUID, String>();
      var why = new LinkedHashMap<UUID, Set<String>>();
      for (var e : players.entrySet()) {
        if (!e.getKey().dimension().equals(dimension)) continue;
        for (var p : e.getValue().entrySet()) {
          chunks.computeIfAbsent(p.getKey(), k -> new TreeSet<>()).add(e.getKey().chunk());
          names.put(p.getKey(), p.getValue());
          why.computeIfAbsent(p.getKey(), k -> new TreeSet<>()).addAll(causes.get(e.getKey()));
        }
      }
      var result = new ArrayList<CommitMetadata.Contribution>();
      for (var e : chunks.entrySet())
        result.add(
            new CommitMetadata.Contribution(
                Identities.player(names.get(e.getKey()), e.getKey(), emailDomain),
                e.getKey(),
                e.getValue(),
                String.join(",", why.get(e.getKey()))));
      return result;
    }

    public Map<DimensionId, List<CommitMetadata.Contribution>> contributions(
        Collection<DimensionId> dimensions, String emailDomain) {
      var result = new LinkedHashMap<DimensionId, List<CommitMetadata.Contribution>>();
      for (var d : dimensions) {
        var list = contributions(d, emailDomain);
        if (!list.isEmpty()) result.put(d, list);
      }
      return result;
    }
  }

  private final Map<Key, Touch> touches = new HashMap<>();
  private long generation;

  public synchronized void record(
      DimensionId dimension, ChunkPos chunk, UUID player, String name, String cause) {
    var t = touches.computeIfAbsent(new Key(dimension, chunk), k -> new Touch());
    t.players.put(player, name);
    t.causes.add(cause);
    t.generation = ++generation;
  }

  public synchronized Snapshot capture() {
    return capture(d -> true);
  }

  /** 只擷取單一維度的歸屬（單維度 commit）。 */
  public synchronized Snapshot capture(DimensionId dimension) {
    return capture(dimension::equals);
  }

  private Snapshot capture(java.util.function.Predicate<DimensionId> select) {
    var g = new HashMap<Key, Long>();
    var p = new HashMap<Key, Map<UUID, String>>();
    var c = new HashMap<Key, Set<String>>();
    touches.forEach(
        (k, t) -> {
          if (!select.test(k.dimension())) return;
          g.put(k, t.generation);
          p.put(k, new LinkedHashMap<>(t.players));
          c.put(k, new TreeSet<>(t.causes));
        });
    return new Snapshot(g, p, c);
  }

  public synchronized void acknowledge(Snapshot snapshot) {
    snapshot.generations.forEach(
        (k, g) -> {
          var t = touches.get(k);
          if (t != null && t.generation == g) touches.remove(k);
        });
  }

  /** 只確認指定維度（其他維度提交失敗時保留其歸屬）。 */
  public synchronized void acknowledge(Snapshot snapshot, Set<DimensionId> dimensions) {
    snapshot.generations.forEach(
        (k, g) -> {
          if (!dimensions.contains(k.dimension())) return;
          var t = touches.get(k);
          if (t != null && t.generation == g) touches.remove(k);
        });
  }

  public synchronized int pendingChunks() {
    return touches.size();
  }
}
