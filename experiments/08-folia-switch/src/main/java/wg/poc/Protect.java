package wg.poc;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 玩家保護（事件取消版）：切換後 N 毫秒內取消窒息/摔落/溺水傷害。 */
public final class Protect implements Listener {
    public static final Map<UUID, Long> until = new ConcurrentHashMap<>();
    public static final Map<String, Integer> cancelled = new ConcurrentHashMap<>();
    /** 所有看到的傷害事件（cause -> 次數 / 累計最終傷害），用來確認「沒保護時」真的有傷害事件。 */
    public static final Map<String, String> seen = new ConcurrentHashMap<>();

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        seen.merge(e.getCause().name(), "1/" + e.getFinalDamage(), (o, n) -> { String[] x = o.split("/"); return (Integer.parseInt(x[0]) + 1) + "/" + (Double.parseDouble(x[1]) + e.getFinalDamage()); });
        Long u = until.get(p.getUniqueId());
        if (u == null || System.currentTimeMillis() > u) return;
        switch (e.getCause()) {
            case FALL, SUFFOCATION, DROWNING -> { e.setCancelled(true); cancelled.merge(e.getCause().name(), 1, Integer::sum); }
            default -> {}
        }
    }
}
