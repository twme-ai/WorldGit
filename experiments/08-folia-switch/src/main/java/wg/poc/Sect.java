package wg.poc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 已載入 chunk 的 section 快照與整段替換（docs/05 §1 的「已載入 → 直接替換 section 內容」）。 */
public final class Sect {
    private Sect() {}

    /** section 快照：block state 容器複本 + block entity 的完整 NBT。 */
    public static final class Snap {
        public PalettedContainer<BlockState> states;
        public final Map<BlockPos, CompoundTag> be = new LinkedHashMap<>();
    }

    public enum Notify { NONE, BLOCKS, RESEND }

    public static int idx(LevelChunk c, int sy) { return c.getSectionIndexFromSectionY(sy); }

    public static Snap snapshot(ServerLevel level, LevelChunk c, int sy) {
        Snap s = new Snap();
        s.states = c.getSection(idx(c, sy)).getStates().copy();
        for (BlockPos p : new ArrayList<>(c.getBlockEntitiesPos())) {
            if ((p.getY() >> 4) != sy) continue;
            CompoundTag t = c.getBlockEntityNbtForSaving(p, level.registryAccess());
            if (t != null) s.be.put(p.immutable(), t);
        }
        return s;
    }

    /** 建立一個新的 states 容器並用 fn 逐格填入。 */
    public static PalettedContainer<BlockState> build(ServerLevel level, LevelChunk c, int sy, java.util.function.Function<int[], BlockState> fn) {
        PalettedContainer<BlockState> pc = c.getSection(idx(c, sy)).getStates().recreate();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) pc.set(x, y, z, fn.apply(new int[]{x, y, z}));
        return pc;
    }

    /** 內容雜湊（block state 字串 + BE NBT），用來驗證「還原後與原本一致」。 */
    public static String hash(ServerLevel level, LevelChunk c, int sy) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            LevelChunkSection sec = c.getSection(idx(c, sy));
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                md.update(sec.getBlockState(x, y, z).toString().getBytes(StandardCharsets.UTF_8));
            TreeMap<String, String> bes = new TreeMap<>();
            for (BlockPos p : c.getBlockEntitiesPos()) if ((p.getY() >> 4) == sy) {
                CompoundTag t = c.getBlockEntityNbtForSaving(p, level.registryAccess());
                bes.put(p.toShortString(), t == null ? "null" : t.toString());
            }
            for (var e : bes.entrySet()) md.update((e.getKey() + "=" + e.getValue()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** 只有材質名稱的雜湊（與 bot 端的 blockAt(...).name 對照；順序 y,z,x）。 */
    public static String nameHash(LevelChunk c, int sy) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            LevelChunkSection sec = c.getSection(idx(c, sy));
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                md.update((net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(sec.getBlockState(x, y, z).getBlock()).getPath() + ";").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /**
     * 替換整個 section（必須在擁有該 chunk 的執行緒上呼叫）。
     * 步驟：移除舊 BE → 換 section 物件 → 重建 BE → heightmap → POI → 光照 → 標記 unsaved → 通知玩家。
     */
    public static Map<String, Object> replace(ServerLevel level, LevelChunk c, int sy, PalettedContainer<BlockState> newStates,
                                              Map<BlockPos, CompoundTag> newBe, Notify notify) {
        long t0 = System.nanoTime();
        int i = idx(c, sy);
        LevelChunkSection old = c.getSections()[i];
        BlockState[] before = new BlockState[4096];
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) before[(y << 8) | (z << 4) | x] = old.getBlockState(x, y, z);

        // 1. 移除舊 block entity（必須在換 section 之前，removeBlockEntity 會處理 ticker / 註冊）
        int removed = 0;
        for (BlockPos p : new ArrayList<>(c.getBlockEntitiesPos())) if ((p.getY() >> 4) == sy) { c.removeBlockEntity(p); removed++; }

        // 2. 換 section：states 用新容器，biomes 沿用舊的（複本）
        @SuppressWarnings("unchecked")
        PalettedContainer<Holder<Biome>> biomes = ((PalettedContainer<Holder<Biome>>) old.getBiomes()).copy();
        LevelChunkSection ns = new LevelChunkSection(newStates, biomes);
        c.getSections()[i] = ns;

        // 3. 重建 block entity：有 NBT 的用 NBT，其餘 hasBlockEntity 的方塊用預設建立
        int created = 0;
        BlockPos base = new BlockPos(c.getPos().getMinBlockX(), sy << 4, c.getPos().getMinBlockZ());
        List<BlockPos> changed = new ArrayList<>();
        int lightChanged = 0;
        ThreadedLevelLightEngine le = level.getChunkSource().getLightEngine();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            BlockState ns0 = ns.getBlockState(x, y, z), os = before[(y << 8) | (z << 4) | x];
            BlockPos p = base.offset(x, y, z);
            if (ns0.hasBlockEntity()) {
                CompoundTag t = newBe.get(p);
                BlockEntity be = t != null ? BlockEntity.loadStatic(p, ns0, t, level.registryAccess())
                        : c.getBlockEntity(p, LevelChunk.EntityCreationType.IMMEDIATE);
                if (t != null && be != null) c.setBlockEntity(be);
                if (be != null) created++;
            }
            if (ns0 != os) {
                changed.add(p);
                // valid PoiSection.refresh() is a no-op; checkConsistency alone leaves stale workstations.
                var oldPoi = net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(os);
                var newPoi = net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(ns0);
                if (!oldPoi.equals(newPoi)) {
                    if (oldPoi.isPresent()) level.getPoiManager().remove(p);
                    if (newPoi.isPresent()) level.getPoiManager().add(p, newPoi.get());
                }
                if (ns0.getLightEmission() != os.getLightEmission() || Nms.lightDampening(ns0) != Nms.lightDampening(os)
                        || ns0.useShapeForLightOcclusion() || os.useShapeForLightOcclusion()) { le.checkBlock(p); lightChanged++; }
            }
        }
        // 4. heightmap（用 chunk 上已有的種類重算）
        EnumSet<Heightmap.Types> types = EnumSet.noneOf(Heightmap.Types.class);
        for (var e : c.getHeightmaps()) types.add(e.getKey());
        Heightmap.primeHeightmaps(c, types);
        // 5. section 空/非空狀態給光照引擎、POI 一致性
        le.updateSectionStatus(SectionPos.of(c.getPos(), sy), ns.hasOnlyAir());
        level.getPoiManager().checkConsistencyWithBlocks(SectionPos.of(c.getPos(), sy), ns);
        // 6. 直接動 section 物件不會設 unsaved，要自己標
        c.markUnsaved();
        // 7. 讓玩家看到
        int notified = 0;
        if (notify == Notify.BLOCKS) {
            for (BlockPos p : changed) { level.getChunkSource().blockChanged(p); notified++; }
        } else if (notify == Notify.RESEND) {
            level.getWorld().refreshChunk(c.getPos().getMinBlockX() >> 4, c.getPos().getMinBlockZ() >> 4);
        }
        return Out.m("blocksChanged", changed.size(), "beRemoved", removed, "beNow", created, "lightChecks", lightChanged,
                "notified", notified, "notify", notify.name(), "micros", (System.nanoTime() - t0) / 1000);
    }
}
