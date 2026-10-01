package org.worldgit.paper;

import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitRunnable;
import org.worldgit.protocol.*;
import java.util.*;

/** 僅 Paper 的展示 fixture；正式唯讀預覽只傳 diff，不能呼叫這裡的方塊修改。 */
public final class PocPlugin extends JavaPlugin implements Listener, PluginMessageListener {
    private record Session(long nonce, boolean ready) {}
    private record Saved(World world, int x, int y, int z, BlockData state) {}
    private final Map<UUID,Session> sessions = new HashMap<>();
    private final Map<UUID,List<Saved>> fixtures = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();
    private long preview = System.currentTimeMillis();
    private static final String[] OLD = {
        "minecraft:stone_bricks",
        "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",
        "minecraft:stone_slab[type=bottom,waterlogged=false]",
        "minecraft:oak_fence[east=true,north=false,south=false,west=true,waterlogged=false]",
        "minecraft:glass_pane[east=true,north=false,south=false,west=true,waterlogged=false]"
    };
    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        var messenger = getServer().getMessenger();
        for (String channel : List.of(Protocol.HELLO, Protocol.DIFF, Protocol.CLEAR)) messenger.registerOutgoingPluginChannel(this, channel);
        messenger.registerIncomingPluginChannel(this, Protocol.HELLO, this);
        getLogger().info("WGPOC enabled protocol=" + Protocol.VERSION + " Java21 same-jar");
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        Player p = event.getPlayer(); long nonce = ++preview;
        sessions.put(p.getUniqueId(), new Session(nonce, false));
        for (long delay : new long[]{20,60}) getServer().getScheduler().runTaskLater(this, () -> {
            var s = sessions.get(p.getUniqueId());
            if (p.isOnline() && s != null && !s.ready) {
                p.sendPluginMessage(this, Protocol.HELLO, Protocol.hello(new Protocol.Hello(Protocol.VERSION, nonce, List.of())));
                getLogger().info("WGPOC hello player=" + p.getName() + " nonce=" + nonce + " channels=" + p.getListeningPluginChannels());
            }
        }, delay);
        getServer().getScheduler().runTaskLater(this, () -> {
            var s = sessions.get(p.getUniqueId());
            if (s != null && s.nonce == nonce && !s.ready) getLogger().info("WGPOC NO_MOD player=" + p.getName() + " timeoutTicks=120 fallback=display-entity-outline (decision only)");
        },120);
    }
    @EventHandler public void quit(PlayerQuitEvent event) { sessions.remove(event.getPlayer().getUniqueId()); }
    @Override public void onPluginMessageReceived(String channel, Player p, byte[] bytes) {
        try {
            var response = Protocol.hello(bytes); var session = sessions.get(p.getUniqueId());
            if (session == null || session.nonce != response.nonce() || response.version() != Protocol.VERSION || !response.capabilities().containsAll(Protocol.CAPABILITIES)) {
                getLogger().warning("WGPOC handshake rejected player=" + p.getName()); return;
            }
            sessions.put(p.getUniqueId(), new Session(session.nonce, true));
            getLogger().info("WGPOC HANDSHAKE_OK player=" + p.getName() + " protocol=" + response.version() + " capabilities=" + response.capabilities());
        } catch (Exception e) { getLogger().warning("WGPOC invalid hello " + e); }
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("玩家執行 /wgpoc diffdemo [1..100000] 或 clear"); return true; }
        if (args.length == 0) { p.sendMessage("/wgpoc diffdemo [n] | /wgpoc clear"); return true; }
        if (busy.contains(p.getUniqueId())) { p.sendMessage("示範建置／還原中，稍後再試。"); return true; }
        if (args[0].equals("clear")) { clear(p); return true; }
        if (!args[0].equals("diffdemo")) return false;
        var s = sessions.get(p.getUniqueId());
        if (s == null || !s.ready) { p.sendMessage("未握手：需要 Fabric WorldGit 模組；fallback 僅記錄判斷。"); return true; }
        int n;
        try { n = args.length > 1 ? Integer.parseInt(args[1]) : 64; if (n < 4 || n > Protocol.MAX_ENTRIES) throw new NumberFormatException(); }
        catch (NumberFormatException e) { p.sendMessage("n 必須為 4..100000（四種各至少一格）"); return true; }
        if (fixtures.containsKey(p.getUniqueId())) { p.sendMessage("請先 /wgpoc clear 還原上一組示範。"); return true; }
        demo(p, n); return true;
    }
    private void demo(Player p, int n) {
        long id = ++preview; var loc = p.getLocation(); World w = p.getWorld();
        int bx = ((loc.getBlockX() >> 4) << 4) + 14, bz = ((loc.getBlockZ() >> 4) << 4) + 14;
        int by = Math.min(w.getMaxHeight()-32, Math.max(w.getMinHeight()+1, loc.getBlockY()+2));
        var entries = new ArrayList<Protocol.Entry>(n); var saved = new ArrayList<Saved>(n);
        fixtures.put(p.getUniqueId(), saved); busy.add(p.getUniqueId()); long started = System.nanoTime();
        new BukkitRunnable() {
            int i;
            @Override public void run() {
                if (!p.isOnline()) { busy.remove(p.getUniqueId()); cancel(); return; }
                for (int limit = Math.min(i+1000, n); i < limit; i++) {
                    var type = DiffType.values()[i % 4];
                    int x = bx + (n <= 64 ? (i / 4)*2 : i % 64);
                    int z = bz + (n <= 64 ? type.ordinal()*3 : (i/64) % 64);
                    int y = by + (n <= 64 ? 0 : i / 4096);
                    var block = w.getBlockAt(x,y,z); saved.add(new Saved(w,x,y,z,block.getBlockData().clone()));
                    String old = OLD[(i / 4) % OLD.length];
                    // REMOVED 先實際放入舊 state，再設為 air；diff 本身仍只供客戶端讀取。
                    block.setBlockData(Bukkit.createBlockData(old), false);
                    if (type == DiffType.REMOVED) block.setType(Material.AIR, false);
                    else if (type == DiffType.MODIFIED) block.setType(Material.MOSSY_STONE_BRICKS, false);
                    else if (type == DiffType.CONFLICT) block.setType(Material.SMOOTH_QUARTZ, false);
                    entries.add(new Protocol.Entry(x,y,z,type, type == DiffType.ADDED ? "" : old));
                }
                if (i < n) return;
                cancel();
                long buildNs = System.nanoTime()-started;
                long air = entries.stream().filter(e -> e.type()==DiffType.REMOVED && w.getBlockAt(e.x(),e.y(),e.z()).getType()==Material.AIR).count();
                // 編碼不讀世界，可移到背景執行緒；送封包回主執行緒。
                getServer().getScheduler().runTaskAsynchronously(PocPlugin.this, () -> {
                    long start = System.nanoTime(); var packets = Protocol.split(id, w.getKey().toString(), entries); long encodeNs = System.nanoTime()-start;
                    getServer().getScheduler().runTask(PocPlugin.this, () -> new BukkitRunnable() {
                        int next;
                        @Override public void run() {
                            if (!p.isOnline()) { busy.remove(p.getUniqueId()); cancel(); return; }
                            for (int limit=Math.min(next+2,packets.size()); next<limit; next++) p.sendPluginMessage(PocPlugin.this,Protocol.DIFF,packets.get(next));
                            if (next == packets.size()) {
                                cancel(); busy.remove(p.getUniqueId());
                                getLogger().info("WGPOC DIFF_SENT player="+p.getName()+" n="+n+" id="+id+" parts="+packets.size()+" bytes="+packets.stream().mapToInt(b->b.length).sum()+" maxPacket="+packets.stream().mapToInt(b->b.length).max().orElse(0)+" removedAir="+air+" fixtureMs="+buildNs/1e6+" encodeMs="+encodeNs/1e6+" origin="+bx+","+by+","+bz);
                            }
                        }
                    }.runTaskTimer(PocPlugin.this,1,1));
                });
            }
        }.runTaskTimer(this,1,1);
    }
    private void clear(Player p) {
        long id = ++preview; p.sendPluginMessage(this,Protocol.CLEAR,Protocol.clear(id));
        var saved = fixtures.remove(p.getUniqueId());
        if (saved == null) { getLogger().info("WGPOC CLEAR player="+p.getName()+" restored=0"); return; }
        busy.add(p.getUniqueId());
        new BukkitRunnable() {
            int i;
            @Override public void run() {
                for (int limit=Math.min(i+2000,saved.size()); i<limit; i++) { var s=saved.get(i); s.world.getBlockAt(s.x,s.y,s.z).setBlockData(s.state,false); }
                if (i==saved.size()) { cancel(); busy.remove(p.getUniqueId()); getLogger().info("WGPOC CLEAR player="+p.getName()+" restored="+i); }
            }
        }.runTaskTimer(this,1,1);
    }
}
