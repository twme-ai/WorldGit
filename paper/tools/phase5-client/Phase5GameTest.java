package org.worldgit.fabric.gametest;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.slf4j.LoggerFactory;

/** 同既有 screenshots：真正原版客戶端、聊天 hover、箱子 UI、BossBar；只加入臨時 gametest classpath。 */
public final class Phase5GameTest implements FabricClientGameTest {
  private static final org.slf4j.Logger LOG=LoggerFactory.getLogger("PaperPhase5Evidence");
  public void runTest(ClientGameTestContext ctx) {
    String address="127.0.0.1:"+Integer.getInteger("wgtest.paperPort");
    Path ready=Path.of(System.getProperty("wgtest.paperReady"));
    ctx.runOnClient(c->{
      c.options.renderDistance().set(3);c.options.enableVsync().set(false);c.options.guiScale().set(2);c.options.languageCode="en_us";
      ConnectScreen.startConnecting(new TitleScreen(),c,ServerAddress.parseString(address),new ServerData("Paper Phase 5",address,ServerData.Type.OTHER),false,null);
    });
    ctx.waitFor(c->c.player!=null && c.level!=null,1200);
    LOG.info("PAPER5 connected={}",(Object)ctx.computeOnClient(c->c.player.getName().getString()));
    ctx.waitFor(c->Files.isRegularFile(ready),12000);
    ctx.runOnClient(c->{c.level.tickRateManager().setFrozen(false);GameTestScreens.play(c);});
    command(ctx,"wg init", "finished:");
    if(!ctx.computeOnClient(c->chat(c).contains("Init Nether too")))throw new AssertionError("missing Nether append button");
    shot(ctx,"phase5-init-buttons");
    command(ctx,"wg init --dimension minecraft:the_nether", "finished:");
    command(ctx,"wg log --graph", "finished:");shot(ctx,"phase5-graph");
    ctx.waitFor(c->bars(c).isEmpty(),1200);
    ctx.runOnClient(c->{GameTestScreens.play(c);c.gui.getChat().clearMessages(false);c.getConnection().sendCommand("wg status --full");});
    ctx.waitFor(c->!bars(c).isEmpty(),1200);ctx.waitTicks(3);ctx.takeScreenshot("phase5-bossbar-progress");
    ctx.waitFor(c->bars(c).values().stream().anyMatch(b->((Component)call(b,"getName")).getString().contains("status finished: SUCCESS")),12000);
    ctx.waitTicks(3);
    ctx.takeScreenshot("phase5-bossbar-terminal");
    ctx.waitFor(c->bars(c).isEmpty(),1200);shot(ctx,"phase5-completion");
    command(ctx,"wg switch missing-branch", "finished:");
    chatScreen(ctx);
    double[] hover=ctx.computeOnClient(c->{
      for(int y=30;y<c.getWindow().getGuiScaledHeight()-25;y+=2)for(int x=2;x<c.getWindow().getGuiScaledWidth()/2;x+=2) {
        try {
          var finder=new net.minecraft.client.gui.ActiveTextCollector.ClickableStyleFinder(c.font,x,y);
          var method=Arrays.stream(c.gui.getChat().getClass().getMethods()).filter(m->m.getName().equals("captureClickableText")).findFirst().orElseThrow();
          Class<?> mode=method.getParameterTypes()[3];
          Object foreground=mode==boolean.class?Boolean.TRUE:mode.getField("FOREGROUND").get(null);
          method.invoke(c.gui.getChat(),finder,c.getWindow().getGuiScaledHeight(),c.gui.getGuiTicks(),foreground);
          var style=finder.result();
          if(style!=null && style.getClickEvent()!=null && (style.getClickEvent().getClass().getSimpleName().equals("CopyToClipboard") || style.getClickEvent().toString().toLowerCase(Locale.ROOT).contains("copy_to_clipboard")))return new double[]{x*c.getWindow().getGuiScale(),y*c.getWindow().getGuiScale()};
        }catch(ReflectiveOperationException e){throw new RuntimeException(e);}
      }
      throw new AssertionError("copyToClipboard component not rendered");
    });
    ctx.getInput().setCursorPos(hover[0],hover[1]);ctx.waitTicks(8);ctx.takeScreenshot("phase5-error-copy-hover");
    command(ctx,"wg ignore", "finished:");
    ctx.waitFor(c->GameTestScreens.current(c) instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen,1200);
    String first=ctx.computeOnClient(c->c.player.containerMenu.getSlot(0).getItem().getHoverName().getString());
    if(!first.startsWith("1 # WorldGit") || first.startsWith("1 # #"))throw new AssertionError("ignore comment marker: "+first);
    LOG.info("PAPER5 ignore-first-line={}",first);
    double[] slotPos=ctx.computeOnClient(c->{
      var screen=GameTestScreens.current(c);var slot=c.player.containerMenu.getSlot(0);
      return new double[]{(((Number)field(screen,"leftPos")).intValue()+slot.x+8)*c.getWindow().getGuiScale(),(((Number)field(screen,"topPos")).intValue()+slot.y+8)*c.getWindow().getGuiScale()};
    });
    ctx.getInput().setCursorPos(slotPos[0],slotPos[1]);ctx.waitTicks(8);
    ctx.takeScreenshot("phase5-ignore-gui");
    command(ctx,"wg ignore add entity minecraft:cow", "Confirm change");shot(ctx,"phase5-ignore-preview");
    ctx.runOnClient(c->{c.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);GameTestScreens.title(c);});
    ctx.waitFor(c->c.level==null,600);LOG.info("PAPER5 DONE");
  }
  private static void command(ClientGameTestContext ctx,String command,String expected) {
    ctx.runOnClient(c->{GameTestScreens.play(c);c.gui.getChat().clearMessages(false);c.getConnection().sendCommand(command);});
    ctx.waitFor(c->chat(c).contains(expected),12000);ctx.waitTicks(5);
    String text=ctx.computeOnClient(Phase5GameTest::chat);
    if(text.contains("CLI") || text.contains("UUID 集合"))throw new AssertionError("unexpected offline entity hint: "+text);
    LOG.info("PAPER5 command={} chat={}",command,(Object)ctx.computeOnClient(Phase5GameTest::chat));
  }
  private static void chatScreen(ClientGameTestContext ctx) {
    ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_T);ctx.waitFor(c->GameTestScreens.current(c) instanceof ChatScreen,600);ctx.waitTicks(5);
  }
  private static void shot(ClientGameTestContext ctx,String name) {chatScreen(ctx);ctx.takeScreenshot(name);ctx.runOnClient(GameTestScreens::play);}
  private static String chat(Minecraft c) {
    return ((List<?>)field(c.gui.getChat(),"allMessages")).stream().map(row->((Component)call(row,"content")).getString()).reduce("",(a,b)->a+"\n"+b);
  }
  private static Map<?,?> bars(Minecraft c) {return (Map<?,?>)field(c.gui.getBossOverlay(),"events");}
  private static Object call(Object object,String name) {
    try {return object.getClass().getMethod(name).invoke(object);}catch(ReflectiveOperationException e){throw new RuntimeException(e);}
  }
  private static Object field(Object object,String name) {
    for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try {var field=type.getDeclaredField(name);field.setAccessible(true);return field.get(object);}catch(NoSuchFieldException ignored) {}catch(IllegalAccessException e){throw new RuntimeException(e);}
    throw new IllegalArgumentException(name);
  }
}
