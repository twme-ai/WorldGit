package org.worldgit.fabric.gametest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/** 獨立 fixture mod 的入口；正式產物不包含此驅動。 */
public final class LegacyClientRunner implements ClientModInitializer {
  static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("WorldGitTest");
  public void onInitializeClient() {
    if(!Boolean.getBoolean("wgtest.phase5"))return;
    ClientTickEvents.END_CLIENT_TICK.register(client->LegacyClientContext.tick());
    ClientLifecycleEvents.CLIENT_STARTED.register(client->{
      var diagnostic = new Thread(() -> {
        var directory = java.nio.file.Path.of(System.getProperty("wgtest.phase5Dir"));
        while (true) try {
          var request = directory.resolve("dump-request");
          if (java.nio.file.Files.deleteIfExists(request)) {
            var dump = new StringBuilder();
            Thread.getAllStackTraces().forEach((thread, stack) -> {
              dump.append(thread.getName()).append(' ').append(thread.getState()).append('\n');
              for (var frame : stack) dump.append("  ").append(frame).append('\n');
            });
            java.nio.file.Files.writeString(directory.resolve("threads.txt"), dump);
          }
          Thread.sleep(100);
        } catch (InterruptedException end) { return; }
          catch (Exception error) { LOG.warn("fixture thread dump failed", error); }
      }, "WorldGit-legacy-diagnostic");
      diagnostic.setDaemon(true); diagnostic.start();
      var thread=new Thread(()->{
        try {Phase5ClientGameTest.run(new LegacyClientContext());client.execute(client::stop);}
        catch(Throwable error){LOG.error("FABRIC5 FAILED",error);client.execute(client::stop);}
      },"WorldGit-legacy-client-test");thread.setDaemon(true);thread.start();
    });
  }
}
