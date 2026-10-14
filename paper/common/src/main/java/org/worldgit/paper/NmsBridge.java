package org.worldgit.paper;

import java.util.List;
import org.bukkit.World;
import org.worldgit.core.anvil.Nbt;

/**
 * 需要伺服器內部程式碼（NMS）的全部操作；每個精確 Minecraft 版本一個薄轉接層實作。
 *
 * <p>共用程式碼不得直接碰 NMS。介面刻意只含三件事：普查已載入 chunk（任何執行緒）、在擁有該 chunk 的執行緒複製、
 * 把複製結果轉成中性 NBT（任何執行緒，通常在背景）。其餘（排程、事件、指令）都在版本無關的程式碼裡。
 */
public interface NmsBridge {
  /** 轉接層對應的 Minecraft 版本，例如 1.21.11。 */
  String minecraftVersion();
  int dataVersion();
  org.worldgit.core.version.NbtUpgrader dataFixer();
  /** 世界的不可變平台身分；版本新增的 SavedData 投影不能借用另一個世界的 UUID。 */
  default org.worldgit.core.version.NbtUpgrader dataFixer(World world) { return dataFixer(); }
  /** Player owner 操作；InventoryView 的 class／interface 邊界留在該版 API。 */
  default void openMenu(org.bukkit.entity.Player player,org.bukkit.inventory.Inventory inventory) {
    throw new UnsupportedOperationException("Inventory menus unavailable");
  }
  /** 驗收在 player owner 注入 GUI 點擊；不讓 common 引用 InventoryView。 */
  default void clickOpenMenu(org.bukkit.entity.Player player,int slot) {
    throw new UnsupportedOperationException("Inventory clicks unavailable");
  }
  /** 生成前配置；各版 API 的 Consumer descriptor 不同，common 使用中性 Java Consumer。 */
  default <T extends org.bukkit.entity.Entity> T spawnConfigured(org.bukkit.Location location,
      Class<T> type, java.util.function.Consumer<T> configure) {
    throw new UnsupportedOperationException("Configured entity spawn unavailable");
  }
  /** 各版已核對的世界存檔／RegionFile IO 完成訊號；只讀取 log，不碰活世界。 */
  default boolean shutdownIoComplete(String loggerName, String message) {
    return message.contains("All RegionFile I/O tasks to complete");
  }
  /** Verified Folia native save point, with a bound region and before its save. */
  default boolean shutdownRegionSave(String loggerName,String message) { return false; }
  default int restoreShutdownGuards(World world) { throw new UnsupportedOperationException("Shutdown owner cleanup unavailable"); }
  default int pendingShutdownGuards() { return 0; }
  /** Server entity manager census, including known but unloaded UUIDs. */
  java.util.Set<java.util.UUID> knownEntityIds(World world);
  default java.util.function.Function<String,com.mojang.brigadier.arguments.ArgumentType<?>> commandTypes(){return CommandSource.LEGACY_TYPES::type;}
  default CommandSource commandSource(org.bukkit.command.CommandSender sender){throw new UnsupportedOperationException("Legacy commands unavailable");}


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

  /** 活世界的 level 設定（全域 scheduler）；只有 adapter 已確認的受追蹤欄位。 */
  default Nbt.Compound copyLevelMetadata(World world) throws java.io.IOException { return new Nbt.Compound(); }

  default boolean savesMetadata() { return false; }

  /** 全域 scheduler 複製維度 saved-data 並排入背景 IO；呼叫端在 repo 執行緒等待完成。 */
  default java.util.concurrent.CompletionStage<Void> saveMetadata(World world) {
    return java.util.concurrent.CompletableFuture.completedFuture(null);
  }

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
  /** 真正 chunk owner 上停用此 chunk 的 vanilla 模擬；保留 IO、光照與世界時間。 */
  default void wakeBoundary(World world,int x,int y,int z,String sourceBlock) {}
  default boolean atomicLightingSafe(World world,org.worldgit.core.apply.ApplyPlan plan) throws java.io.IOException {return false;}
  AutoCloseable lockChunkTicks(World world, int x, int z);
  /** 套用新增的 section／BE／entity 必須在返回遊戲迴圈前加入同一鎖。 */
  void refreshChunkLock(World world, int x, int z);
  default void entityBoundary(World world,java.util.Set<org.worldgit.core.model.ChunkPos> scope) {}
  default void guardEntities(World world,int x,int z) {}
  default void guardEntity(org.bukkit.entity.Entity entity) {}
  default java.util.Collection<org.bukkit.entity.Entity> clearEntityBoundary(World world) {return java.util.List.of();}
  default void restoreEntity(org.bukkit.entity.Entity entity) {}
  /** 真正 region ID/current tick；Paper 使用 server tick。 */
  record OwnerTick(long owner, long tick) {}
  OwnerTick ownerTick(World world);

}
