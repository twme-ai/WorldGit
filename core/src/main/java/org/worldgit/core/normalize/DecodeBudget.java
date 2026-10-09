package org.worldgit.core.normalize;

import java.io.IOException;

/**
 * 跨 blob／NBT／commit 的聚合預算；在同一執行緒以 scope 共用，close 必定還原。
 * 未開 scope 的既有呼叫維持原本單物件限制，供離線 CLI／平台使用。
 */
public final class DecodeBudget {
  private static final ThreadLocal<DecodeBudget> CURRENT = new ThreadLocal<>();
  private long decoded, read, nodes, objects, work;

  public DecodeBudget(long decodedBytes, long readBytes, long nodes, long objects, long work) {
    if (decodedBytes <= 0 || readBytes <= 0 || nodes <= 0 || objects <= 0 || work <= 0)
      throw new IllegalArgumentException("預算必須大於零");
    this.decoded = decodedBytes;
    this.read = readBytes;
    this.nodes = nodes;
    this.objects = objects;
    this.work = work;
  }

  public static final class Exceeded extends IOException {
    public Exceeded(String kind) { super("資料超過聚合解析預算（" + kind + "），請縮小請求或分批推送"); }
  }

  private static long take(long left, long amount, String kind) throws Exceeded {
    if (amount < 0 || amount > left) throw new Exceeded(kind);
    return left - amount;
  }

  public static void decoded(long n) throws Exceeded {
    var b = CURRENT.get();
    if (b != null) b.decoded = take(b.decoded, n, "解壓縮位元組");
  }

  public static void read(long n) throws Exceeded {
    var b = CURRENT.get();
    if (b != null) b.read = take(b.read, n, "輸入位元組");
  }

  public static void nodes(long n) throws Exceeded {
    var b = CURRENT.get();
    if (b != null) b.nodes = take(b.nodes, n, "NBT 節點");
  }

  public static void objects(long n) throws Exceeded {
    var b = CURRENT.get();
    if (b != null) b.objects = take(b.objects, n, "物件數");
  }

  public static void work(long n) throws Exceeded {
    var b = CURRENT.get();
    if (b != null) b.work = take(b.work, n, "解析工作量");
  }

  /** 路徑查詢快取在聚合 scope 內保留原本逐 tree 的計費路徑。 */
  public static boolean scoped() { return CURRENT.get() != null; }

  /** 在配置容器前檢查，不先扣款；逐節點 read 時再實際扣款。 */
  public static void checkNodes(long n) throws Exceeded {
    var b = CURRENT.get();
    if (b != null) take(b.nodes, n, "NBT 節點");
  }

  public Scope open() { return new Scope(this); }

  public static final class Scope implements AutoCloseable {
    private final DecodeBudget previous;
    private boolean closed;
    private Scope(DecodeBudget budget) { previous = CURRENT.get(); CURRENT.set(budget); }
    @Override public void close() {
      if (!closed) {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        closed = true;
      }
    }
  }
}
