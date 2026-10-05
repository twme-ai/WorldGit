package org.worldgit.fabric.gametest;

import java.nio.file.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.entity.Display;
import org.slf4j.LoggerFactory;

/** 僅供 paper/tools 的實機證據；以暫時 Gradle init script 加入測試，不修改 Fabric。 */
public final class Phase4DisplayGameTest implements FabricClientGameTest {
  @Override public void runTest(ClientGameTestContext ctx) {
    var log=LoggerFactory.getLogger("PaperPhase4Evidence");
    String address="127.0.0.1:"+Integer.getInteger("wgtest.paperPort");
    Path ready=Path.of(System.getProperty("wgtest.paperReady"));
    Path viewReady=Path.of(ready+".view");
    ctx.runOnClient(c->{
      c.options.renderDistance().set(3);c.options.enableVsync().set(false);c.options.languageCode="en_us";
      ConnectScreen.startConnecting(new TitleScreen(),c,ServerAddress.parseString(address),new ServerData("Paper Phase 4",address,ServerData.Type.OTHER),false,null);
    });
    ctx.waitFor(c->c.player!=null && c.level!=null,1200);
    log.info("PAPER4 connected={}",(Object)ctx.computeOnClient(c->c.player.getName().getString()));
    ctx.waitFor(c->Files.isRegularFile(ready),12000);
    ctx.waitFor(c->c.player.getAbilities().mayfly,1200);
    ctx.runOnClient(c->{
      c.player.getAbilities().flying=true;c.player.onUpdateAbilities();
    });
    log.info("PAPER4 flying=true");
    // 先把飛行送給伺服器，再讓伺服器 teleport；不得 local setPos 跳 160 格觸發位置回復。
    ctx.waitFor(c->Files.isRegularFile(viewReady) && Math.abs(c.player.getY()-224)<.1,2400);
    // 原版 Display.tick 要先初始化 render state；只讓客戶端繼續渲染，不能推進伺服器世界。
    ctx.runOnClient(c->{c.level.tickRateManager().setFrozen(false);c.player.setYRot(0);c.player.setXRot(0);c.getConnection().sendCommand("wg comments show 1");});
    ctx.waitFor(c->{for(var e:c.level.entitiesForRendering())if(e instanceof Display.TextDisplay)return true;return false;},2400);
    ctx.waitTicks(40);
    String text=ctx.computeOnClient(c->{
      for(var e:c.level.entitiesForRendering()) if(e instanceof Display.TextDisplay t) {
        if(t.getText().getStyle().getClickEvent()!=null)throw new AssertionError("Hub text has click event");
        return t.getText().getString();
      }
      throw new AssertionError("missing TextDisplay");
    });
    if(!text.contains("<script>alert(1)</script>") || !text.contains("<red>"))throw new AssertionError("markup did not remain literal: "+text);
    log.info("PAPER4 literal={}",text);
    ctx.runOnClient(c->{c.player.setYRot(0);c.player.setXRot(0);});ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_F1);ctx.waitTicks(15);
    log.info("PAPER4 camera={}",(Object)ctx.computeOnClient(c->c.player.position()));
    ctx.takeScreenshot("phase4-comments-visible");
    ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_F1);ctx.runOnClient(c->c.getConnection().sendCommand("wg comments hide"));
    ctx.waitFor(c->{for(var e:c.level.entitiesForRendering())if(e instanceof Display.TextDisplay)return false;return true;},1200);
    ctx.waitTicks(20);ctx.takeScreenshot("phase4-comments-hidden");
    ctx.runOnClient(c->{c.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);GameTestScreens.title(c);});
    ctx.waitFor(c->c.level==null,600);
    log.info("PAPER4 DONE");
  }
}
