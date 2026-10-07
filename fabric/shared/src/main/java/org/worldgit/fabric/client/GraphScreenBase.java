package org.worldgit.fabric.client;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.worldgit.core.graph.CommitGraph;
import org.worldgit.fabric.logic.UiProtocol;

/**
 * 可捲動的分支圖畫面：每個 commit 一列（lane 連線＋標籤＋訊息），點選後可複製完整 id 或把指令填進聊天輸入。
 * 資料來自伺服器的 {@code worldgit:ui} 分支圖封包（與聊天文字圖同一份 core CommitGraph），重新整理走同一個請求。
 */
abstract class GraphScreenBase extends Screen {
  static final int ROW = 14, LANE = 10, TOP = 54, DETAIL = 62;
  private static final int[] PALETTE = {0xFF4FA8FF, 0xFF4CD964, 0xFFFFC83D, 0xFFFF7AC6, 0xFF9B7BFF, 0xFF4DE0D0, 0xFFFF9A45, 0xFFB8D95A};

  private long generation = -1;
  private int scroll, selected = -1;
  private boolean refs;
  private List<CommitGraph.Node> nodes = List.of();
  private String dimension = "";
  private boolean truncated;
  private int listBottom;

  GraphScreenBase() { super(Component.translatable("worldgit.graph.title")); }

  @Override public boolean isPauseScreen() { return false; }

  private ClientUi ui() { return ClientRuntime.get().ui(); }

  private void load() {
    var graph = ui().graph();
    generation = ui().graphGeneration();
    if (graph == null) return;
    String selectedId = selected >= 0 && selected < nodes.size() ? nodes.get(selected).id() : null;
    nodes = graph.nodes();
    dimension = graph.dimension();
    truncated = graph.truncated();
    selected = -1;
    if (selectedId != null) for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).id().equals(selectedId)) selected = i;
    scroll = Math.max(0, Math.min(scroll, Math.max(0, nodes.size() - visibleRows())));
  }

  private int visibleRows() { return Math.max(1, (listBottom - TOP) / ROW); }

  @Override public void tick() {
    if (generation != ui().graphGeneration()) { load(); rebuildWidgets(); }
  }

  @Override protected void init() {
    load();
    listBottom = height - DETAIL - 40;
    addRenderableWidget(Button.builder(Component.translatable("worldgit.graph.close"), b -> onClose()).bounds(width - 84, 6, 72, 20).build());
    addRenderableWidget(Button.builder(Component.translatable("worldgit.graph.refresh"), b -> refresh()).bounds(width - 164, 6, 76, 20).build());
    var toggle = addRenderableWidget(Button.builder(Component.translatable(refs ? "worldgit.graph.refs_on" : "worldgit.graph.refs_off"), b -> {
      refs = !refs;
      refresh();
      rebuildWidgets();
    }).bounds(width - 284, 6, 116, 20).build());
    toggle.setTooltip(Tooltip.create(Component.translatable("worldgit.graph.refs_tip")));
    boolean has = selected >= 0 && selected < nodes.size();
    int y = height - 24;
    var copy = addRenderableWidget(Button.builder(Component.translatable("worldgit.graph.copy"), b -> copyId()).bounds(12, y, 90, 20).build());
    var diff = addRenderableWidget(Button.builder(Component.translatable("worldgit.graph.diff"), b -> fill("diff")).bounds(106, y, 120, 20).build());
    var restore = addRenderableWidget(Button.builder(Component.translatable("worldgit.graph.switch"), b -> fill("switch")).bounds(230, y, 120, 20).build());
    copy.active = diff.active = restore.active = has;
  }

  private void refresh() {
    ClientRuntime.get().requestGraph(dimension, refs);
  }

  private String selectedId() { return selected >= 0 && selected < nodes.size() ? nodes.get(selected).id() : null; }

  private void copyId() {
    String id = selectedId();
    if (id != null) Minecraft.getInstance().keyboardHandler.setClipboard(id);
  }

  /** 填入聊天輸入框（不送出），讓玩家確認後再執行。 */
  private void fill(String command) {
    String id = selectedId();
    if (id == null) return;
    String text = "/wg " + command + " " + id + (command.equals("switch") ? " --dry-run" : "") + " --dimension " + dimension;
    ClientPlatform.openChat(text);
  }

  @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) {
    scroll = Math.max(0, Math.min(Math.max(0, nodes.size() - visibleRows()), scroll - (int) Math.signum(sy) * 3));
    return true;
  }

  @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
    if (super.mouseClicked(event, doubleClick)) return true;
    if (event.x() >= 8 && event.x() < width - 8 && event.y() >= TOP && event.y() < listBottom) {
      int index = scroll + (int) ((event.y() - TOP) / ROW);
      if (index >= 0 && index < nodes.size()) {
        selected = index;
        if (doubleClick) copyId();
        rebuildWidgets();
        return true;
      }
    }
    return false;
  }

  /** 以選取的 commit 與捲動位置做截圖／測試。 */
  int selectedIndex() { return selected; }

  void select(int index) { selected = index; rebuildWidgets(); }

  int nodeCount() { return nodes.size(); }

  static int color(String id) { return PALETTE[Math.floorMod(id.hashCode(), PALETTE.length)]; }

  private int laneX(int lane) { return 18 + lane * LANE; }

  static int labelColor(String kind) {
    return switch (kind) {
      case "HEAD" -> 0xFFFF5A5A;
      case "tag" -> 0xFFFFC83D;
      case "tracking" -> 0xFF4DE0D0;
      default -> 0xFF4CD964;
    };
  }

  /** 背景與清單（在按鈕之前繪製）。 */
  void paintUnder(Canvas canvas) {
    canvas.fill(0, 0, width, height, 0xE010121C);
    canvas.textLimited(Component.translatable("worldgit.graph.heading", dimension.isEmpty() ? "—" : dimension, nodes.size()), 12, 35, 0xFFFFFFFF, width - 24);
    canvas.fill(8, TOP - 2, width - 8, listBottom, 0xFF161B26);
    if (nodes.isEmpty()) {
      canvas.text(Component.translatable("worldgit.graph.empty"), 16, TOP + 6, 0xFFB0B8C8);
      return;
    }
    int maxLane = 1;
    for (var node : nodes) maxLane = Math.max(maxLane, Math.max(node.before().size(), node.after().size()));
    int textX = laneX(Math.min(maxLane, 12)) + 8;
    int rows = visibleRows();
    for (int i = scroll; i < Math.min(nodes.size(), scroll + rows); i++) {
      var node = nodes.get(i);
      int y = TOP + (i - scroll) * ROW, cy = y + ROW / 2;
      if (i == selected) canvas.fill(9, y, width - 9, y + ROW, 0xFF263350);
      drawLanes(canvas, node, y, cy);
      int x = textX;
      for (var label : node.labels()) {
        var chip = Component.literal("[" + label.name() + "]");
        canvas.text(chip, x, y + 3, labelColor(label.kind()));
        x += canvas.width(chip) + 4;
      }
      canvas.text(Component.literal(node.id().substring(0, Math.min(8, node.id().length()))), x, y + 3, 0xFFFFE08A);
      x += 56;
      var message = Component.literal(node.message());
      canvas.textLimited(message, x, y + 3, 0xFFFFFFFF, Math.max(0, width - 18 - x));
      var meta = Component.literal(node.author() + "  " + node.time().replace('T', ' ').substring(0, Math.min(19, node.time().length())));
      int metaX = width - 16 - canvas.width(meta);
      if (metaX > x + canvas.width(message) + 12) canvas.text(meta, metaX, y + 3, 0xFF8892A8);
    }
    if (nodes.size() > rows) {
      int trackH = listBottom - TOP, barH = Math.max(12, trackH * rows / nodes.size());
      int barY = TOP + (trackH - barH) * scroll / Math.max(1, nodes.size() - rows);
      canvas.fill(width - 12, TOP, width - 9, listBottom, 0xFF222A3A);
      canvas.fill(width - 12, barY, width - 9, barY + barH, 0xFF6C7A9C);
    }
    if (truncated) canvas.text(Component.translatable("worldgit.graph.truncated"), 12, listBottom + 4, 0xFFFFC83D);
  }

  private void drawLanes(Canvas canvas, CommitGraph.Node node, int y, int cy) {
    int bottom = y + ROW;
    // 穿過這一列的其他分支線：同一個 commit 在上方 lane 與下方 lane 之間連線。
    for (int lane = 0; lane < node.before().size(); lane++) {
      if (lane == node.lane()) continue;
      String id = node.before().get(lane);
      int target = node.after().indexOf(id);
      if (target >= 0) canvas.line(laneX(lane), y, laneX(target), bottom, color(id));
    }
    int color = color(node.id());
    if (node.lane() < node.before().size()) canvas.line(laneX(node.lane()), y, laneX(node.lane()), cy, color);
    for (var edge : node.edges()) if (edge.toLane() >= 0) canvas.line(laneX(edge.fromLane()), cy, laneX(edge.toLane()), bottom, color(edge.parent()));
    int cx = laneX(node.lane());
    canvas.fill(cx - 3, cy - 3, cx + 4, cy + 4, color);
    boolean head = node.labels().stream().anyMatch(l -> l.kind().equals("HEAD"));
    if (head) canvas.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFF10121C);
  }

  /** 選取 commit 的完整資訊（在按鈕之後繪製，避免被清單蓋住）。 */
  void paintOver(Canvas canvas) {
    int y = height - DETAIL - 24;
    canvas.fill(8, y, width - 8, y + DETAIL - 2, 0xFF161B26);
    if (selected < 0 || selected >= nodes.size()) {
      canvas.text(Component.translatable("worldgit.graph.select"), 16, y + 8, 0xFFB0B8C8);
      return;
    }
    var node = nodes.get(selected);
    canvas.text(Component.literal(node.id()), 16, y + 5, 0xFFFFE08A);
    canvas.text(Component.literal(node.author() + " · " + node.time()), 16, y + 18, 0xFFB0B8C8);
    canvas.text(Component.literal(node.message()), 16, y + 31, 0xFFFFFFFF);
    String parents = node.parents().isEmpty() ? "—" : String.join(", ", node.parents().stream().map(p -> p.substring(0, Math.min(10, p.length()))).toList());
    canvas.text(Component.translatable("worldgit.graph.parents", parents), 16, y + 44, 0xFF8892A8);
  }
}
