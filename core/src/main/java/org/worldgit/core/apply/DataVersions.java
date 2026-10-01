package org.worldgit.core.apply;

import java.io.IOException;

/** 不得以改寫 DataVersion 數字冒充 DataFixer 升級。 */
public final class DataVersions {
  private DataVersions() {}

  public static void requireSame(int target, int current) throws IOException {
    if (target <= 0 || current <= 0) throw new IOException("缺少有效的 mcDataVersion");
    if (target > current) throw new IOException("目標 mcDataVersion=" + target
        + " 比目前世界=" + current + " 新；禁止降級寫回。");
    if (target < current) throw new IOException("目標 mcDataVersion=" + target
        + " 比目前世界=" + current + " 舊；尚未提供可靠的快照 DataFixer 升級。"
        + "請先用相同版本還原至世界複本，再以新版伺服器升級並重新 commit。");
  }
}
