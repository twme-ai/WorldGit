package wg.poc;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;

public final class PocPlugin extends JavaPlugin {
    private static PocPlugin inst;
    public static PocPlugin get() { return inst; }
    public final Watcher watcher = new Watcher();
    public Pkt pkt;
    public Bench bench;

    @Override public void onEnable() {
        inst = this;
        Out.init(getDataFolder().toPath());
        boolean folia = false;
        try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); folia = true; } catch (Throwable ignored) {}
        Out.res("boot", "server", Bukkit.getName() + " " + Bukkit.getMinecraftVersion(), "folia", folia,
                "java", System.getProperty("java.version"), "bukkitVersion", Bukkit.getBukkitVersion());
        bench = new Bench();
        Bukkit.getPluginManager().registerEvents(bench, this);
    }

    @Override public void onDisable() { if (bench != null) bench.shutdown(); }

    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        try { bench.command(s, a); }
        catch (Throwable t) { Out.res("error", "cmd", String.join(" ", a), "err", t.toString(), "trace", Tests.trace(t)); }
        return true;
    }
}
