package wg.poc;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Furnace;
import org.bukkit.block.Sign;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** section 替換 PoC 的指令實作（sec ...）與「未載入 chunk 走 chunk IO」的 PoC（io ...）。 */
public final class Section {
    private Section() {}
    static final int SY = 11; // y 176..191
    static final Map<String, Sect.Snap> snaps = new HashMap<>();

    static int[][] SAMPLES = {{5, 175, 5}, {5, 180, 5}, {2, 191, 0}, {4, 192, 4}, {3, 190, 3}, {8, 190, 8}};

    static void cmd(String[] a) {
        World w = Tests.world();
        String op = a[1];
        // 參數位置：prep <cx> <cz> | snap <name> <cx> <cz> | apply <pat> <cx> <cz> <notify> | report <cx> <cz> [tag]
        switch (op) {
            case "prep" -> { int x = Integer.parseInt(a[2]), z = Integer.parseInt(a[3]); Sched.at(w, x, z, () -> prep(w, x, z)); }
            case "snap" -> { String n = a[2]; int x = Integer.parseInt(a[3]), z = Integer.parseInt(a[4]);
                Sched.at(w, x, z, () -> { ServerLevel l = Nms.level(w); LevelChunk c = Nms.chunkNow(l, x, z); Sect.Snap s = Sect.snapshot(l, c, SY); snaps.put(n, s);
                    Out.res("sec_snap", "name", n, "hash", Sect.hash(l, c, SY), "beCount", s.be.size()); }); }
            case "apply" -> { String pat = a[2]; int x = Integer.parseInt(a[3]), z = Integer.parseInt(a[4]); Sect.Notify nt = Sect.Notify.valueOf(a[5]);
                Sched.at(w, x, z, () -> apply(w, x, z, pat, nt)); }
            case "report" -> { int x = Integer.parseInt(a[2]), z = Integer.parseInt(a[3]); String tag = a.length > 4 ? a[4] : "";
                Sched.at(w, x, z, () -> report(w, x, z, tag)); }
            case "dump" -> { int x = Integer.parseInt(a[2]), z = Integer.parseInt(a[3]); String tag = a[4];
                Sched.at(w, x, z, () -> { LevelChunk c = Nms.chunkNow(Nms.level(w), x, z); StringBuilder sb = new StringBuilder();
                    var sec = c.getSection(Sect.idx(c, SY));
                    for (int y = 0; y < 16; y++) for (int zz = 0; zz < 16; zz++) for (int xx = 0; xx < 16; xx++)
                        sb.append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(sec.getBlockState(xx, y, zz).getBlock()).getPath()).append(';');
                    try { java.nio.file.Files.writeString(java.nio.file.Path.of("plugins/WorldGitPoc/dump-" + tag + ".txt"), sb.toString()); } catch (Exception e) { Out.res("error", "dump", e.toString()); }
                    Out.res("sec_dump", "tag", tag); }); }
            case "disk" -> { int x = Integer.parseInt(a[2]), z = Integer.parseInt(a[3]); disk(w, x, z); }
            case "clearflag" -> { int x = Integer.parseInt(a[2]), z = Integer.parseInt(a[3]);
                Sched.at(w, x, z, () -> Out.res("sec_clearflag", "was", Nms.chunkNow(Nms.level(w), x, z).tryMarkSaved())); }
            default -> Out.res("error", "sec?", op);
        }
    }

    static void prep(World w, int cx, int cz) {
        Tests.floor(w, cx, cz);
        int bx = cx * 16, bz = cz * 16;
        Block b = w.getBlockAt(bx + 2, 190, bz + 2); b.setType(Material.CHEST);
        ((Chest) b.getState()).getBlockInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        b = w.getBlockAt(bx + 4, 190, bz + 2); b.setType(Material.OAK_SIGN);
        Sign sg = (Sign) b.getState(); sg.getSide(org.bukkit.block.sign.Side.FRONT).line(0, net.kyori.adventure.text.Component.text("S0-sign")); sg.update();
        b = w.getBlockAt(bx + 6, 190, bz + 2); b.setType(Material.FURNACE);
        Furnace f = (Furnace) b.getState(); f.getInventory().setFuel(new ItemStack(Material.COAL, 2)); f.update();
        w.getBlockAt(bx + 8, 190, bz + 8).setType(Material.GLOWSTONE);
        w.getBlockAt(bx + 10, 190, bz + 10).setType(Material.SAND);
        w.getBlockAt(bx + 10, 191, bz + 10).setType(Material.SAND);
        w.getBlockAt(bx + 12, 190, bz + 12).setType(Material.OAK_STAIRS);
        w.refreshChunk(cx, cz); // floor() 用 flags=0 直接寫 chunk，不會送封包；重送讓 bot 看到
        Out.res("sec_prep", "cx", cx, "cz", cz);
    }

    static BlockState p1(int[] q) {
        int x = q[0], y = q[1], z = q[2];
        if (y <= 13) return Blocks.STONE.defaultBlockState();
        if (y == 14) { if (x == 2 && z == 3) return Blocks.CHEST.defaultBlockState(); if (x == 6 && z == 7) return Blocks.BARREL.defaultBlockState();
            return ((x + z) % 2 == 0) ? Blocks.GLASS.defaultBlockState() : Blocks.AIR.defaultBlockState(); }
        return (x % 4 == 0 && z % 4 == 0) ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }
    static BlockState p2(int[] q) {
        int y = q[1];
        if (y < 8) return Blocks.DIRT.defaultBlockState();
        if (y < 10) return Blocks.WATER.defaultBlockState();
        return Blocks.AIR.defaultBlockState();
    }

    static void apply(World w, int cx, int cz, String pat, Sect.Notify nt) {
        ServerLevel l = Nms.level(w); LevelChunk c = Nms.chunkNow(l, cx, cz);
        c.tryMarkSaved();
        boolean f0 = Nms.rawUnsaved(c);
        Map<String, Object> r;
        switch (pat) {
            case "P1" -> r = Sect.replace(l, c, SY, Sect.build(l, c, SY, Section::p1), Map.of(), nt);
            case "P2" -> r = Sect.replace(l, c, SY, Sect.build(l, c, SY, Section::p2), Map.of(), nt);
            default -> { Sect.Snap s = snaps.get(pat); r = Sect.replace(l, c, SY, s.states.copy(), s.be, nt); }
        }
        Out.res("sec_apply", "pattern", pat, "flagBefore", f0, "flagAfter", Nms.rawUnsaved(c), "stats", r, "hash", Sect.hash(l, c, SY));
    }

    static void report(World w, int cx, int cz, String tag) {
        ServerLevel l = Nms.level(w); LevelChunk c = Nms.chunkNow(l, cx, cz);
        List<Object> lights = new ArrayList<>();
        for (int[] s : SAMPLES) {
            Block b = w.getBlockAt(cx * 16 + s[0], s[1], cz * 16 + s[2]);
            lights.add(Out.m("pos", s[0] + "," + s[1] + "," + s[2], "block", b.getLightFromBlocks(), "sky", b.getLightFromSky(), "type", b.getType().getKey().getKey()));
        }
        int be = 0; List<String> bl = new ArrayList<>();
        for (BlockPos p : c.getBlockEntitiesPos()) if ((p.getY() >> 4) == SY) { be++; bl.add(p.toShortString()); }
        Out.res("sec_report", "tag", tag, "hash", Sect.hash(l, c, SY), "nameHash", Sect.nameHash(c, SY), "beCount", be, "bePos", bl, "lights", lights, "flag", Nms.rawUnsaved(c));
    }

    /** 從磁碟（透過 chunk IO）讀回 chunk NBT，列出 section SY 的 palette 與 block entity 數。 */
    static void disk(World w, int cx, int cz) {
        ServerLevel l = Nms.level(w);
        Sched.async(() -> {
            try {
                CompoundTag tag = MoonriseRegionFileIO.loadData(l, cx, cz, MoonriseRegionFileIO.RegionFileType.CHUNK_DATA, Priority.NORMAL);
                ListTag secs = tag.getList("sections").orElseThrow();
                List<String> names = new ArrayList<>();
                for (int i = 0; i < secs.size(); i++) {
                    CompoundTag sec = secs.getCompound(i).orElseThrow();
                    if (sec.getByte("Y").orElse((byte) 99) != SY) continue;
                    sec.getCompound("block_states").flatMap(bs -> bs.getList("palette")).ifPresent(pal -> {
                        for (int k = 0; k < pal.size(); k++) names.add(pal.getCompound(k).orElseThrow().getString("Name").orElse("?"));
                    });
                }
                int be = 0; ListTag bes = tag.getList("block_entities").orElse(new ListTag());
                for (int i = 0; i < bes.size(); i++) if ((bes.getCompound(i).orElseThrow().getInt("y").orElse(0) >> 4) == SY) be++;
                Out.res("sec_disk", "cx", cx, "cz", cz, "palette", names, "beInSection", be, "regionTs", Tests.regionTs(w, cx, cz, "region"), "nowSec", System.currentTimeMillis() / 1000);
            } catch (Throwable t) { Out.res("error", "sec_disk", Tests.trace(t)); }
        });
    }

    /** 診斷：磁碟上 chunk NBT 各主要欄位的雜湊，用來找出「存檔之間是什麼在變」。 */
    static void diskSig(int cx, int cz) {
        World w = Tests.world(); ServerLevel l = Nms.level(w);
        Sched.async(() -> {
            try {
                CompoundTag tag = MoonriseRegionFileIO.loadData(l, cx, cz, MoonriseRegionFileIO.RegionFileType.CHUNK_DATA, Priority.NORMAL);
                Map<String, Object> m = new TreeMap<>();
                for (String k : tag.keySet()) {
                    var t = tag.get(k);
                    String str = t.toString();
                    m.put(k, (t instanceof NumericTag ? str : Integer.toHexString(str.hashCode()) + "/" + str.length()));
                }
                // 把 sections 拆成「方塊(block_states)」「光照(BlockLight/SkyLight/starlight.*)」「其他(biomes)」三種雜湊
                StringBuilder bsb = new StringBuilder(), lsb = new StringBuilder(), osb = new StringBuilder();
                ListTag secs2 = tag.getList("sections").orElse(new ListTag());
                for (int i = 0; i < secs2.size(); i++) {
                    CompoundTag sc = secs2.getCompound(i).orElseThrow();
                    for (String k : sc.keySet()) {
                        String v = sc.get(k).toString();
                        if (k.equals("block_states")) bsb.append(sc.getByte("Y").orElse((byte) 0)).append(v);
                        else if (k.equals("BlockLight") || k.equals("SkyLight") || k.startsWith("starlight")) lsb.append(sc.getByte("Y").orElse((byte) 0)).append(k).append(v);
                        else if (!k.equals("Y")) osb.append(k).append(v);
                    }
                }
                m.put("~blockStates", Integer.toHexString(bsb.toString().hashCode()));
                m.put("~light", Integer.toHexString(lsb.toString().hashCode()));
                m.put("~otherSectionFields", Integer.toHexString(osb.toString().hashCode()));
                Out.res("disksig", "cx", cx, "cz", cz, "fields", m, "regionTs", Tests.regionTs(w, cx, cz, "region"));
            } catch (Throwable t) { Out.res("error", "disksig", Tests.trace(t)); }
        });
    }

    // ---------- 未載入 chunk：透過 Moonrise chunk IO 讀出→改→寫回 ----------
    static void io(int cx, int cz, int sy, String block, String variant) {
        World w = Tests.world(); ServerLevel l = Nms.level(w);
        Sched.async(() -> {
            try {
                boolean loadedBefore = Nms.chunkNow(l, cx, cz) != null; boolean holderBefore = Nms.hasChunkHolder(l, cx, cz);
                long t0 = System.nanoTime();
                CompoundTag tag = MoonriseRegionFileIO.loadData(l, cx, cz, MoonriseRegionFileIO.RegionFileType.CHUNK_DATA, Priority.NORMAL);
                if (tag == null) { Out.res("io", "err", "no chunk data", "loadedBefore", loadedBefore); return; }
                long readMs = (System.nanoTime() - t0) / 1_000_000;
                String status = tag.getString("Status").orElse("?");
                ListTag secs = tag.getList("sections").orElseThrow();
                int found = -1;
                for (int i = 0; i < secs.size(); i++) if (secs.getCompound(i).orElseThrow().getByte("Y").orElse((byte) 99) == sy) found = i;
                CompoundTag sec = found >= 0 ? secs.getCompound(found).orElseThrow() : new CompoundTag();
                String oldFirst = sec.getCompound("block_states").flatMap(bs -> bs.getList("palette")).map(p -> p.toString()).orElse("none");
                CompoundTag bs = new CompoundTag(); ListTag pal = new ListTag(); CompoundTag e = new CompoundTag(); e.putString("Name", "minecraft:" + block); pal.add(e); bs.put("palette", pal);
                sec.put("block_states", bs);
                if (!variant.equals("min")) { sec.remove("BlockLight"); sec.remove("SkyLight"); }
                if (found < 0) { sec.putByte("Y", (byte) sy); secs.add(sec); }
                if (!variant.equals("min")) { tag.remove("isLightOn"); tag.remove("Heightmaps"); } // 讓載入時重算光照與 heightmap
                // 移除該 section 內舊的 block entity（NBT 層要自己處理）
                ListTag bes = tag.getList("block_entities").orElse(new ListTag());
                ListTag keep = new ListTag(); int dropped = 0;
                for (int i = 0; i < bes.size(); i++) { CompoundTag be = bes.getCompound(i).orElseThrow(); if ((be.getInt("y").orElse(0) >> 4) == sy) dropped++; else keep.add(be); }
                tag.put("block_entities", keep);
                MoonriseRegionFileIO.scheduleSave(l, cx, cz, tag, MoonriseRegionFileIO.RegionFileType.CHUNK_DATA);
                MoonriseRegionFileIO.flush(l);
                long tsAfter = Tests.regionTs(w, cx, cz, "region");
                // 再讀一次確認寫進去了
                CompoundTag again = MoonriseRegionFileIO.loadData(l, cx, cz, MoonriseRegionFileIO.RegionFileType.CHUNK_DATA, Priority.NORMAL);
                String pal2 = again.getList("sections").orElseThrow().getCompound(Math.max(found, 0)).flatMap(s -> s.getCompound("block_states")).flatMap(s2 -> s2.getList("palette")).map(Object::toString).orElse("?");
                Out.res("io_written", "cx", cx, "cz", cz, "sy", sy, "loadedBefore", loadedBefore, "holderBefore", holderBefore, "holderAfterWrite", Nms.hasChunkHolder(l, cx, cz), "status", status, "readMs", readMs, "oldPalette", oldFirst.length() > 200 ? oldFirst.substring(0, 200) : oldFirst,
                        "beDropped", dropped, "reReadPalette", pal2, "regionTs", tsAfter, "nowSec", System.currentTimeMillis() / 1000, "loadedAfterWrite", Nms.chunkNow(l, cx, cz) != null);
                // 現在正常載入 chunk，確認內容
                w.getChunkAtAsync(cx, cz).thenAccept(ch -> { w.addPluginChunkTicket(cx, cz, PocPlugin.get()); Sched.at(w, cx, cz, () -> {
                    Block b = w.getBlockAt(cx * 16 + 3, sy * 16 + 3, cz * 16 + 3);
                    LevelChunk lc = Nms.chunkNow(l, cx, cz);
                    int cnt = 0; for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                        if (lc.getSection(Sect.idx(lc, sy)).getBlockState(x, y, z).getBlock() == Blocks.GLASS) cnt++;
                    Out.res("io_count", "glassInSection", cnt, "status", String.valueOf(lc.getPersistedStatus()));
                    Out.res("io_loaded", "cx", cx, "cz", cz, "blockAtSection", b.getType().getKey().getKey(), "expected", block, "unsavedAfterLoad", lc != null && Nms.rawUnsaved(lc), "effectiveUnsavedAfterLoad", Nms.effectiveUnsaved(w, cx, cz),
                            "skyLightAbove", w.getBlockAt(cx * 16 + 3, sy * 16 + 16, cz * 16 + 3).getLightFromSky());
                    w.removePluginChunkTicket(cx, cz, PocPlugin.get());
                }); });
            } catch (Throwable t) { Out.res("io", "err", Tests.trace(t)); }
        });
    }
}
