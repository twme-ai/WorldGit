package wg.poc;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 每 tick 在 global scheduler 上掃描一個矩形範圍內已載入 chunk 的 unsaved 旗標，記錄 false→true / true→false 的轉換。
 * unsaved 是 volatile boolean，所以可以跨執行緒讀取（Folia 上不需要進 region 執行緒）。
 */
public final class Watcher {
    public record Event(long nanos, String world, int cx, int cz, boolean nowUnsaved) {}
    private final Map<String, Boolean> last = new ConcurrentHashMap<>();
    public final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();
    private volatile int x0 = -3, x1 = 14, z0 = -3, z1 = 5;
    private volatile boolean logEach = false;
    public int scanned;

    public void area(int x0, int x1, int z0, int z1) { this.x0 = x0; this.x1 = x1; this.z0 = z0; this.z1 = z1; }
    public void logEach(boolean b) { logEach = b; }

    public void start() {
        org.bukkit.Bukkit.getGlobalRegionScheduler().runAtFixedRate(PocPlugin.get(), t -> scan(), 1, 1);
    }

    private void scan() {
        int n = 0;
        for (World w : org.bukkit.Bukkit.getWorlds()) {
            ServerLevel lvl = Nms.level(w);
            for (int cx = x0; cx <= x1; cx++) for (int cz = z0; cz <= z1; cz++) {
                LevelChunk c = Nms.chunkNow(lvl, cx, cz);
                String k = w.getName() + ":" + cx + ":" + cz;
                boolean now = c != null && Nms.rawUnsaved(c);
                if (c != null) n++;
                Boolean prev = last.put(k, now);
                boolean p = prev != null && prev;
                if (prev != null && p != now) {
                    events.add(new Event(System.nanoTime(), w.getName(), cx, cz, now));
                    if (logEach) Out.res("flag_transition", "world", w.getName(), "cx", cx, "cz", cz, "unsaved", now);
                }
            }
        }
        scanned = n;
    }

    /** 自 sinceNanos 之後的轉換。 */
    public List<Event> since(long sinceNanos) {
        List<Event> l = new ArrayList<>();
        for (Event e : events) if (e.nanos >= sinceNanos) l.add(e);
        return l;
    }
}
