import org.bukkit.plugin.java.JavaPlugin;

/** 僅供驗收：世界載入後、第一個遊戲 tick 前凍結，避免自然流體改變驗證內容。 */
public final class Phase5Freeze extends JavaPlugin {
  @Override public void onEnable() {
    getServer().getServerTickManager().setFrozen(true);
    getLogger().info("PHASE5 frozen before first tick");
  }
}
