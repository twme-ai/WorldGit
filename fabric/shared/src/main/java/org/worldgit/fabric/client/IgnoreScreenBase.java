package org.worldgit.fabric.client;

import java.util.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.worldgit.core.config.IgnoreRules;
import org.worldgit.fabric.logic.UiProtocol;

/**
 * .wgignore 圖形編輯畫面：規則清單、新增／刪除／排序／停用、即時語法錯誤與 preview 結果。
 * 畫面只是請求端：每次修改都送給伺服器，由伺服器重新驗證權限、MERGING、規則與 HEAD，先 preview 再按「確認」才寫入。
 */
abstract class IgnoreScreenBase extends Screen {
  static final int ROW = 14, TOP = 54;

  private long generation = -1;
  private int scroll, selected = -1;
  private EditBox input;
  private Button addButton;
  private String syntax = "";
  private final Map<Integer, String> lineErrors = new HashMap<>();
  private int listBottom;

  IgnoreScreenBase() { super(Component.translatable("worldgit.ignore.title")); }

  @Override public boolean isPauseScreen() { return false; }

  private ClientUi ui() { return ClientRuntime.get().ui(); }

  private UiProtocol.IgnoreView view() { return ui().ignoreView(); }

  @Override public void tick() {
    if (generation != ui().ignoreGeneration()) rebuildWidgets();
  }

  private void errors() {
    lineErrors.clear();
    var view = view();
    if (view == null) return;
    for (int i = 0; i < view.lines().size(); i++) {
      var line = view.lines().get(i);
      if (!line.rule() || !line.enabled()) continue;
      try {
        IgnoreRules.parse(line.text());
      } catch (Exception e) {
        lineErrors.put(view.offset() + i + 1, String.valueOf(e.getMessage()));
      }
    }
  }

  @Override protected void init() {
    generation = ui().ignoreGeneration();
    errors();
    var view = view();
    boolean editable = view != null && view.editable() && !view.merging();
    var preview = ui().ignorePreview();
    boolean pending = preview != null && !preview.code().isEmpty();
    listBottom = height - 148;
    addRenderableWidget(Button.builder(Component.translatable("worldgit.ignore.close"), b -> onClose()).bounds(width - 84, 6, 72, 20).build());
    addRenderableWidget(Button.builder(Component.translatable("worldgit.ignore.reload"), b -> ClientRuntime.get().requestIgnore("view", 0, 0, "", "")).bounds(width - 164, 6, 76, 20).build());
    // 新增規則：即時語法檢查（與伺服器同一個 core parser，伺服器仍會重新驗證）。
    String previous = input == null ? "" : input.getValue();
    input = new EditBox(font, 12, height - 82, width - 112, 18, Component.translatable("worldgit.ignore.input"));
    input.setMaxLength(UiProtocol.MAX_LINE_CHARS);
    input.setHint(Component.translatable("worldgit.ignore.hint"));
    input.setResponder(this::validate);
    input.setValue(previous);
    input.setEditable(editable && !pending);
    addRenderableWidget(input);
    validate(input.getValue());
    int y = height - 24;
    addButton = addRenderableWidget(Button.builder(Component.translatable("worldgit.ignore.add"), b -> add()).bounds(width - 96, height - 82, 84, 18).build());
    validate(input.getValue());
    boolean has = selected >= 0 && view != null && selected < view.total() && selected >= view.offset() && selected - view.offset() < view.lines().size();
    boolean rule = has && view.lines().get(selected - view.offset()).rule();
    boolean enabledRule = rule && view.lines().get(selected - view.offset()).enabled();
    var remove = addRenderableWidget(Button.builder(Component.translatable("worldgit.ignore.remove"), b -> edit("remove", selected + 1, 0)).bounds(12, y, 70, 20).build());
    var toggle = addRenderableWidget(Button.builder(Component.translatable(enabledRule || !rule ? "worldgit.ignore.disable" : "worldgit.ignore.enable"),
        b -> edit(enabledRule ? "disable" : "enable", selected + 1, 0)).bounds(86, y, 76, 20).build());
    var up = addRenderableWidget(Button.builder(Component.literal("↑"), b -> edit("move", selected + 1, selected)).bounds(166, y, 24, 20).build());
    var down = addRenderableWidget(Button.builder(Component.literal("↓"), b -> edit("move", selected + 1, selected + 2)).bounds(194, y, 24, 20).build());
    remove.active = editable && !pending && has;
    toggle.active = editable && !pending && rule;
    up.active = editable && !pending && has && selected > 0;
    down.active = editable && !pending && has && view != null && selected + 1 < view.total();
    var test = addRenderableWidget(Button.builder(Component.translatable("worldgit.ignore.test"), b -> ClientRuntime.get().requestIgnore("test", 0, 0, "", "")).bounds(226, y, 84, 20).build());
    test.setTooltip(Tooltip.create(Component.translatable("worldgit.ignore.test_tip")));
    test.active = view != null;
    var check = addRenderableWidget(Button.builder(Component.translatable("worldgit.ignore.preview"), b -> ClientRuntime.get().requestIgnore("preview", 0, 0, "", view == null ? "" : view.token())).bounds(314, y, 84, 20).build());
    check.active = view != null;
    var confirm = addRenderableWidget(Button.builder(Component.translatable("worldgit.ignore.confirm"), b -> ClientRuntime.get().requestIgnore("confirm", 0, 0, "", "", preview.code())).bounds(width - 196, y, 90, 20).build());
    var cancel = addRenderableWidget(Button.builder(Component.translatable("worldgit.ignore.cancel"), b -> ClientRuntime.get().requestIgnore("cancel", 0, 0, "", "")).bounds(width - 102, y, 90, 20).build());
    confirm.active = cancel.active = pending;
    confirm.visible = cancel.visible = pending;
    remove.visible = toggle.visible = up.visible = down.visible = test.visible = check.visible = !pending;
  }

  private void validate(String value) {
    syntax = "";
    if (value.trim().startsWith("#")) {
      syntax = Component.translatable("worldgit.ignore.syntax_comment").getString();
    } else if (!value.isBlank()) try {
      IgnoreRules.parse(value);
    } catch (Exception e) {
      syntax = String.valueOf(e.getMessage());
    }
    var view = view();
    var preview = ui().ignorePreview();
    if (addButton != null) addButton.active = view != null && view.editable() && !view.merging()
        && (preview == null || preview.code().isEmpty()) && !value.isBlank() && syntax.isEmpty();
  }

  private void add() {
    ClientRuntime.get().requestIgnore("add", 0, 0, input.getValue(), view() == null ? "" : view().token());
    input.setValue("");
  }

  private void edit(String op, int line, int destination) {
    var view = view();
    ClientRuntime.get().requestIgnore(op, line, destination, "", view == null ? "" : view.token());
  }

  @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) {
    var view = view();
    int rows = Math.max(1, (listBottom - TOP) / ROW), total = view == null ? 0 : view.total();
    scroll = Math.max(0, Math.min(Math.max(0, total - rows), scroll - (int) Math.signum(sy) * 3));
    maybeLoadMore(rows);
    return true;
  }

  private void maybeLoadMore(int rows) {
    var view = view();
    if (view == null) return;
    int loadedEnd = view.offset() + view.lines().size();
    if (loadedEnd < view.total() && scroll + rows + 5 >= loadedEnd) ClientRuntime.get().requestIgnore("view", 0, 0, "", "", "", loadedEnd);
  }

  @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
    if (super.mouseClicked(event, doubleClick)) return true;
    var view = view();
    if (view != null && event.x() >= 8 && event.x() < width - 8 && event.y() >= TOP && event.y() < listBottom) {
      int index = scroll + (int) ((event.y() - TOP) / ROW);
      if (index >= 0 && index < view.total()) {
        selected = index;
        rebuildWidgets();
        return true;
      }
    }
    return false;
  }

  int selectedLine() { return selected + 1; }

  void select(int line) { selected = line - 1; rebuildWidgets(); }

  void typeRule(String rule) { input.setValue(rule); rebuildWidgets(); }

  void paintUnder(Canvas canvas) {
    canvas.fill(0, 0, width, height, 0xE010121C);
    var view = view();
    canvas.textLimited(Component.translatable("worldgit.ignore.heading", view == null ? "—" : view.dimension(), view == null ? 0 : view.total()), 12, 35, 0xFFFFFFFF, width - 24);
    canvas.fill(8, TOP - 2, width - 8, listBottom, 0xFF161B26);
    if (view == null) {
      canvas.text(Component.translatable("worldgit.ignore.loading"), 16, TOP + 6, 0xFFB0B8C8);
      return;
    }
    if (view.merging()) canvas.text(Component.translatable("worldgit.ignore.merging"), 220, 12, 0xFFFF5A5A);
    else if (!view.editable()) canvas.text(Component.translatable("worldgit.ignore.readonly"), 220, 12, 0xFFFFC83D);
    int rows = Math.max(1, (listBottom - TOP) / ROW);
    for (int i = scroll; i < Math.min(view.total(), scroll + rows); i++) {
      int y = TOP + (i - scroll) * ROW;
      if (i == selected) canvas.fill(9, y, width - 9, y + ROW, 0xFF263350);
      int local = i - view.offset();
      if (local < 0 || local >= view.lines().size()) {
        canvas.text(Component.literal("…"), 16, y + 3, 0xFF6C7A9C);
        continue;
      }
      var line = view.lines().get(local);
      String error = lineErrors.get(i + 1);
      int color = error != null ? 0xFFFF5A5A : !line.rule() ? 0xFF7C879E : line.enabled() ? 0xFFFFFFFF : 0xFF8892A8;
      canvas.text(Component.literal(String.format("%4d", i + 1)), 14, y + 3, 0xFF6C7A9C);
      canvas.text(Component.literal(!line.rule() ? "" : line.enabled() ? "✓" : "✗"), 48, y + 3, line.rule() && line.enabled() ? 0xFF4CD964 : 0xFF8892A8);
      canvas.textLimited(Component.literal(line.text() + (line.cut() ? "…" : "")), 62, y + 3, color, width - 82);
      if (error != null) {
        var message = Component.literal("⚠ " + error);
        int x = width - 18 - canvas.width(message);
        if (x > 200) canvas.text(message, x, y + 3, 0xFFFF5A5A);
      }
    }
    if (view.total() > rows) {
      int trackH = listBottom - TOP, barH = Math.max(12, trackH * rows / view.total());
      int barY = TOP + (trackH - barH) * scroll / Math.max(1, view.total() - rows);
      canvas.fill(width - 12, TOP, width - 9, listBottom, 0xFF222A3A);
      canvas.fill(width - 12, barY, width - 9, barY + barH, 0xFF6C7A9C);
    }
  }

  /** 按鈕之後：preview／結果面板與即時語法訊息。 */
  void paintOver(Canvas canvas) {
    int y = listBottom + 4;
    canvas.fill(8, y, width - 8, y + 54, 0xFF161B26);
    var preview = ui().ignorePreview();
    var result = ui().ignoreResult();
    if (preview != null) {
      canvas.text(Component.translatable(preview.code().isEmpty() ? "worldgit.ignore.preview_current" : "worldgit.ignore.preview_change", preview.change()), 14, y + 4, 0xFFFFC83D);
      canvas.text(Component.translatable("worldgit.ignore.preview_counts", n(preview.blocks()), n(preview.blockEntities()), n(preview.entities()), n(preview.biomes()), n(preview.fields())), 14, y + 16, 0xFFFFFFFF);
      canvas.text(Component.literal(preview.examples().isEmpty() ? "—" : String.join("; ", preview.examples().stream().limit(3).toList())), 14, y + 28, 0xFF8892A8);
      if (!preview.code().isEmpty()) canvas.text(Component.translatable("worldgit.ignore.confirm_hint", preview.expiresSeconds()), 14, y + 40, 0xFFB0B8C8);
    } else if (result != null) {
      canvas.text(Component.literal(result.text()), 14, y + 8, result.ok() ? 0xFF4CD964 : 0xFFFF5A5A);
    } else canvas.text(Component.translatable("worldgit.ignore.help"), 14, y + 8, 0xFFB0B8C8);
    if (preview != null && result != null && !result.ok()) canvas.text(Component.literal(result.text()), 14, y + 40, 0xFFFF5A5A);
    int syntaxY = height - 60;
    if (!syntax.isEmpty()) canvas.text(Component.literal("⚠ " + syntax), 14, syntaxY, 0xFFFF5A5A);
    else if (input != null && !input.getValue().isBlank()) canvas.text(Component.translatable("worldgit.ignore.syntax_ok"), 14, syntaxY, 0xFF4CD964);
  }

  private static String n(long value) { return String.format(Locale.ROOT, "%,d", value); }
}
