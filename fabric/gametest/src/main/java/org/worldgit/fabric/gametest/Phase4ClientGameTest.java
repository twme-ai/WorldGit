package org.worldgit.fabric.gametest;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import org.worldgit.fabric.WorldGitMod;
import org.worldgit.fabric.client.ClientRuntime;
import org.worldgit.core.merge.MergeReport.Choice;

/** 只在驗收 mod：真 client／單人 owner 操作，由 Python 控制真 Hub／CLI。 */
final class Phase4ClientGameTest {
  private static final Gson JSON=new Gson();
  private static Path directory;
  private static final class Session {
    TestSingleplayerContext initial;
    void disconnect(ClientGameTestContext ctx) {
      var server=ctx.<net.minecraft.server.MinecraftServer,RuntimeException>computeOnClient(c->c.getSingleplayerServer());
      if(initial!=null) {initial.close();initial=null;}
      else ctx.runOnClient(c->{if(c.level!=null)c.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);});
      // Client teardown precedes integrated-server saving and session.lock release.
      // Keep driving client ticks until the owner thread has finished all shutdown work.
      ctx.waitFor(c->c.level==null && (server==null || !server.getRunningThread().isAlive()),1200);
    }
  }
  static void run(ClientGameTestContext ctx) {
    directory=Path.of(System.getProperty("wgtest.phase4Dir"));
    ctx.runOnClient(c->{c.options.renderDistance().set(3);c.options.enableVsync().set(false);c.options.framerateLimit().set(60);c.options.languageCode="en_us";});
    boolean single=Boolean.getBoolean("wgtest.phase4Single");
    var session=new Session();
    try {
      if(single) {
        var world=ctx.worldBuilder().adjustSettings(ui->ui.setAllowCommands(false)).create();session.initial=world;
        world.getServer().runCommand("gamerule random_tick_speed 0");
        world.getServer().runCommand("gamemode creative @a");
        world.getServer().runCommand("tp @a 8 225 8");
        loop(ctx,true,session);
      }else {
        String address="127.0.0.1:"+Integer.getInteger("wgtest.paperPort");
        ctx.runOnClient(c->ConnectScreen.startConnecting(new TitleScreen(),c,ServerAddress.parseString(address),new ServerData("Fabric Phase 4",address,ServerData.Type.OTHER),false,null));
        loop(ctx,false,session);
      }
    }finally {
      session.disconnect(ctx);
    }
    ctx.runOnClient(c->{if(c.level!=null)c.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);GameTestScreens.title(c);});
    ctx.waitFor(c->c.level==null,600);
    WorldGitClientGameTest.LOG.info("FABRIC4 DONE");
  }
  private static void loop(ClientGameTestContext ctx,boolean single,Session session) {
    ctx.waitFor(c->c.player!=null && c.level!=null && ClientRuntime.get().handshaken(),2400);
    var ready=ctx.<JsonObject,RuntimeException>computeOnClient(c->{var v=new JsonObject();v.addProperty("player",c.player.getName().getString());
      var loader=net.fabricmc.loader.api.FabricLoader.getInstance();var artifact=loader.getModContainer("worldgit").orElseThrow().getOrigin().getPaths().getFirst();
      if(loader.isDevelopmentEnvironment() || !Files.isRegularFile(artifact))throw new AssertionError("Phase 4 must load the production jar");
      v.addProperty("productionJar",artifact.toString());
      if(single) {var rt=WorldGitMod.runtime(c.getSingleplayerServer());v.addProperty("world",rt.worldRoot().toString());v.addProperty("repository",rt.repositoryRoot().toString());}
      return v;
    });write("ready.json",ready);WorldGitClientGameTest.LOG.info("FABRIC4 READY {}",ready);
    for(int sequence=1;;sequence++) {
      String name="request-"+sequence+".json";
      ctx.waitFor(c->Files.exists(directory.resolve(name)),24000);
      var request=read(name);String action=request.get("action").getAsString();var out=new JsonObject();
      switch(action) {
        case "prepare-dimension" -> {
          if(!single)throw new AssertionError("prepare dimension requires singleplayer");
          String dimension=request.get("dimension").getAsString();
          if(!Set.of("minecraft:overworld","minecraft:the_nether","minecraft:the_end").contains(dimension))throw new AssertionError(dimension);
          var server=ctx.<net.minecraft.server.MinecraftServer,RuntimeException>computeOnClient(c->c.getSingleplayerServer());
          var key=dimension.equals("minecraft:overworld")?net.minecraft.world.level.Level.OVERWORLD:
              dimension.equals("minecraft:the_nether")?net.minecraft.world.level.Level.NETHER:net.minecraft.world.level.Level.END;
          // Test thread must keep driving client ticks while the integrated server executes tasks.
          var force=server.submit(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
              "execute in "+dimension+" run forceload add -80 -80 95 95"));
          ctx.waitFor(c->force.isDone(),2400);force.join();
          int loaded=0;
          for(int tick=0;tick<2400;tick++) {
            var counted=server.submit(()->{
              var source=server.getLevel(key).getChunkSource();int count=0;
              for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++)if(source.getChunkNow(x,z)!=null)count++;
              return count;
            });
            ctx.waitFor(c->counted.isDone(),2400);loaded=counted.join();
            if(loaded==121)break;
            ctx.waitTick();
          }
          if(loaded!=121)throw new AssertionError(dimension+" fixture chunks not loaded: "+loaded);
          var saved=server.submit(()->server.saveEverything(true,true,true));
          ctx.waitFor(c->saved.isDone(),6000);saved.join();
          out.addProperty("loadedChunks",loaded);
        }
        case "command" -> {
          String command=request.get("command").getAsString();
          if(single) {
            ctx.runOnClient(c->{var server=c.getSingleplayerServer();var id=c.player.getUUID();server.execute(()->{
              var player=server.getPlayerList().getPlayer(id);server.getCommands().performPrefixedCommand(command.startsWith("wg ")?player.createCommandSourceStack():server.createCommandSourceStack(),command);
            });});
            ctx.waitTicks(6);
            ctx.waitFor(c->{var rt=WorldGitMod.runtime(c.getSingleplayerServer());return !rt.operationActive() && !busy(rt) && !rt.ui().busy();},18000);
            ctx.waitTicks(10);
            out=ctx.computeOnClient(c->{var rt=WorldGitMod.runtime(c.getSingleplayerServer());var j=new JsonObject();j.addProperty("code",code(rt,c.player.getUUID().toString()));return j;});
          }else {command(ctx,command);ctx.waitTicks(6);}
        }
        case "comments" -> {
          command(ctx,"wg comments show 1");ctx.waitFor(c->!ClientRuntime.get().comments().isEmpty(),2400);ctx.waitTicks(20);
          ctx.waitFor(c->ClientRuntime.get().visibleCommentsCount()>0,1200);
          var rows=ctx.<List<org.worldgit.protocol.CommentsProtocol.Comment>,RuntimeException>computeOnClient(c->ClientRuntime.get().comments());
          if(!rows.getFirst().text().contains("<script>alert(1)</script>") || !rows.getFirst().text().contains("<red>") || rows.getFirst().text().contains("§c"))throw new AssertionError("literal comment");
          out.add("comments",JSON.toJsonTree(rows));ctx.takeScreenshot("phase4-comments-visible");
        }
        case "hide" -> {
          command(ctx,"wg comments hide");ctx.waitFor(c->ClientRuntime.get().comments().isEmpty(),1200);ctx.waitTicks(15);ctx.takeScreenshot("phase4-comments-hidden");out.addProperty("count",0);
        }
        case "conflict" -> {
          ctx.waitFor(c->!ClientRuntime.get().conflicts().regions().isEmpty(),2400);
          ctx.runOnClient(c->{var rt=ClientRuntime.get();rt.selectConflict(rt.conflicts().regions().getFirst().key());rt.openConflicts();});ctx.waitTicks(10);
          ctx.takeScreenshot("phase4-conflict-list");out.addProperty("regions",ctx.<Integer,RuntimeException>computeOnClient(c->ClientRuntime.get().conflicts().regions().size()));
          ctx.runOnClient(c->ClientRuntime.get().applyConflict(Choice.THEIRS,true));
          ctx.waitFor(c->ClientRuntime.get().conflicts().regions().stream().allMatch(r->r.info().resolved()),2400);
          ctx.runOnClient(c->GameTestScreens.play(c));
        }
        case "view" -> {
          // 原版 teleport/metadata 可重設飛行；觀察者相機不受重力影響。
          ctx.runOnClient(c->{
            // 26.2 單人 gamemode 命令亦改世界的預設 GameType；相機只改此玩家。
            if(single) {var server=c.getSingleplayerServer();var playerId=c.player.getUUID();server.execute(()->server.getPlayerList().getPlayer(playerId).setGameMode(net.minecraft.world.level.GameType.SPECTATOR));}
            else c.getConnection().sendCommand("gamemode spectator @s");
          });ctx.waitFor(c->c.player.isSpectator(),1200);
          ctx.runOnClient(c->{
            if(single) {var server=c.getSingleplayerServer();var playerName=c.player.getName().getString();server.execute(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"tp "+playerName+" 8 226 8 180 15"));}
            else c.getConnection().sendCommand("tp @s 8 226 8 180 15");
          });ctx.waitFor(c->c.player.getY()>225 && c.player.getY()<227,1200);ctx.waitTicks(20);
          ctx.waitFor(c->c.player.getY()>225 && c.player.getY()<227 && c.player.isSpectator(),1200);
        }
        case "state" -> {
          out=ctx.computeOnClient(c->{var v=new JsonObject();for(int x:new int[]{0,2,3,4,8})v.addProperty("block"+x,net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(c.level.getBlockState(new BlockPos(x,224,0)).getBlock()).toString());
            v.addProperty("fps",c.getFps());v.addProperty("comments",ClientRuntime.get().comments().size());v.addProperty("conflicts",ClientRuntime.get().conflicts().regions().size());
            v.addProperty("visibleComments",ClientRuntime.get().visibleCommentsCount());v.addProperty("x",c.player.getX());v.addProperty("y",c.player.getY());v.addProperty("z",c.player.getZ());
            int entities=0;for(var e:c.level.entitiesForRendering())if(e instanceof net.minecraft.world.entity.Display)entities++;v.addProperty("displays",entities);return v;});
        }
        case "disconnect" -> {
          session.disconnect(ctx);
        }
        case "open-clone" -> {
          String nameWorld=request.get("world").getAsString();ctx.runOnClient(c->c.createWorldOpenFlows().openWorld(nameWorld,()->{}));
          waitForClone(ctx,"世界載入及 Screen／overlay 關閉",Phase4ClientGameTest::inWorld);
          ctx.runOnClient(c->{if(!c.getSingleplayerServer().tickRateManager().isFrozen())throw new AssertionError("clone must be frozen before world ticks");});
          out=ctx.computeOnClient(c->{var rt=WorldGitMod.runtime(c.getSingleplayerServer());var v=new JsonObject();v.addProperty("world",rt.worldRoot().toString());v.addProperty("repository",rt.repositoryRoot().toString());return v;});
        }
        case "clone-screenshot" -> {
          if(!single)throw new AssertionError("clone screenshot requires singleplayer");
          // 只設定相機玩家，不改單人世界 GameType 或任何追蹤方塊。
          ctx.runOnClient(c->{var server=c.getSingleplayerServer();var id=c.player.getUUID();var playerName=c.player.getName().getString();server.execute(()->{
            server.getPlayerList().getPlayer(id).setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"tp "+playerName+" 4.5 229 6.5 180 45");
          });c.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);c.options.chunkSectionFadeInTime().set(0.0);});
          var stable=new int[]{0};
          waitForClone(ctx,"相機定位、合併方塊與周圍 chunk 渲染",c->{
            stable[0]=cloneRendered(c)?stable[0]+1:0;return stable[0]>=3;
          });
          boolean hidden=ctx.<Boolean,RuntimeException>computeOnClient(GameTestScreens::guiHidden);
          try {
            ctx.runOnClient(c->GameTestScreens.hideGui(c,true));
            // GUI 關閉後仍要求渲染就緒；takeScreenshot 由 API 擷取下一個 framebuffer。
            waitForClone(ctx,"無 GUI 的世界畫面",c->GameTestScreens.guiHidden(c) && cloneRendered(c));
            out=ctx.computeOnClient(Phase4ClientGameTest::cloneState);
            out.addProperty("screenshot",ctx.takeScreenshot("phase4-clone-open").getFileName().toString());
          }finally {ctx.runOnClient(c->GameTestScreens.hideGui(c,hidden));}
        }
        case "stop" -> {write("response-"+sequence+".json",out);return;}
        default -> throw new AssertionError(action);
      }
      write("response-"+sequence+".json",out);
    }
  }
  private static boolean inWorld(net.minecraft.client.Minecraft c) {
    return c.level!=null && c.player!=null && ClientRuntime.get().handshaken()
        && GameTestScreens.current(c)==null && GameTestScreens.overlay(c)==null;
  }
  private static boolean surroundingChunks(net.minecraft.client.Minecraft c) {
    if(c.level==null)return false;
    for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)if(!c.level.hasChunk(x,z))return false;
    return true;
  }
  private static boolean cloneRendered(net.minecraft.client.Minecraft c) {
    if(!inWorld(c) || !c.player.isSpectator() || !surroundingChunks(c))return false;
    var camera=GameTestScreens.camera(c);var pos=camera.position();
    return camera.isInitialized() && camera.entity()==c.player && Math.abs(pos.x-4.5)<0.05 && Math.abs(pos.z-6.5)<0.05
        && Math.abs(pos.y-c.player.getEyeY())<0.05 && Math.abs(c.player.getY()-229)<0.05
        && Math.abs(net.minecraft.util.Mth.wrapDegrees(camera.yRot()-180))<0.1 && Math.abs(camera.xRot()-45)<0.1
        && c.level.getBlockState(new BlockPos(8,224,0)).is(net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK)
        && c.level.getBlockState(new BlockPos(0,224,0)).is(net.minecraft.world.level.block.Blocks.GOLD_BLOCK)
        && c.levelRenderer.hasRenderedAllSections()
        && c.levelRenderer.isSectionCompiledAndVisible(new BlockPos(8,224,0))
        && c.levelRenderer.isSectionCompiledAndVisible(new BlockPos(0,224,0));
  }
  private static JsonObject cloneState(net.minecraft.client.Minecraft c) {
    var v=new JsonObject();v.addProperty("inWorld",inWorld(c));
    v.addProperty("screen",GameTestScreens.current(c)==null?"none":GameTestScreens.current(c).getClass().getSimpleName());
    v.addProperty("overlay",GameTestScreens.overlay(c)==null?"none":GameTestScreens.overlay(c).getClass().getSimpleName());
    v.addProperty("surroundingChunksLoaded",surroundingChunks(c));v.addProperty("renderReady",cloneRendered(c));v.addProperty("guiHidden",GameTestScreens.guiHidden(c));
    if(c.player!=null){v.addProperty("x",c.player.getX());v.addProperty("y",c.player.getY());v.addProperty("z",c.player.getZ());}
    return v;
  }
  private static void waitForClone(ClientGameTestContext ctx,String stage,java.util.function.Predicate<net.minecraft.client.Minecraft> condition) {
    try {ctx.waitFor(condition,2400);}catch(AssertionError e) {
      throw new AssertionError("clone 截圖等待逾時（2400 client ticks）："+stage+" "+ctx.computeOnClient(Phase4ClientGameTest::cloneState),e);
    }
  }
  private static boolean busy(org.worldgit.fabric.ServerRuntime rt) {
    try {var f=((Object)rt.remote()).getClass().getDeclaredField("busy");f.setAccessible(true);return !((Set<?>)f.get(rt.remote())).isEmpty();}catch(Exception e) {throw new AssertionError(e);}
  }
  private static String code(org.worldgit.fabric.ServerRuntime rt,String key) {
    try {var f=((Object)rt.remote()).getClass().getDeclaredField("pending");f.setAccessible(true);Object pending=((Map<?,?>)f.get(rt.remote())).get(key);if(pending==null)return "";var method=pending.getClass().getDeclaredMethod("code");method.setAccessible(true);return (String)method.invoke(pending);}catch(Exception e) {throw new AssertionError(e);}
  }
  private static void command(ClientGameTestContext ctx,String value) {ctx.runOnClient(c->c.getConnection().sendCommand(value));}
  private static JsonObject read(String name) {try {return JSON.fromJson(Files.readString(directory.resolve(name)),JsonObject.class);}catch(Exception e) {throw new AssertionError(e);}}
  private static void write(String name,JsonObject value) {try {var p=directory.resolve(name+".tmp");Files.writeString(p,JSON.toJson(value));Files.move(p,directory.resolve(name),StandardCopyOption.REPLACE_EXISTING);}catch(Exception e) {throw new AssertionError(e);}}
}
