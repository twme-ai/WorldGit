package wg.poc;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.*;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/** PacketEvents 監聽：BlockChange / MultiBlockChange / ChunkData / UpdateLight / BlockEntityData。 */
public final class Pkt extends PacketListenerAbstract {
    public record Rec(long nanos, String type, String player, int cx, int cz, int sy, int entries) {}
    public final ConcurrentLinkedQueue<Rec> recs = new ConcurrentLinkedQueue<>();
    public volatile int errors;
    public volatile String lastError = "";

    public Pkt() { super(PacketListenerPriority.MONITOR); }

    public static void install() {
        PacketEvents.getAPI().getEventManager().registerListener(new Pkt());
    }
    public static Pkt instance;

    @Override
    public void onPacketSend(PacketSendEvent e) {
        try {
            var t = e.getPacketType();
            String name = e.getUser() == null ? "?" : e.getUser().getName();
            if (t == PacketType.Play.Server.BLOCK_CHANGE) {
                var w = new WrapperPlayServerBlockChange(e);
                var p = w.getBlockPosition();
                add("BLOCK_CHANGE", name, p.getX() >> 4, p.getZ() >> 4, p.getY() >> 4, 1);
            } else if (t == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
                var w = new WrapperPlayServerMultiBlockChange(e);
                var p = w.getChunkPosition(); // section 座標
                add("MULTI_BLOCK_CHANGE", name, p.getX(), p.getZ(), p.getY(), w.getBlocks().length);
            } else if (t == PacketType.Play.Server.CHUNK_DATA) {
                var w = new WrapperPlayServerChunkData(e);
                add("CHUNK_DATA", name, w.getColumn().getX(), w.getColumn().getZ(), 0, 1);
            } else if (t == PacketType.Play.Server.UPDATE_LIGHT) {
                var w = new WrapperPlayServerUpdateLight(e);
                add("UPDATE_LIGHT", name, w.getX(), w.getZ(), 0, 1);
            } else if (t == PacketType.Play.Server.BLOCK_ENTITY_DATA) {
                var w = new WrapperPlayServerBlockEntityData(e);
                var p = w.getPosition();
                add("BLOCK_ENTITY_DATA", name, p.getX() >> 4, p.getZ() >> 4, p.getY() >> 4, 1);
            } else if (t == PacketType.Play.Server.UNLOAD_CHUNK) {
                var w = new WrapperPlayServerUnloadChunk(e);
                add("UNLOAD_CHUNK", name, w.getChunkX(), w.getChunkZ(), 0, 1);
            }
        } catch (Throwable ex) {
            errors++; lastError = ex.toString();
        }
    }

    private void add(String type, String player, int cx, int cz, int sy, int n) {
        recs.add(new Rec(System.nanoTime(), type, player, cx, cz, sy, n));
    }

    /** 自 since 起，指定 chunk 的封包統計：type -> {packets, entries, players} */
    public Map<String, Object> summarize(long since, int cx, int cz) {
        Map<String, int[]> pk = new TreeMap<>();
        Map<String, Set<String>> pl = new TreeMap<>();
        for (Rec r : recs) if (r.nanos >= since && r.cx == cx && r.cz == cz) {
            pk.computeIfAbsent(r.type, k -> new int[2])[0]++;
            pk.get(r.type)[1] += r.entries;
            pl.computeIfAbsent(r.type, k -> new TreeSet<>()).add(r.player);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : pk.entrySet()) out.put(e.getKey(), Out.m("packets", e.getValue()[0], "entries", e.getValue()[1], "players", pl.get(e.getKey())));
        return out;
    }
}
