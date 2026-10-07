package org.worldgit.fabric.client;

import java.util.*;
import org.worldgit.fabric.logic.UiProtocol;

/**
 * 客戶端 UI 狀態（只在 client thread 存取）：進度 HUD、分支圖與 .wgignore 畫面的資料。
 * 資料全部來自伺服器的 {@code worldgit:ui} 封包；沒有宣告能力的伺服器（Paper、原版）不會有任何狀態。
 */
final class ClientUi {
  record Hud(UUID id, String operation, String dimension, String phase, int unit, long completed, long total, long etaMillis,
      UiProtocol.Status status, String text, long elapsedMillis, long received, long ttlMillis) {
    boolean terminal() { return status != UiProtocol.Status.RUNNING; }
  }

  private final Map<UUID, Hud> huds = new LinkedHashMap<>();
  private final UiProtocol.GraphAssembler graphs = new UiProtocol.GraphAssembler();
  private UiProtocol.GraphAssembler.Completed graph;
  private long graphGeneration;
  private UiProtocol.IgnoreView ignoreView;
  private UiProtocol.IgnorePreview ignorePreview;
  private UiProtocol.IgnoreResult ignoreResult;
  private long ignoreGeneration;
  private long requests = System.nanoTime();

  long nextRequest() { return ++requests; }

  void progress(UiProtocol.Progress p, long now) {
    long ttl = p.status() == UiProtocol.Status.RUNNING ? 10_000 : Math.min(30_000, Math.max(1_000, p.etaMillis()));
    huds.put(p.operation(), new Hud(p.operation(), p.name(), p.dimension(), p.phase(), p.unit(), p.completed(), p.total(),
        p.status() == UiProtocol.Status.RUNNING ? p.etaMillis() : -1, p.status(), p.text(), p.elapsedMillis(), now, ttl));
    while (huds.size() > 4) huds.remove(huds.keySet().iterator().next());
  }

  /** 目前應顯示的進度（過期者移除）。 */
  List<Hud> active(long now) {
    huds.values().removeIf(h -> now - h.received() > h.ttlMillis());
    return List.copyOf(huds.values());
  }

  Optional<UiProtocol.GraphAssembler.Completed> acceptGraph(UiProtocol.GraphPart part) {
    var done = graphs.accept(part);
    done.ifPresent(value -> { graph = value; graphGeneration++; });
    return done;
  }

  UiProtocol.GraphAssembler.Completed graph() { return graph; }

  long graphGeneration() { return graphGeneration; }

  void ignoreView(UiProtocol.IgnoreView view) {
    if (view.open() || ignoreView == null || !ignoreView.dimension().equals(view.dimension()) || !ignoreView.token().equals(view.token())) {
      ignorePreview = null;
      ignoreResult = null;
    }
    // 同一份規則的後續頁（offset>0）接在前面；token 改變就整份重來。
    if (ignoreView != null && ignoreView.token().equals(view.token()) && ignoreView.dimension().equals(view.dimension()) && view.offset() > 0
        && ignoreView.offset() + ignoreView.lines().size() == view.offset()) {
      var lines = new ArrayList<>(ignoreView.lines());
      lines.addAll(view.lines());
      ignoreView = new UiProtocol.IgnoreView(view.request(), view.dimension(), view.token(), view.editable(), view.merging(), view.total(), ignoreView.offset(), view.open(), lines);
    } else ignoreView = view;
    ignoreGeneration++;
  }

  void ignorePreview(UiProtocol.IgnorePreview preview) { ignorePreview = preview; ignoreGeneration++; }

  void ignoreResult(UiProtocol.IgnoreResult result) {
    ignoreResult = result;
    if (!result.ok() || result.kind().equals("written") || result.kind().equals("cancelled")) ignorePreview = null;
    ignoreGeneration++;
  }

  void clearIgnorePreview() { ignorePreview = null; ignoreGeneration++; }

  UiProtocol.IgnoreView ignoreView() { return ignoreView; }

  UiProtocol.IgnorePreview ignorePreview() { return ignorePreview; }

  UiProtocol.IgnoreResult ignoreResult() { return ignoreResult; }

  long ignoreGeneration() { return ignoreGeneration; }

  void reset() {
    huds.clear();
    graphs.clear();
    graph = null;
    ignoreView = null;
    ignorePreview = null;
    ignoreResult = null;
    graphGeneration++;
    ignoreGeneration++;
  }
}
