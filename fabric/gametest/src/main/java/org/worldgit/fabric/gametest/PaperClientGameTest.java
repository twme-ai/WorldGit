package org.worldgit.fabric.gametest;

import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.lwjgl.glfw.GLFW;
import org.worldgit.fabric.client.ClientRuntime;

/** Connects the real client to a copied Paper server managed by tools/accept-paper.py. */
final class PaperClientGameTest {
    static void run(ClientGameTestContext ctx, int port) {
        String address = "127.0.0.1:" + port;
        Path ready = Path.of(System.getProperty("wgtest.paperReady"));
        ctx.runOnClient(c -> ConnectScreen.startConnecting(new TitleScreen(), c,
                ServerAddress.parseString(address), new ServerData("WorldGit acceptance", address, ServerData.Type.OTHER), false, null));
        ctx.waitFor(c -> c.player != null && ClientRuntime.get().handshaken(), 1200);
        WorldGitClientGameTest.LOG.info("WGPAPER handshake=true");
        // The driver initializes a repo and makes exactly three changes in one section.
        ctx.waitFor(c -> Files.isRegularFile(ready), 12000);
        ctx.waitTicks(40);
        command(ctx, "wg diff --show");
        ctx.waitFor(c -> ClientRuntime.get().summary() != null && ClientRuntime.get().summary().ghost(), 2400);
        var diff = ctx.<ClientRuntime.Summary, RuntimeException>computeOnClient(c -> ClientRuntime.get().summary());
        if (diff.cells() != 3) throw new AssertionError("Paper diff 應有 3 格，實際為 " + diff);
        ctx.waitFor(c -> ClientRuntime.get().summary().detailSections() > 0, 600);
        WorldGitClientGameTest.LOG.info("WGPAPER diff {}", diff);
        ctx.waitTicks(40);
        WorldGitClientGameTest.LOG.info("WGPAPER camera={}",
                (Object) ctx.computeOnClient(c -> c.player.position()));
        ctx.getInput().pressKey(GLFW.GLFW_KEY_F1);
        ctx.waitTicks(5);
        ctx.takeScreenshot("paper-diff-default");
        command(ctx, "wgc palette colorblind");
        ctx.waitTicks(20);
        ctx.takeScreenshot("paper-diff-colorblind");
        command(ctx, "wgc palette default");
        command(ctx, "wg status --show");
        ctx.waitFor(c -> ClientRuntime.get().summary() != null && !ClientRuntime.get().summary().ghost(), 2400);
        WorldGitClientGameTest.LOG.info("WGPAPER status {}",
                (Object) ctx.<ClientRuntime.Summary, RuntimeException>computeOnClient(c -> ClientRuntime.get().summary()));
        ctx.waitTicks(20);
        ctx.takeScreenshot("paper-status-outline");
        command(ctx, "wg clear");
        ctx.waitFor(c -> ClientRuntime.get().summary() == null, 600);
        ctx.runOnClient(c -> c.disconnect(new TitleScreen(), false));
        ctx.waitFor(c -> c.level == null, 600);
        WorldGitClientGameTest.LOG.info("WGPAPER DONE");
    }

    private static void command(ClientGameTestContext ctx, String command) {
        ctx.runOnClient(c -> c.getConnection().sendCommand(command));
    }
}
