package org.worldgit.paper;

import java.util.List;
import org.bukkit.World;
import org.worldgit.core.anvil.Nbt;

/**
 * 需要伺服器內部程式碼（NMS）的全部操作；每個 Minecraft 版本一個薄轉接層（paper:v1_21_11、paper:v26_2）實作。
 *
 * <p>共用程式碼不得直接碰 NMS。介面刻意只含三件事：普查已載入 chunk（任何執行緒）、在擁有該 chunk 的執行緒複製、
 * 把複製結果轉成中性 NBT（任何執行緒，通常在背景）。其餘（排程、事件、指令）都在版本無關的程式碼裡。
 */
public interface NmsBridge {
  /** 轉接層對應的 Minecraft 版本，例如 1.21.11。 */
  String minecraftVersion();

  /** 普查結果的接收端；任何執行緒呼叫。 */
  interface CensusSink {
    /**
     * @param unsaved 原始 {@code ChunkAccess.unsaved} 旗標（不是會被排程 tick 汙染的 isUnsaved()）
     * @param hasEntities 該 chunk 的 entity storage 目前有實體
     */
    void chunk(int x, int z, boolean unsaved, boolean hasEntities);
  }

  /**
   * 列舉世界中目前已載入且已完成（FULL）的 chunk。可由任何執行緒呼叫（讀 volatile 旗標與並行表，不進入 region 執行緒），
   * 所以可以放在背景定時工作。回傳的是即時近似；呼叫端只能把它當候選，最終以內容雜湊為準。
   */
  void census(World world, CensusSink sink);

  /** 世界級 gamerule 的獨立 NBT 複本（全域排程器；不讀取實體或 chunk）。 */
  Nbt.Compound copyGameRules(World world) throws java.io.IOException;

  /** 複製出來、尚未編碼的 chunk 資料。 */
  interface RawChunk {
    int x();

    int z();

    /** 與 region 檔相同結構的 chunk NBT。編碼在呼叫端的背景執行緒完成，不佔 tick。 */
    Nbt.Compound terrain() throws java.io.IOException;

    /** 與 entities 檔相同結構的實體 NBT 清單（已在複製當下序列化，因為實體只能在擁有執行緒讀）。 */
    List<Nbt.Compound> entities() throws java.io.IOException;
  }

  /**
   * 在<strong>擁有該 chunk 的執行緒</strong>（Paper 主執行緒／Folia region 執行緒）複製。chunk 未載入或尚未 FULL 時回傳 null。
   * 只做複製（palette container、方塊實體 NBT、實體 NBT）；其餘編碼放在 {@link RawChunk} 的方法裡。
   */
  RawChunk copy(World world, int x, int z);

  /** 以下全部由真正 owner 呼叫，IO barrier 除外。 */
  void applyChunk(World world, org.worldgit.core.apply.ApplyPlan.ChunkOp op,
      org.worldgit.core.config.IgnoreRules rules) throws java.io.IOException;
  void removeEntities(World world, int x, int z, java.util.Set<java.util.UUID> ids);
  void spawnEntity(World world, org.worldgit.core.model.EntitySnapshot entity) throws java.io.IOException;
  java.util.concurrent.CompletionStage<Void> finishChunk(World world, int x, int z);
  void saveRegion(World world);
  /** repo 背景執行緒等待 terrain/entity/POI IO 強制落盤。 */
  default void saveChunk(World world, int x, int z) { saveRegion(world); }
  void flushIo(World world);
  /** 全域 scheduler，保存／恢復 vanilla freeze 與 step 狀態。 */
  AutoCloseable freeze(World world);
  /** 真正 region ID/current tick；Paper 使用 server tick。 */
  record OwnerTick(long owner, long tick) {}
  OwnerTick ownerTick(World world);

}
