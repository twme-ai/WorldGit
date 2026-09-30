package wg.poc;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.block.data.BlockData;

/** 小型 NMS 輔助。全部以 Mojang 名稱編譯（對 1.21.11 的 patched server），在 26.2 上直接執行。 */
public final class Nms {
    private Nms() {}
    public static ServerLevel level(World w) { return ((CraftWorld) w).getHandle(); }
    /** 已載入（full）的 chunk，不載入；可自任何執行緒呼叫（Moonrise 的 fullChunks 是並行表）。 */
    public static LevelChunk chunkNow(ServerLevel l, int cx, int cz) { return l.getChunkSource().getChunkNow(cx, cz); }
    // ---- unsaved 旗標 ----
    // LevelChunk.isUnsaved() 並不只是旗標：Moonrise/Paper 把它覆寫成「blockTicks.isDirty(gameTime) || fluidTicks.isDirty(gameTime) || ChunkAccess.unsaved」，
    // 只要 chunk 內有未完成的排程 tick（流動的水、紅石…）就永遠回傳 true，存檔後下一 tick 又變 true；而且 Folia 上 isUnsaved() 需要 region 執行緒
    // （getGameTime 讀 thread-local 的 region 資料，否則 NPE）。所以「只看方塊/BE 是否被改」要直接讀 ChunkAccess.unsaved 這個 volatile 欄位。
    private static final java.lang.invoke.VarHandle UNSAVED;
    static {
        try {
            var l = java.lang.invoke.MethodHandles.privateLookupIn(net.minecraft.world.level.chunk.ChunkAccess.class, java.lang.invoke.MethodHandles.lookup());
            UNSAVED = l.findVarHandle(net.minecraft.world.level.chunk.ChunkAccess.class, "unsaved", boolean.class);
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    /** 原始旗標（ChunkAccess.unsaved，volatile，任何執行緒可讀）。 */
    public static boolean rawUnsaved(net.minecraft.world.level.chunk.ChunkAccess c) { return (boolean) UNSAVED.getVolatile(c); }
    /** 原始旗標；chunk 未載入回傳 false。 */
    public static boolean unsaved(World w, int cx, int cz) {
        LevelChunk c = chunkNow(level(w), cx, cz);
        return c != null && rawUnsaved(c);
    }
    /** 有效旗標（LevelChunk.isUnsaved()，含排程 tick 的 dirty）；Folia 上必須在擁有該 chunk 的 region 執行緒呼叫，否則回傳 null。 */
    public static Boolean effectiveUnsaved(World w, int cx, int cz) {
        LevelChunk c = chunkNow(level(w), cx, cz);
        if (c == null) return false;
        try { return c.isUnsaved(); } catch (Throwable t) { return null; }
    }
    /** Moonrise 的 ChunkHolder（任何狀態，包含只有 proto chunk 的）是否存在；存在就代表 chunk 系統的記憶體裡有這個 chunk。 */
    public static boolean hasChunkHolder(ServerLevel l, int cx, int cz) {
        return l.moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(cx, cz) != null;
    }
    // ---- 版本差異：BlockState.getLightBlock()（1.21.11）在 26.2 改名為 getLightDampening()；其餘本 PoC 用到的 NMS 簽章兩版相同 ----
    private static final java.lang.invoke.MethodHandle LIGHT_DAMP;
    static {
        java.lang.invoke.MethodHandle h = null;
        var lk = java.lang.invoke.MethodHandles.lookup();
        var mt = java.lang.invoke.MethodType.methodType(int.class);
        for (String n : new String[]{"getLightDampening", "getLightBlock"}) {
            try { h = lk.findVirtual(BlockState.class, n, mt); break; } catch (ReflectiveOperationException ignored) {}
        }
        LIGHT_DAMP = h;
    }
    public static int lightDampening(BlockState s) {
        try { return (int) LIGHT_DAMP.invoke(s); } catch (Throwable t) { throw new RuntimeException(t); }
    }
    /** Folia 沒有 /save-all；用 Moonrise 的 ChunkHolderManager 直接存（參數 flush, shutdown, logProgress, saveEntitiesAndPoi? 見 REPORT）。 */
    public static void saveAllChunksNms(ServerLevel l, boolean flush) {
        l.moonrise$getChunkTaskScheduler().chunkHolderManager.saveAllChunks(flush, false, false, true);
    }
    public static BlockState state(BlockData d) { return ((CraftBlockData) d).getState(); }
}
