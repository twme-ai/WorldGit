package org.worldgit.fabric.client;

import java.util.List;
import net.minecraft.network.chat.Component;

/**
 * 模組客戶端的進度 overlay：階段、維度、百分比或不定進度、ETA；完成後顯示終態（成功綠、部分黃、失敗紅）數秒。
 * 原版客戶端看不到這個 overlay，但仍有伺服器端的 BossBar。
 */
final class ProgressHud {
  private ProgressHud() {}

  static final int WIDTH = 300, ROW = 40;

  /** 回傳需要的列數（測試與截圖用）。 */
  static int render(Canvas canvas, List<ClientUi.Hud> items, int guiWidth, long now) {
    // 窄畫面下將 overlay 放在最多四條 vanilla BossBar 的下方。
    int x = 6, y = guiWidth < 820 ? 96 : 6;
    for (var hud : items) {
      canvas.fill(x, y, x + WIDTH, y + ROW - 4, 0xD0101820);
      canvas.fill(x, y, x + 2, y + ROW - 4, color(hud));
      canvas.textLimited(title(hud), x + 8, y + 4, 0xFFFFFFFF, WIDTH - 16);
      var detail = detail(hud);
      canvas.textLimited(detail, x + 8, y + 16, hud.terminal() ? 0xFFE8F4FF : 0xFFB8D4EA, WIDTH - 16);
      int barX = x + 8, barY = y + 29, barW = WIDTH - 16;
      canvas.fill(barX, barY, barX + barW, barY + 5, 0xFF2A3440);
      if (hud.terminal()) canvas.fill(barX, barY, barX + barW, barY + 5, color(hud));
      else if (hud.total() > 0) canvas.fill(barX, barY, barX + (int) (barW * Math.min(1.0, (double) hud.completed() / hud.total())), barY + 5, color(hud));
      else {
        int segment = barW / 4, offset = (int) ((now / 12) % (barW + segment)) - segment;
        canvas.fill(barX + Math.max(0, offset), barY, barX + Math.min(barW, offset + segment), barY + 5, color(hud));
      }
      y += ROW;
    }
    return items.size();
  }

  static int color(ClientUi.Hud hud) {
    return switch (hud.status()) {
      case SUCCESS, NO_OP -> 0xFF4CD964;
      case PARTIAL -> 0xFFFFC83D;
      case FAILED, CANCELLED -> 0xFFFF5A5A;
      case RUNNING -> 0xFF4FA8FF;
    };
  }

  static Component title(ClientUi.Hud hud) {
    return Component.translatable("worldgit.hud.title", hud.operation(), hud.dimension().isEmpty() ? "—" : hud.dimension());
  }

  static Component detail(ClientUi.Hud hud) {
    if (hud.terminal()) {
      var status = Component.translatable("worldgit.hud.status." + hud.status().name().toLowerCase(java.util.Locale.ROOT));
      return hud.text().isBlank() ? Component.translatable("worldgit.hud.done", status, hud.elapsedMillis())
          : Component.translatable("worldgit.hud.done_text", status, hud.text(), hud.elapsedMillis());
    }
    var phase = Component.translatableWithFallback("worldgit.hud.phase." + hud.phase(), hud.phase());
    if (hud.total() <= 0) return Component.translatable("worldgit.hud.indeterminate", phase, number(hud.completed()), unit(hud.unit()));
    int percent = (int) Math.min(100, hud.completed() * 100 / hud.total());
    String eta = hud.etaMillis() < 0 ? "—" : Math.max(0, hud.etaMillis() / 1000) + "s";
    return Component.translatable("worldgit.hud.progress", phase, percent, number(hud.completed()), number(hud.total()), unit(hud.unit()), eta);
  }

  private static String number(long value) { return String.format(java.util.Locale.ROOT, "%,d", value); }

  private static Component unit(int ordinal) {
    String[] names = {"chunk", "section", "object", "bytes", "commit"};
    return Component.translatable("worldgit.hud.unit." + names[Math.min(ordinal, names.length - 1)]);
  }
}
