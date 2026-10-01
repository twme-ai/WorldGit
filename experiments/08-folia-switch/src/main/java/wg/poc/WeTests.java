package wg.poc;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.EditSessionBuilder;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.function.pattern.BlockPattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** 以 WorldEdit/FAWE API 建立 EditSession（可指定以某玩家名義）。 we api <mode> <cx> [player]。 */
final class WeTests {
    private WeTests() {}

    static void cmd(CommandSender s, String[] a) {
        String op = a[1];
        if (op.equals("drain")) { Out.res("we_drain", "sessions", We.drain(), "stageCounts", We.stageCounts, "eventsSeen", We.eventsSeen, "fawe", We.fawe); return; }
        String mode = a[2]; int cx = Integer.parseInt(a[3]); String actorName = a.length > 4 ? a[4] : null;
        World w = Tests.world();
        Sched.at(w, cx, Tests.CZ, () -> {
            try {
                com.sk89q.worldedit.world.World ww = BukkitAdapter.adapt(w);
                EditSessionBuilder b = WorldEdit.getInstance().newEditSessionBuilder().world(ww);
                Player p = actorName == null ? null : Bukkit.getPlayerExact(actorName);
                if (p != null) b.actor(BukkitAdapter.adapt(p));
                if (mode.equals("fast") && We.fawe) b.fastMode(true);
                int n = 0;
                try (EditSession es = b.build()) {
                    CuboidRegion r = new CuboidRegion(ww, BlockVector3.at(cx * 16 + 1, 190, 17), BlockVector3.at(cx * 16 + 6, 193, 22));
                    var diamond = BlockTypes.DIAMOND_BLOCK.getDefaultState();
                    switch (mode) {
                        case "set", "fast" -> n = es.setBlocks((Region) r, diamond);
                        case "pattern" -> n = es.setBlocks((Region) r, new BlockPattern(diamond));
                        case "blocks" -> { for (BlockVector3 v : r) { es.setBlock(v, diamond); n++; } }
                        default -> {}
                    }
                }
                Out.res("we_api_done", "mode", mode, "cx", cx, "actor", actorName, "returned", n);
            } catch (Throwable t) { Out.res("error", "we_api", Tests.trace(t)); }
        });
    }
}
