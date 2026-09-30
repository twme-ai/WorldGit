package wg.poc;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;

/** 只用 Paper/Folia 的 Region/Global/Entity/Async scheduler（BukkitScheduler 在 Folia 上不能用）。 */
public final class Sched {
    private Sched() {}
    /** 在擁有該 chunk 的執行緒上執行（Paper = 主執行緒，Folia = region 執行緒）。 */
    public static void at(World w, int cx, int cz, Runnable r) {
        Bukkit.getRegionScheduler().execute(PocPlugin.get(), w, cx, cz, r);
    }
    public static void atDelayed(World w, int cx, int cz, long ticks, Runnable r) {
        Bukkit.getRegionScheduler().runDelayed(PocPlugin.get(), w, cx, cz, t -> r.run(), Math.max(1, ticks));
    }
    public static void global(Runnable r) { Bukkit.getGlobalRegionScheduler().execute(PocPlugin.get(), r); }
    public static void globalDelayed(long ticks, Runnable r) {
        Bukkit.getGlobalRegionScheduler().runDelayed(PocPlugin.get(), t -> r.run(), Math.max(1, ticks));
    }
    public static void entity(Entity e, Runnable r) { e.getScheduler().run(PocPlugin.get(), t -> r.run(), null); }
    public static void async(Runnable r) { Bukkit.getAsyncScheduler().runNow(PocPlugin.get(), t -> r.run()); }
}
