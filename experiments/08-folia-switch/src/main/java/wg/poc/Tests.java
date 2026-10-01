package wg.poc;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.concurrentutil.util.Priority;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.Piston;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** 所有測試場景與 /wgpoc 子指令。 */
public final class Tests {
    private Tests() {}
    static final int CZ = 1, FLOOR_Y = 189, Y = 190;

    static String trace(Throwable t) {
        StringBuilder sb = new StringBuilder(t.toString());
        for (int i = 0; i < Math.min(8, t.getStackTrace().length); i++) sb.append(" @ ").append(t.getStackTrace()[i]);
        return sb.toString();
    }
    static World world() { return Bukkit.getWorlds().get(0); }
    static Watcher watcher() { return PocPlugin.get().watcher; }

    // ---------- 小工具 ----------
    static final class Seq {
        private final ArrayDeque<Object[]> q = new ArrayDeque<>();
        Seq add(long delay, Runnable r) { q.add(new Object[]{delay, r}); return this; }
        void go() {
            Object[] s = q.poll();
            if (s == null) return;
            Sched.globalDelayed((Long) s[0], () -> {
                try { ((Runnable) s[1]).run(); } catch (Throwable t) { Out.res("error", "where", "seq", "err", trace(t)); }
                go();
            });
        }
    }

    static final boolean FOLIA = isFolia();
    static boolean isFolia() { try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); return true; } catch (Throwable t) { return false; } }
    /** 強制把已載入 chunk 存檔（旗標清掉、region 檔寫入）。Paper：console 的 save-all flush；Folia 沒有 /save-all，改在各 region 執行緒呼叫 Moonrise saveAllChunks。 */
    static void saveAll() {
        if (!FOLIA) { dispatch("save-all flush"); return; }
        World w = world();
        for (int cx = -1; cx <= 11; cx++) for (int cz = -1; cz <= 5; cz++) { final int fx = cx, fz = cz; Sched.at(w, fx, fz, () -> { try { Nms.saveAllChunksNms(Nms.level(w), true); } catch (Throwable t) { Out.res("error", "saveAll", trace(t)); } }); }
    }

    static void dispatch(String cmd) { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd); }

    /** region 檔頭時間戳（秒）；沒有回傳 -1。folder = region | entities | poi。 */
    static long regionTs(World w, int cx, int cz, String folder) {
        try {
            File f = new File(w.getWorldFolder(), folder + "/r." + (cx >> 5) + "." + (cz >> 5) + ".mca");
            if (!f.exists()) return -2;
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                int idx = (cx & 31) + (cz & 31) * 32;
                r.seek(4096L + idx * 4L);
                return r.readInt() & 0xFFFFFFFFL;
            }
        } catch (IOException e) { return -3; }
    }

    static void ensureLoaded(World w, int x0, int x1, int z, Runnable done) {
        int total = x1 - x0 + 1;
        int[] left = {total};
        for (int cx = x0; cx <= x1; cx++) {
            final int fx = cx;
            w.getChunkAtAsync(fx, z).thenAccept(ch -> {
                w.addPluginChunkTicket(fx, z, PocPlugin.get());
                if (--left[0] == 0) done.run();
            });
        }
    }

    static void floor(World w, int cx, int cz) {
        ServerLevel l = Nms.level(w);
        LevelChunk c = Nms.chunkNow(l, cx, cz);
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 12; y++) c.setBlockState(new BlockPos(cx * 16 + x, y, cz * 16 + z), air, 0);
            c.setBlockState(new BlockPos(cx * 16 + x, FLOOR_Y, cz * 16 + z), stone, 0);
        }
    }

    /** 準備 index 0..n-1 對應的 chunk（cx=i%10, cz=1+i/10）：載入、加 ticket、鋪地板、清實體。 */
    static void prepArea(int n, Runnable done) {
        World w = world();
        int[] left = {n};
        for (int i = 0; i < n; i++) {
            final int fx = cxOf(i), fz = czOf(i);
            w.getChunkAtAsync(fx, fz).thenAccept(ch -> {
                w.addPluginChunkTicket(fx, fz, PocPlugin.get());
                Sched.at(w, fx, fz, () -> {
                    floor(w, fx, fz);
                    for (Entity e : w.getChunkAt(fx, fz).getEntities()) if (!(e instanceof Player)) e.remove();
                    boolean last; synchronized (left) { last = --left[0] == 0; }
                    if (last) done.run();
                });
            });
        }
    }

    // ---------- 子指令 ----------
    static void dispatch(CommandSender s, String[] a) {
        if (a.length == 0) return;
        String sub = a[0];
        switch (sub) {
            case "prep" -> prepArea(Integer.parseInt(a[1]), () -> Out.res("prep_done"));
            case "flags" -> suiteFlags(a);
            case "fp" -> suiteFp(Integer.parseInt(a[1]));
            case "autosave" -> suiteAutosave();
            case "load" -> suiteLoad();
            case "light" -> suiteLight();
            case "fp2" -> suiteFp2(Integer.parseInt(a[1]));
            case "t" -> tPrim(a);
            case "watch" -> { watcher().logEach(a[1].equals("on")); if (a.length > 5) watcher().area(Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4]), Integer.parseInt(a[5])); Out.res("watch", "on", a[1]); }
            case "flagdump" -> flagDump(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4]));
            case "sec" -> Section.cmd(a);
            case "io" -> Section.io(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]), a.length > 4 ? a[4] : "glass", a.length > 5 ? a[5] : "full");
            case "we" -> WeTests.cmd(s, a);
            case "protect" -> protect(a);
            case "pktdump" -> pktDump(Long.parseLong(a[1]));
            case "info" -> Out.res("info", "loaded", watcher().scanned, "pktRecs", PocPlugin.get().pkt == null ? -1 : PocPlugin.get().pkt.recs.size(),
                    "pktErr", PocPlugin.get().pkt == null ? -1 : PocPlugin.get().pkt.errors, "pktLastErr", PocPlugin.get().pkt == null ? "" : PocPlugin.get().pkt.lastError,
                    "worldFolder", world().getWorldFolder().getPath());
            case "tsdouble" -> tsDouble();
            case "savetest" -> saveTest();
            case "saveall" -> { saveAll(); Sched.globalDelayed(FOLIA ? 40 : 2, () -> Out.res("saveall")); }
            case "disksig" -> Section.diskSig(Integer.parseInt(a[1]), Integer.parseInt(a[2]));
            case "mark" -> Out.res("mark", "text", String.join(" ", Arrays.copyOfRange(a, 1, a.length)));
            default -> Out.res("error", "unknown", sub);
        }
    }

    static void flagDump(int x0, int x1, int z0, int z1) {
        World w = world(); ServerLevel l = Nms.level(w);
        List<String> unsaved = new ArrayList<>(); int loaded = 0;
        for (int cx = x0; cx <= x1; cx++) for (int cz = z0; cz <= z1; cz++) {
            LevelChunk c = Nms.chunkNow(l, cx, cz);
            if (c != null) { loaded++; if (Nms.rawUnsaved(c)) unsaved.add(cx + "," + cz); }
        }
        Out.res("flagdump", "loaded", loaded, "unsaved", unsaved);
    }

    static void pktDump(long sinceMs) {
        Pkt p = PocPlugin.get().pkt; if (p == null) return;
        Map<String, Integer> byType = new TreeMap<>();
        for (Pkt.Rec r : p.recs) byType.merge(r.type() + "@" + r.player(), 1, Integer::sum);
        Out.res("pktdump", "byTypePlayer", byType);
    }

    /** t begin <name> <cx> <cz> / t end <name> <cx> <cz>：給外部驅動（bot 動作）用的觀察窗。 */
    static final Map<String, long[]> windows = new java.util.concurrent.ConcurrentHashMap<>();
    static void tPrim(String[] a) {
        String mode = a[1], name = a[2]; int cx = Integer.parseInt(a[3]), cz = Integer.parseInt(a[4]);
        World w = world();
        if (mode.equals("begin")) {
            windows.put(name, new long[]{System.nanoTime(), regionTs(w, cx, cz, "region"), regionTs(w, cx, cz, "entities")});
            Out.res("t_begin", "name", name, "cx", cx, "cz", cz, "flag", Nms.unsaved(w, cx, cz));
        } else {
            long[] b = windows.get(name);
            Out.res("t_end", "name", name, "cx", cx, "cz", cz, "flag", Nms.unsaved(w, cx, cz), "others", flaggedSince(b[0], cx, cz),
                    "pkt", pktFor(b[0], cx, cz), "pktAll", pktAll(b[0]), "we", We.class.getName().isEmpty() ? null : weDrain());
        }
    }

    static Object weDrain() { try { return PocPlugin.get().getServer().getPluginManager().getPlugin("WorldEdit") == null
            && PocPlugin.get().getServer().getPluginManager().getPlugin("FastAsyncWorldEdit") == null ? null : We.drain(); } catch (Throwable t) { return t.toString(); } }

    static List<String> flaggedSince(long t0, int cx, int cz) {
        List<String> l = new ArrayList<>();
        for (Watcher.Event e : watcher().since(t0)) if (e.nowUnsaved() && !(e.cx() == cx && e.cz() == cz) && e.cz() >= 0 && e.cz() <= 4 && e.cx() >= -1 && e.cx() <= 10) l.add(e.cx() + "," + e.cz());
        return l;
    }
    static Object pktFor(long t0, int cx, int cz) { Pkt p = PocPlugin.get().pkt; return p == null ? null : p.summarize(t0, cx, cz); }
    static Object pktAll(long t0) {
        Pkt p = PocPlugin.get().pkt; if (p == null) return null;
        Map<String, Integer> m = new TreeMap<>();
        for (Pkt.Rec r : p.recs) if (r.nanos() >= t0 && r.cz() >= 0 && r.cz() <= 4 && r.cx() >= -1 && r.cx() <= 10) m.merge(r.type() + "@" + r.cx() + "," + r.cz(), 1, Integer::sum);
        return m;
    }

    // ---------- 場景：改動來源 ----------
    record Src(String name, int cx, Consumer<Ctx> prep, Consumer<Ctx> act, boolean global, int waitTicks) {}
    static final class Ctx {
        final World w = world(); final ServerLevel l = Nms.level(w); final int cx, cz; final LevelChunk c;
        Ctx(int cx, int cz) { this.cx = cx; this.cz = cz; this.c = Nms.chunkNow(l, cx, cz); }
        BlockPos p(int dx, int dy, int dz) { return new BlockPos(cx * 16 + 4 + dx, Y + dy, cz * 16 + 4 + dz); }
        Location loc(int dx, int dy, int dz) { BlockPos p = p(dx, dy, dz); return new Location(w, p.getX() + .5, p.getY(), p.getZ() + .5); }
        Block block(int dx, int dy, int dz) { BlockPos p = p(dx, dy, dz); return w.getBlockAt(p.getX(), p.getY(), p.getZ()); }
    }

    static int cxOf(int i) { return i % 10; }
    static int czOf(int i) { return 1 + i / 10; }

    static List<Src> sources() {
        List<Src> l = new ArrayList<>();
        int i = 0;
        l.add(new Src("bukkit_setType", i++, null, c -> c.block(0, 0, 0).setType(Material.DIAMOND_BLOCK), false, 60));
        l.add(new Src("nms_level_setBlock", i++, null, c -> c.l.setBlock(c.p(0, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState(), 3), false, 60));
        l.add(new Src("nms_chunk_setBlockState", i++, null, c -> c.c.setBlockState(c.p(0, 0, 0), Blocks.IRON_BLOCK.defaultBlockState(), 3), false, 60));
        l.add(new Src("nms_section_raw", i++, null, c -> {
            BlockPos p = c.p(0, 0, 0);
            c.c.getSection(c.c.getSectionIndex(p.getY())).setBlockState(p.getX() & 15, p.getY() & 15, p.getZ() & 15, Blocks.EMERALD_BLOCK.defaultBlockState(), false);
        }, false, 60));
        l.add(new Src("nms_section_raw+markUnsaved", i++, null, c -> {
            BlockPos p = c.p(0, 0, 0);
            c.c.getSection(c.c.getSectionIndex(p.getY())).setBlockState(p.getX() & 15, p.getY() & 15, p.getZ() & 15, Blocks.EMERALD_BLOCK.defaultBlockState(), false);
            c.c.markUnsaved();
        }, false, 60));
        l.add(new Src("console_setblock", i++, null, c -> { BlockPos p = c.p(0, 0, 0); dispatch("setblock " + p.getX() + " " + p.getY() + " " + p.getZ() + " minecraft:lapis_block"); }, true, 60));
        l.add(new Src("console_fill", i++, null, c -> { BlockPos a = c.p(0, 0, 0), b = c.p(3, 3, 3); dispatch("fill " + a.getX() + " " + a.getY() + " " + a.getZ() + " " + b.getX() + " " + b.getY() + " " + b.getZ() + " minecraft:copper_block"); }, true, 60));
        l.add(new Src("piston", i++, c -> {
            Block p = c.block(2, 0, 0);
            p.setType(Material.PISTON, false);
            Piston d = (Piston) p.getBlockData(); d.setFacing(BlockFace.EAST); p.setBlockData(d, false);
            c.block(3, 0, 0).setType(Material.STONE, false);
        }, c -> { c.l.setBlock(c.p(2, 1, 0), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3); c.c.tryMarkSaved(); }, false, 60));
        l.add(new Src("water_flow", i++, null, c -> { c.l.setBlock(c.p(0, 0, 0), Blocks.WATER.defaultBlockState(), 3); c.c.tryMarkSaved(); }, false, 80));
        l.add(new Src("explosion", i++, null, c -> c.w.createExplosion(c.loc(0, 0, 0), 3f, false, true), false, 60));
        l.add(new Src("chest_setItem", i++, c -> c.block(0, 0, 0).setType(Material.CHEST),
                c -> ((Chest) c.block(0, 0, 0).getState()).getBlockInventory().setItem(0, new ItemStack(Material.DIAMOND)), false, 60));
        l.add(new Src("sign_text", i++, c -> c.block(0, 0, 0).setType(Material.OAK_SIGN), c -> {
            Sign sg = (Sign) c.block(0, 0, 0).getState(); sg.getSide(org.bukkit.block.sign.Side.FRONT).line(0, net.kyori.adventure.text.Component.text("hi")); sg.update();
        }, false, 60));
        l.add(new Src("bukkit_setBiome", i++, null, c -> c.w.setBiome(c.p(0, 0, 0).getX(), c.p(0, 0, 0).getY(), c.p(0, 0, 0).getZ(), org.bukkit.block.Biome.DESERT), false, 60));
        l.add(new Src("chunk_pdc", i++, null, c -> c.w.getChunkAt(c.cx, c.cz).getPersistentDataContainer().set(new NamespacedKey("wgpoc", "x"), org.bukkit.persistence.PersistentDataType.INTEGER, 1), false, 60));
        // 實體
        l.add(new Src("entity_spawn(armorstand)", i++, null, c -> { ArmorStand as = c.w.spawn(c.loc(0, 0, 0), ArmorStand.class); as.setGravity(false); }, false, 60));
        l.add(new Src("entity_move(teleport)", i++, c -> { ArmorStand as = c.w.spawn(c.loc(0, 0, 0), ArmorStand.class); as.setGravity(false); as.addScoreboardTag("mv"); },
                c -> { for (Entity e : c.w.getChunkAt(c.cx, c.cz).getEntities()) if (e.getScoreboardTags().contains("mv")) e.teleportAsync(e.getLocation().add(3, 0, 0)); }, false, 60));
        l.add(new Src("entity_data_change(customName)", i++, c -> { ArmorStand as = c.w.spawn(c.loc(0, 0, 0), ArmorStand.class); as.setGravity(false); as.addScoreboardTag("mv"); },
                c -> { for (Entity e : c.w.getChunkAt(c.cx, c.cz).getEntities()) if (e.getScoreboardTags().contains("mv")) e.customName(net.kyori.adventure.text.Component.text("renamed")); }, false, 60));
        l.add(new Src("entity_kill", i++, c -> { ArmorStand as = c.w.spawn(c.loc(0, 0, 0), ArmorStand.class); as.setGravity(false); as.addScoreboardTag("mv"); },
                c -> { for (Entity e : c.w.getChunkAt(c.cx, c.cz).getEntities()) if (e.getScoreboardTags().contains("mv")) e.remove(); }, false, 60));
        l.add(new Src("furnace_smelting(BE tick)", i++, c -> {
            c.block(0, 0, 0).setType(Material.FURNACE);
            Furnace f = (Furnace) c.block(0, 0, 0).getState();
            f.getInventory().setSmelting(new ItemStack(Material.RAW_IRON, 8)); f.getInventory().setFuel(new ItemStack(Material.COAL, 8)); f.update();
        }, c -> {}, false, 100));
        l.add(new Src("entity_present_unchanged", i++, c -> { ArmorStand as = c.w.spawn(c.loc(0, 0, 0), ArmorStand.class); as.setGravity(false); }, c -> {}, false, 100));
        l.add(new Src("nothing", i++, null, c -> {}, false, 100));
        l.add(new Src("nothing#2", i++, null, c -> {}, false, 100));
        return l;
    }

    static void suiteFlags(String[] a) {
        List<Src> srcs = sources();
        int n = srcs.size();
        World w = world();
        prepArea(n, () -> {
            Seq seq = new Seq();
            seq.add(100, () -> saveAll()); // 讓地板、光照先穩定
            seq.add(100, () -> saveAll());
            for (Src s : srcs) {
                final int cx = cxOf(s.cx), cz = czOf(s.cx);
                Ctx[] ctx = new Ctx[1];
                long[] t0 = new long[4];
                boolean[] pre = new boolean[2];
                seq.add(20, () -> { ctx[0] = new Ctx(cx, cz); if (s.prep != null) Sched.at(w, cx, cz, () -> s.prep.accept(ctx[0])); });
                seq.add(20, () -> saveAll());
                seq.add(70, () -> { // 與上一次寫入拉開 >3 秒，region 時間戳（秒）才分得出來
                    pre[1] = Nms.unsaved(w, cx, cz);            // save 後 70 tick，旗標是否又被設回（雜訊）
                    Sched.at(w, cx, cz, () -> Nms.chunkNow(ctx[0].l, cx, cz).tryMarkSaved()); // 清掉雜訊，確保觀察窗乾淨
                });
                seq.add(2, () -> {
                    t0[0] = System.nanoTime(); t0[1] = regionTs(w, cx, cz, "region"); t0[2] = regionTs(w, cx, cz, "entities");
                    pre[0] = Nms.unsaved(w, cx, cz);
                    Runnable act = () -> s.act.accept(ctx[0]);
                    if (s.global) Sched.global(act); else Sched.at(w, cx, cz, act);
                });
                Object[] hold = new Object[6];
                seq.add(s.waitTicks, () -> {
                    hold[0] = Nms.unsaved(w, cx, cz);
                    hold[5] = Nms.effectiveUnsaved(w, cx, cz);
                    hold[1] = flaggedSince(t0[0], cx, cz);
                    hold[2] = pktFor(t0[0], cx, cz);
                    hold[3] = pktAll(t0[0]);
                    hold[4] = weDrain();
                    saveAll();
                });
                seq.add(10, () -> {
                    long ts1 = regionTs(w, cx, cz, "region"), es1 = regionTs(w, cx, cz, "entities");
                    Out.res("src", "name", s.name, "cx", cx, "cz", cz, "flagNoiseBeforeClear", pre[1], "flagPre", pre[0], "flagPost", hold[0], "otherChunksFlagged", hold[1],
                            "flagEffectivePost(isUnsaved incl. tick dirty)", hold[5], "flagEffectiveAfterSaveAll", Nms.effectiveUnsaved(w, cx, cz), "flagAfterSaveAll", Nms.unsaved(w, cx, cz), "regionTsBefore", t0[1], "regionTsAfter", ts1, "entTsBefore", t0[2], "entTsAfter", es1,
                            "pkt", hold[2], "pktAllChunks", hold[3], "we", hold[4]);
                });
            }
            seq.add(5, () -> Out.res("suite_done", "suite", "flags"));
            seq.go();
        });
    }

    // ---------- 誤報觀察 ----------
    static void suiteFp(int seconds) {
        World w = world();
        int[] cxs = {0, 1, 2, 3, 4, 5, 6, 7};
        String[] names = {"empty", "cow_ai", "item_entity", "furnace_burning", "hopper_chest", "lava_static", "sapling+bonemeal-none", "sheep_ai_far"};
        prepArea(8, () -> {
            Seq seq = new Seq();
            seq.add(10, () -> {
                Sched.at(w, 1, CZ, () -> { for (int i = 0; i < 3; i++) { Cow cow = (Cow) w.spawnEntity(new Location(w, 16 + 4 + i * 2, 190, 16 + 8), EntityType.COW); } });
                Sched.at(w, 2, CZ, () -> w.dropItem(new Location(w, 32 + 8, 190, 16 + 8), new ItemStack(Material.STONE, 5)));
                Sched.at(w, 3, CZ, () -> {
                    Block b = w.getBlockAt(48 + 4, 190, 16 + 4); b.setType(Material.FURNACE);
                    Furnace f = (Furnace) b.getState(); f.getInventory().setSmelting(new ItemStack(Material.RAW_IRON, 16)); f.getInventory().setFuel(new ItemStack(Material.COAL, 16)); f.update();
                });
                Sched.at(w, 4, CZ, () -> {
                    Block h = w.getBlockAt(64 + 4, 190, 16 + 4); h.setType(Material.CHEST);
                    ((Chest) h.getState()).getBlockInventory().addItem(new ItemStack(Material.STONE, 64));
                    Block hp = w.getBlockAt(64 + 4, 191, 16 + 4); hp.setType(Material.HOPPER);
                    Block c2 = w.getBlockAt(64 + 4, 192, 16 + 4); c2.setType(Material.CHEST);
                    ((Chest) c2.getState()).getBlockInventory().addItem(new ItemStack(Material.STONE, 64));
                });
                Sched.at(w, 5, CZ, () -> w.getBlockAt(80 + 4, 190, 16 + 4).setType(Material.LAVA));
                Sched.at(w, 7, CZ, () -> { Sheep sh = (Sheep) w.spawnEntity(new Location(w, 112 + 4, 190, 16 + 8), EntityType.SHEEP); });
            });
            seq.add(20, () -> saveAll());
            seq.add(100, () -> saveAll());   // 第二次 flush：光照等非同步工作大多已完成
            long[] t0 = new long[1];
            List<Boolean> preNoise = new ArrayList<>();
            seq.add(100, () -> {
                for (int cx : cxs) { preNoise.add(Nms.unsaved(w, cx, CZ)); final int fx = cx; Sched.at(w, fx, CZ, () -> Nms.chunkNow(Nms.level(w), fx, CZ).tryMarkSaved()); }
            });
            seq.add(3, () -> {
                t0[0] = System.nanoTime();
                List<Boolean> pre = new ArrayList<>(); for (int cx : cxs) pre.add(Nms.unsaved(w, cx, CZ));
                Out.res("fp_begin", "flagsSetBeforeClear(noise after 2 flushes)", preNoise, "flagsPre", pre, "seconds", seconds);
            });
            seq.add(seconds * 20L, () -> {
                Map<String, Object> res = new LinkedHashMap<>();
                for (int k = 0; k < cxs.length; k++) {
                    int cx = cxs[k]; int sets = 0; long firstMs = -1;
                    for (Watcher.Event e : watcher().since(t0[0])) if (e.cx() == cx && e.cz() == CZ && e.nowUnsaved()) { sets++; if (firstMs < 0) firstMs = (e.nanos() - t0[0]) / 1_000_000; }
                    res.put(cx + ":" + names[k], Out.m("flagSetTransitions", sets, "firstSetMs", firstMs, "flagNow", Nms.unsaved(w, cx, CZ)));
                }
                Out.res("fp_result", "seconds", seconds, "chunks", res, "others", flaggedSince(t0[0], -99, -99));
            });
            seq.go();
        });
    }

    // ---------- 誤報觀察 2：重複樣本（空 chunk / 有 AI 的生物 / 靜止實體 / 掉落物） ----------
    static void suiteFp2(int seconds) {
        World w = world();
        String[] kinds = {"empty", "empty", "empty", "cows_ai", "cows_ai", "cows_ai", "armorstand_static", "armorstand_static", "items_static", "sheep_ai"};
        prepArea(10, () -> {
            Seq seq = new Seq();
            seq.add(10, () -> {
                for (int i = 0; i < 10; i++) {
                    final int cx = i; final String k = kinds[i];
                    Sched.at(w, cx, CZ, () -> {
                        for (int j = 0; j < 3; j++) {
                            Location l = new Location(w, cx * 16 + 4 + j * 3, 190, 16 + 8);
                            switch (k) {
                                case "cows_ai" -> w.spawnEntity(l, EntityType.COW);
                                case "sheep_ai" -> w.spawnEntity(l, EntityType.SHEEP);
                                case "armorstand_static" -> { ArmorStand as = w.spawn(l, ArmorStand.class); as.setGravity(false); }
                                case "items_static" -> w.dropItem(l, new ItemStack(Material.STONE, 3));
                                default -> {}
                            }
                        }
                    });
                }
            });
            seq.add(20, () -> saveAll());
            seq.add(100, () -> saveAll());
            seq.add(100, () -> saveAll());
            seq.add(100, () -> { for (int cx = 0; cx < 10; cx++) { final int fx = cx; Sched.at(w, fx, CZ, () -> Nms.chunkNow(Nms.level(w), fx, CZ).tryMarkSaved()); } });
            long[] t0 = new long[1];
            seq.add(3, () -> { t0[0] = System.nanoTime(); Out.res("fp2_begin", "seconds", seconds); });
            seq.add(seconds * 20L, () -> {
                Map<String, Object> res = new LinkedHashMap<>();
                for (int cx = 0; cx < 10; cx++) {
                    int sets = 0; long first = -1;
                    for (Watcher.Event e : watcher().since(t0[0])) if (e.cx() == cx && e.cz() == CZ && e.nowUnsaved()) { sets++; if (first < 0) first = (e.nanos() - t0[0]) / 1_000_000; }
                    res.put(cx + ":" + kinds[cx], Out.m("setTransitions", sets, "firstSetMs", first, "flagNow", Nms.unsaved(w, cx, CZ)));
                }
                Out.res("fp2_result", "seconds", seconds, "chunks", res, "otherChunksFlaggedAnyRow", flaggedSince(t0[0], -99, -99));
            });
            seq.go();
        });
    }

    // ---------- 光照是否讓「沒改方塊」的鄰近 chunk 也被設旗標 ----------
    static void suiteLight() {
        World w = world();
        // case: name, chunk (cx,cz=1), local x/z, y, material
        Object[][] cases = {
            {"glowstone_center_of_chunk(5,1)", 5, 8, 8, 195, Material.GLOWSTONE},
            {"glowstone_at_chunk_edge_x=15(7,1)", 7, 15, 8, 195, Material.GLOWSTONE},
            {"opaque_stone_high_above(9,1)_skylight_shadow", 9, 8, 8, 200, Material.STONE},
        };
        prepArea(10, () -> {
            Seq seq = new Seq();
            seq.add(100, () -> saveAll());
            seq.add(100, () -> saveAll());
            seq.add(100, () -> saveAll());
            for (Object[] c : cases) {
                final String name = (String) c[0]; final int cx = (Integer) c[1], lx = (Integer) c[2], lz = (Integer) c[3], y = (Integer) c[4]; final Material m = (Material) c[5];
                long[] t0 = new long[1];
                seq.add(20, () -> {
                    for (int x = -1; x <= 11; x++) for (int z = 0; z <= 3; z++) { final int fx = x, fz = z; Sched.at(w, fx, fz, () -> { LevelChunk ch = Nms.chunkNow(Nms.level(w), fx, fz); if (ch != null) ch.tryMarkSaved(); }); }
                });
                seq.add(10, () -> { t0[0] = System.nanoTime(); Sched.at(w, cx, 1, () -> Nms.level(w).setBlock(new BlockPos(cx * 16 + lx, y, 16 + lz), Nms.state(m.createBlockData()), 3)); });
                seq.add(100, () -> {
                    List<String> flagged = new ArrayList<>();
                    for (Watcher.Event e : watcher().since(t0[0])) if (e.nowUnsaved()) flagged.add(e.cx() + "," + e.cz() + "@" + (e.nanos() - t0[0]) / 1_000_000 + "ms");
                    Out.res("light_case", "name", name, "changedChunk", cx + ",1", "flaggedChunks", flagged);
                });
            }
            seq.add(5, () -> Out.res("suite_done", "suite", "light"));
            seq.go();
        });
    }

    // ---------- chunk 載入本身會不會設旗標 ----------
    static void suiteLoad() {
        World w = world();
        int[][] cs = {{9, -9}, {25, 25}}; // 第一個：baseline 已生成（full），但目前沒載入；第二個：從未生成
        String[] desc = {"existing_full_chunk_in_baseline", "never_generated_chunk"};
        for (int k = 0; k < cs.length; k++) {
            final int cx = cs[k][0], cz = cs[k][1]; final String d = desc[k];
            Seq seq = new Seq(); List<Object> samples = new ArrayList<>(); long[] t0 = new long[1];
            boolean loadedBefore = w.isChunkLoaded(cx, cz);
            long[] tsb = {regionTs(w, cx, cz, "region")};
            seq.add(1 + k * 300, () -> { t0[0] = System.nanoTime(); w.getChunkAtAsync(cx, cz).thenAccept(ch -> w.addPluginChunkTicket(cx, cz, PocPlugin.get())); });
            for (int i : new int[]{10, 40, 100, 200}) {
                long[] prev = {0};
                seq.add(i, () -> samples.add(Out.m("afterMs", (System.nanoTime() - t0[0]) / 1_000_000, "loaded", Nms.chunkNow(Nms.level(w), cx, cz) != null, "unsaved", Nms.unsaved(w, cx, cz))));
            }
            seq.add(5, () -> { saveAll(); });
            seq.add(10, () -> {
                boolean afterSave = Nms.unsaved(w, cx, cz);
                w.removePluginChunkTicket(cx, cz, PocPlugin.get());
                Out.res("load_test", "desc", d, "chunk", cx + "," + cz, "loadedBefore", loadedBefore, "regionTsBeforeLoad", tsb[0], "samples", samples, "flagAfterSaveAll", afterSave, "regionTsAfterSave", regionTs(w, cx, cz, "region"));
            });
            seq.go();
        }
    }

    // ---------- 自動存檔清旗標 ----------
    static void suiteAutosave() {
        World w = world();
        prepArea(3, () -> {
            Seq seq = new Seq();
            seq.add(10, () -> saveAll());
            long[] t0 = new long[1];
            seq.add(60, () -> {
                t0[0] = System.nanoTime();
                for (int cx = 0; cx <= 2; cx++) { final int fx = cx; Sched.at(w, fx, CZ, () -> {
                    Nms.level(w).setBlock(new BlockPos(fx * 16 + 4, 190, 16 + 4), Blocks.GOLD_BLOCK.defaultBlockState(), 3); }); }
            });
            seq.add(10, () -> Out.res("autosave_begin", "flags", List.of(Nms.unsaved(w, 0, CZ), Nms.unsaved(w, 1, CZ), Nms.unsaved(w, 2, CZ))));
            // 輪詢直到三個 chunk 都被清（最長 120 秒）
            Runnable[] poll = new Runnable[1];
            int[] tries = {0};
            poll[0] = () -> {
                boolean all = true;
                for (int cx = 0; cx <= 2; cx++) if (Nms.unsaved(w, cx, CZ)) all = false;
                if (all || ++tries[0] > 120) {
                    List<Object> clears = new ArrayList<>();
                    for (Watcher.Event e : watcher().since(t0[0])) if (!e.nowUnsaved() && e.cz() == CZ && e.cx() <= 2 && e.cx() >= 0) clears.add(Out.m("cx", e.cx(), "afterMs", (e.nanos() - t0[0]) / 1_000_000));
                    Out.res("autosave_result", "allCleared", all, "clears", clears,
                            "regionTs", List.of(regionTs(w, 0, CZ, "region"), regionTs(w, 1, CZ, "region"), regionTs(w, 2, CZ, "region")), "nowSec", System.currentTimeMillis() / 1000);
                    Out.res("suite_done", "suite", "autosave");
                } else Sched.globalDelayed(20, poll[0]);
            };
            seq.add(20, () -> poll[0].run());
            seq.go();
        });
    }

    // ---------- 存檔方法探測（Folia 沒有 /save-all） ----------
    static void saveTest() {
        World w = world();
        String[] modes = {"api-global", "api-async", "nms-async", "nms-global", "nms-region"};
        Seq seq = new Seq();
        for (int i = 0; i < modes.length; i++) {
            final String m = modes[i]; final int cx = i;
            long[] ts = new long[1];
            seq.add(i == 0 ? 20 : 60, () -> Sched.at(w, cx, CZ, () -> { floorIfNeeded(w, cx); Nms.level(w).setBlock(new BlockPos(cx * 16 + 4, 190, 16 + 4), Blocks.GOLD_BLOCK.defaultBlockState(), 3); }));
            seq.add(10, () -> { ts[0] = regionTs(w, cx, CZ, "region");
                Runnable r = () -> { try {
                    switch (m) {
                        case "api-global", "api-async" -> w.save();
                        default -> Nms.saveAllChunksNms(Nms.level(w), true);
                    } Out.res("savetest_call", "mode", m, "thread", Thread.currentThread().getName(), "ok", true); }
                    catch (Throwable t) { Out.res("savetest_call", "mode", m, "thread", Thread.currentThread().getName(), "ok", false, "err", trace(t)); } };
                switch (m) { case "api-global", "nms-global" -> Sched.global(r); case "nms-region" -> Sched.at(w, cx, CZ, r); default -> Sched.async(r); } });
            seq.add(60, () -> Out.res("savetest", "mode", m, "rawFlagAfter", Nms.unsaved(w, cx, CZ), "tsBefore", ts[0], "tsAfter", regionTs(w, cx, CZ, "region")));
        }
        seq.add(5, () -> Out.res("suite_done", "suite", "savetest"));
        seq.go();
    }
    static void floorIfNeeded(World w, int cx) { floor(w, cx, CZ); }

    // ---------- 時間戳同秒碰撞 ----------
    static void tsDouble() {
        World w = world();
        Seq seq = new Seq();
        seq.add(1, () -> Sched.at(w, 0, CZ, () -> Nms.level(w).setBlock(new BlockPos(5, 190, 20), Blocks.GOLD_BLOCK.defaultBlockState(), 3)));
        seq.add(5, () -> saveAll());
        long[] ts = new long[3];
        seq.add(2, () -> { ts[0] = regionTs(w, 0, CZ, "region"); ts[2] = System.currentTimeMillis();
            Sched.at(w, 0, CZ, () -> Nms.level(w).setBlock(new BlockPos(5, 190, 20), Blocks.IRON_BLOCK.defaultBlockState(), 3)); });
        seq.add(2, () -> saveAll());
        seq.add(2, () -> { ts[1] = regionTs(w, 0, CZ, "region");
            Out.res("ts_double", "ts1", ts[0], "ts2", ts[1], "sameSecond", ts[0] == ts[1], "gapMs", System.currentTimeMillis() - ts[2]); });
        seq.go();
    }

    // ---------- 玩家保護 ----------
    /** protect <none|event|potion> <player>：摔落（從 y=235 落到 y=190 地板，約 45 格）→ 落地 4 秒 → 頭上放石頭（窒息）→ 4 秒。 */
    static void protect(String[] a) {
        String mode = a[1]; Player p = Bukkit.getPlayerExact(a[2]);
        if (p == null) { Out.res("error", "protect", "no player"); return; }
        World w = world();
        Sched.entity(p, () -> {
            p.setGameMode(GameMode.SURVIVAL); p.setAllowFlight(false); p.setFlying(false); p.setHealth(20); p.setFoodLevel(20); p.setFallDistance(0); p.setNoDamageTicks(0);
            for (PotionEffect pe : p.getActivePotionEffects()) p.removePotionEffect(pe.getType());
            Protect.until.remove(p.getUniqueId()); Protect.cancelled.clear(); Protect.seen.clear();
            if (mode.equals("event")) Protect.until.put(p.getUniqueId(), System.currentTimeMillis() + 15_000);
            if (mode.equals("potion")) p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 300, 4, false, false, false));
            p.teleportAsync(new Location(w, 8.5, 235, 24.5)).thenRun(() -> Sched.entity(p, () -> Out.res("protect_start", "mode", mode, "y", p.getLocation().getY(), "health", p.getHealth(), "gm", p.getGameMode().name())));
        });
        Sched.globalDelayed(200, () -> Sched.entity(p, () -> {
            Out.res("protect_fall", "mode", mode, "health", p.getHealth(), "y", p.getLocation().getY(), "onGround", p.isOnGround(), "cancelled", new TreeMap<>(Protect.cancelled), "damaged", Protect.seen.toString());
            Location head = p.getEyeLocation();
            Sched.at(w, head.getBlockX() >> 4, head.getBlockZ() >> 4, () -> head.getBlock().setType(Material.STONE));
        }));
        Sched.globalDelayed(200 + 100, () -> Sched.entity(p, () -> {
            Out.res("protect_suffocate", "mode", mode, "health", p.getHealth(), "cancelled", new TreeMap<>(Protect.cancelled), "damaged", Protect.seen.toString(), "headBlock", p.getEyeLocation().getBlock().getType().name());
            Location head = p.getEyeLocation(); Sched.at(w, head.getBlockX() >> 4, head.getBlockZ() >> 4, () -> head.getBlock().setType(Material.AIR));
            p.setGameMode(GameMode.CREATIVE);
        }));
    }
}
