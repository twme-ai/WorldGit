package org.worldgit.paper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.core.model.CommitMetadata;
import org.worldgit.core.model.DimensionId;

/**
 * 作者歸屬：事件與 WorldEdit/FAWE API 記錄「誰在哪個維度改了哪些 chunk、原因是什麼」（決定 #11，不整合 CoreProtect）。
 *
 * <p>寫入端（任何執行緒，可能同時來自多個 Folia region）取讀鎖，非常便宜；commit 取走資料時取寫鎖換掉整張表，
 * 因此取走之後的新事件屬於下一次 commit，不會遺失也不會重複。commit 失敗時 {@link #restore} 併回。
 */
public final class Attribution {
  private record Key(DimensionId dimension, UUID player) {}

  private static final class Entry {
    volatile String name;
    final ConcurrentMap<ChunkPos, Set<String>> chunks = new ConcurrentHashMap<>();
    Entry(String name) { this.name = Objects.requireNonNull(name); }
  }

  /** 一位玩家在一個維度的貢獻。 */
  public record Contributor(UUID player, String name, Map<ChunkPos, Set<String>> chunks) {
    public Contributor {
      Objects.requireNonNull(player);
      Objects.requireNonNull(name);
      var copy = new TreeMap<ChunkPos, Set<String>>();
      chunks.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
      chunks = Collections.unmodifiableSortedMap(copy);
    }

    public CommitMetadata.Identity identity() {
      return new CommitMetadata.Identity(name, player + "@players.worldgit.invalid");
    }

    public String cause() {
      var causes = new TreeSet<String>();
      chunks.values().forEach(causes::addAll);
      return causes.isEmpty() ? "unknown" : String.join("+", causes);
    }

    public CommitMetadata.Contribution toContribution() {
      return new CommitMetadata.Contribution(identity(), player, chunks.keySet(), cause());
    }
  }

  /** 被取走的一批歸屬資料。 */
  public record Drained(Map<DimensionId, List<Contributor>> byDimension) {
    public Drained {
      var copy = new TreeMap<DimensionId, List<Contributor>>();
      byDimension.forEach((k, v) -> copy.put(k, List.copyOf(v)));
      byDimension = Collections.unmodifiableSortedMap(copy);
    }

    public List<Contributor> of(DimensionId dimension) {
      return byDimension.getOrDefault(dimension, List.of());
    }

    public boolean empty() {
      return byDimension.values().stream().allMatch(List::isEmpty);
    }

    /** git 主要作者：該維度改動最多 chunk 的玩家；同數量取名稱字典序最小，結果固定可重現。 */
    public Optional<Contributor> primary(DimensionId dimension) {
      return of(dimension).stream()
          .max(
              Comparator.comparingInt((Contributor c) -> c.chunks().size())
                  .thenComparing(Contributor::name, Comparator.reverseOrder()));
    }
  }

  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private ConcurrentMap<Key, Entry> current = new ConcurrentHashMap<>();

  public void record(DimensionId dimension, UUID player, String name, ChunkPos chunk, String cause) {
    lock.readLock().lock();
    try {
      Entry e = current.computeIfAbsent(new Key(dimension, player), k -> new Entry(name));
      e.name = name;
      e.chunks.computeIfAbsent(chunk, k -> ConcurrentHashMap.newKeySet()).add(cause);
    } finally {
      lock.readLock().unlock();
    }
  }

  public Drained drain() {
    ConcurrentMap<Key, Entry> old;
    lock.writeLock().lock();
    try {
      old = current;
      current = new ConcurrentHashMap<>();
    } finally {
      lock.writeLock().unlock();
    }
    return toDrained(old);
  }

  /** commit 失敗時併回，避免作者資料遺失。 */
  public void restore(Drained drained) {
    drained.byDimension.forEach(
        (dimension, list) ->
            list.forEach(
                c -> c.chunks().forEach((chunk, causes) -> causes.forEach(cause -> record(dimension, c.player(), c.name(), chunk, cause)))));
  }

  /** 不取走，只讀目前累積量（status 顯示用）。 */
  public Drained peek() {
    lock.readLock().lock();
    try {
      return toDrained(current);
    } finally {
      lock.readLock().unlock();
    }
  }

  private static Drained toDrained(Map<Key, Entry> map) {
    var result = new TreeMap<DimensionId, List<Contributor>>();
    map.forEach(
        (key, entry) ->
            result
                .computeIfAbsent(key.dimension, k -> new ArrayList<>())
                .add(new Contributor(key.player, entry.name, entry.chunks)));
    result.values().forEach(l -> l.sort(Comparator.comparing(Contributor::name).thenComparing(Contributor::player)));
    return new Drained(result);
  }
}
