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

    @Override public void onEnable() {
        inst = this;
        Out.init(getDataFolder().toPath());
        boolean folia = false;
        try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); folia = true; } catch (Throwable ignored) {}
        Out.res("boot", "server", Bukkit.getName() + " " + Bukkit.getMinecraftVersion(), "folia", folia,
                "java", System.getProperty("java.version"), "bukkitVersion", Bukkit.getBukkitVersion());
        if (Bukkit.getPluginManager().getPlugin("packetevents") != null) {
            try { pkt = new Pkt(); Pkt.instance = pkt;
                com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager().registerListener(pkt);
                Out.res("hook", "packetevents", "ok");
            } catch (Throwable t) { Out.res("hook", "packetevents", "fail: " + t); }
        }
        boolean fawe = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null;
        boolean we = Bukkit.getPluginManager().getPlugin("WorldEdit") != null;
        if (fawe || we) {
            try { We.install(); Out.res("hook", fawe ? "fawe" : "worldedit", "ok"); }
            catch (Throwable t) { Out.res("hook", "we", "fail: " + t); }
        }
        watcher.start();
        Bukkit.getPluginManager().registerEvents(new Protect(), this);
    }

    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        try { Tests.dispatch(s, a); }
        catch (Throwable t) { Out.res("error", "cmd", String.join(" ", a), "err", t.toString(), "trace", Tests.trace(t)); }
        return true;
    }
}
