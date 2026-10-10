package org.worldgit.core.normalize;

import java.io.IOException;
import org.worldgit.core.anvil.Nbt;

/**
 * 排程 tick（{@code block_ticks}／{@code fluid_ticks}）的語意（決策 #169）。
 *
 * <p>{@code t} 是「剩餘延遲」，存檔時為 {@code triggerTick - gameTime}。不在 tick 範圍內的已載入 chunk 不會處理排程，
 * 遊戲時間卻持續前進，所以 {@code t} 會變成隨時間越來越小的負值（已到期、等 chunk 恢復 tick 就立刻執行）。負值沒有額外的資訊，
 * 只讓同一份內容在不同時間擷取出不同的 bytes。因此一律夾到 {@code max(t, 0)}：0 代表「已到期」，恢復 tick 後立刻執行；
 * 正值是尚未到期的相對延遲。
 *
 * <p>排程 tick 隨方塊一起追蹤與還原，不單獨構成差異（見 DiffEngine／ApplyPlanner／MergeEngine）。
 */
public final class TickSemantics {
  private TickSemantics() {}

  public static final java.util.List<String> KEYS = java.util.List.of("block_ticks", "fluid_ticks");

  /** 夾住負的剩餘延遲；回傳是否有改動。就地修改。 */
  public static boolean clamp(Nbt.Compound ticks) {
    boolean changed = false;
    for (String key : KEYS)
      for (Object value : ticks.list(key).values()) {
        var tick = (Nbt.Compound) value;
        int t = tick.integer("t", 0);
        if (t < 0) {
          tick.put("t", 0);
          changed = true;
        }
      }
    return changed;
  }

  /** 對正規化後的 ticks NBT bytes 夾住負延遲；沒有負值時回傳同一個陣列。 */
  public static byte[] clamp(byte[] nbt) throws IOException {
    if (nbt == null) return null;
    var ticks = Nbt.read(nbt);
    return clamp(ticks) ? Nbt.write(ticks) : nbt;
  }
}
