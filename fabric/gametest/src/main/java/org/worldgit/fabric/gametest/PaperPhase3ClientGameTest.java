package org.worldgit.fabric.gametest;

import com.google.gson.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.worldgit.core.merge.MergeReport.Choice;
import org.worldgit.fabric.client.ClientRuntime;
import org.worldgit.fabric.logic.ClientConflicts;
import org.lwjgl.glfw.GLFW;

/** 真 client UI → vanilla command → Paper/Folia；driver 與 client 以檢查點檔案同步。 */
final class PaperPhase3ClientGameTest {
  private static Path directory;
  private static final Gson JSON=new Gson();
  static void run(ClientGameTestContext ctx,int port) {
    directory=Path.of(System.getProperty("wgtest.paperReady"));
    connect(ctx,port);
    check(ctx.computeOnClient(c->ClientRuntime.get().mergeCapable() && ClientRuntime.get().selectCapable()),"server capabilities");
    log("handshake=true merge=true select=true");
    var expected=read(ctx,"ready.json");
    awaitList(ctx,expected);
    var row=ctx.<ClientConflicts.Region,RuntimeException>computeOnClient(c->ClientRuntime.get().conflicts().regions().getFirst());
    check(expected.getAsJsonArray("regions").size()==1,"one region");
    var key=row.key();
    open(ctx,key); ctx.takeScreenshot("pair-01-conflict-list");
    var before=hashes(ctx);
    for(var choice:List.of(Choice.OURS,Choice.THEIRS,Choice.BASE)) {
      open(ctx,key); clickChoice(ctx,choice,0);
      ctx.waitFor(c->ClientRuntime.get().conflicts().preview()!=null,1200);
      var got=ctx.<JsonArray,RuntimeException>computeOnClient(c->{
        var array=new JsonArray();
        for(var cell:ClientRuntime.get().conflictPreviewCells()) {
          var item=new JsonObject(); item.add("position",JSON.toJsonTree(cell.position()));item.addProperty("state",cell.state());
          item.addProperty("be",cell.blockEntity()==null ? "" : Base64.getEncoder().encodeToString(cell.blockEntity()));array.add(item);
        }
        return array;
      });
      check(got.equals(expected.getAsJsonObject("previews").getAsJsonArray(key.region()+":"+choice)),"ghost snapshot mismatch "+choice+" "+got);
      check(before.equals(hashes(ctx)),"ghost changed live client blocks");
      ctx.waitTicks(20);
      if(choice==Choice.THEIRS) ctx.takeScreenshot("pair-02-ghost-theirs");
      log("ghost="+choice+" cells="+got.size()+" snapshot=true world-unchanged=true");
    }
    write("ghosts.json",JSON.toJsonTree(before));log("ghosts_done");read(ctx,"ghosts-checked.json");
    open(ctx,key);clickChoice(ctx,Choice.THEIRS,1);
    ctx.waitFor(c->{var r=ClientRuntime.get().conflicts().region(key);return r!=null && r.info().choice()==Choice.THEIRS && !r.info().resolved();},1200);
    ctx.waitFor(c->hashesOnClient(c).equals(expected.getAsJsonObject("hashes").getAsJsonObject("THEIRS")),1200);
    check(!before.equals(hashes(ctx)),"set did not change world");
    open(ctx,key);ctx.waitTicks(12);ctx.takeScreenshot("pair-03-set-blocks");write("set.json",JSON.toJsonTree(regions(ctx)));log("set_done");read(ctx,"set-checked.json");
    open(ctx,key);clickLabel(ctx,"Resolve: ours",0);
    // Resolve's first button is ours; check the corresponding candidate and immediate list update.
    ctx.waitFor(c->{var r=ClientRuntime.get().conflicts().region(key);return r!=null && r.info().choice()==Choice.OURS && r.info().resolved();},1200);
    ctx.waitFor(c->hashesOnClient(c).equals(expected.getAsJsonObject("hashes").getAsJsonObject("OURS")),1200);
    write("resolve.json",JSON.toJsonTree(regions(ctx)));log("resolve_done");read(ctx,"resolve-checked.json");
    open(ctx,key);ctx.clickScreenButton("worldgit.conflicts.goto");
    var b=row.info().bounds();
    ctx.waitFor(c->Math.abs(c.player.getX()-(b.minX()+.5))<.1 && Math.abs(c.player.getZ()-(b.minZ()+.5))<.1,600);
    log("teleport=true");
    command(ctx,"wg merge --continue");
    ctx.waitFor(c->ClientRuntime.get().conflicts().regions().isEmpty(),12000);
    check(before.equals(hashes(ctx)),"continue changed resolved world");
    log("small_done list-empty=true");
    var many=read(ctx,"many.json");awaitList(ctx,many);
    check(many.getAsJsonArray("regions").size()==200,"200 regions");
    open(ctx,ctx.<ClientConflicts.Key,RuntimeException>computeOnClient(c->ClientRuntime.get().conflicts().regions().getFirst().key()));
    ctx.takeScreenshot("pair-04-200-regions");
    clickLabel(ctx,">",0);ctx.waitTicks(5);
    log("many_loaded pagination=true");
    disconnect(ctx);
    ctx.waitTicks(40);connect(ctx,port);awaitList(ctx,many);
    log("reconnected regions=200");read(ctx,"reconnect-checked.json");
    command(ctx,"wg resolve all manual");
    ctx.waitFor(c->ClientRuntime.get().conflicts().regions().size()==200 && ClientRuntime.get().conflicts().regions().stream().allMatch(r->r.info().resolved()),24000);
    log("many_resolved");read(ctx,"many-resolved-checked.json");
    command(ctx,"wg merge --continue");ctx.waitFor(c->ClientRuntime.get().conflicts().regions().isEmpty(),24000);
    log("many_done list-empty=true");
    disconnect(ctx);
    log("DONE");
  }
  private static void disconnect(ClientGameTestContext ctx) {
    // 與原版離開伺服器按鈕相同：先關閉 network connection，再清除世界畫面。
    // Minecraft.disconnect(screen, false) 只做畫面／listener teardown，會留下 TCP 到 timeout。
    ctx.runOnClient(c->c.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE));
    ctx.waitFor(c->c.level==null && !ClientRuntime.get().handshaken() && !ClientRuntime.get().selectCapable()
        && ClientRuntime.get().conflicts().regions().isEmpty() && ClientRuntime.get().conflicts().preview()==null,600);
    ctx.runOnClient(GameTestScreens::title);
    ctx.waitFor(c->GameTestScreens.current(c) instanceof TitleScreen,600);
    log("disconnected state-reset=true");
  }
  private static void connect(ClientGameTestContext ctx,int port) {
    var address="127.0.0.1:"+port;
    ctx.runOnClient(c->ConnectScreen.startConnecting(new TitleScreen(),c,ServerAddress.parseString(address),new ServerData("WorldGit Phase 3",address,ServerData.Type.OTHER),false,null));
    ctx.waitFor(c->c.player!=null && ClientRuntime.get().handshaken(),2400);
  }
  private static void open(ClientGameTestContext ctx,ClientConflicts.Key key) {
    ctx.runOnClient(c->{ClientRuntime.get().selectConflict(key);ClientRuntime.get().openConflicts();});ctx.waitTicks(5);
  }
  /** 真滑鼠按鈕事件；三個同名選擇列以由上到下的位置辨識。 */
  private static void clickChoice(ClientGameTestContext ctx,Choice choice,int group) {
    clickLabel(ctx,choice.name().toLowerCase(Locale.ROOT),group);
  }
  private static void clickLabel(ClientGameTestContext ctx,String label,int group) {
    var point=ctx.<double[],RuntimeException>computeOnClient(c->{
      var buttons=GameTestScreens.current(c).children().stream().filter(e->e instanceof Button).map(e->(Button)e)
          .filter(b->b.getMessage().getString().equals(label)).sorted(Comparator.comparingInt(Button::getY)).toList();
      var button=buttons.get(group);check(button.active,"Set blocks disabled");
      return new double[]{(button.getX()+button.getWidth()/2.0)*c.getWindow().getGuiScale(),(button.getY()+10)*c.getWindow().getGuiScale()};
    });
    ctx.getInput().setCursorPos(point[0],point[1]);ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);ctx.waitTicks(4);
  }
  private static List<Object> regions(ClientGameTestContext ctx) {
    return ctx.<List<Object>,RuntimeException>computeOnClient(c->ClientRuntime.get().conflicts().regions().stream().sorted(Comparator.comparingInt(r->r.info().id())).map(r->(Object)r.info()).toList());
  }
  private static void awaitList(ClientGameTestContext ctx,JsonObject expected) {
    var rows=expected.getAsJsonArray("regions");ctx.waitFor(c->ClientRuntime.get().conflicts().regions().size()==rows.size(),12000);
    check(JSON.toJsonTree(regions(ctx)).equals(rows),"regions mismatch: "+JSON.toJson(regions(ctx))+" expected "+rows);
    log("regions="+rows.size()+" bounds-choice-resolved-authors=true");
  }
  private static JsonObject hashes(ClientGameTestContext ctx) { return ctx.<JsonObject,RuntimeException>computeOnClient(PaperPhase3ClientGameTest::hashesOnClient); }
  private static JsonObject hashesOnClient(net.minecraft.client.Minecraft c) {
    try {
      var out=new JsonObject();
      for(int cx=0;cx<=1;cx++) {
        var hash=MessageDigest.getInstance("SHA-256");
        for(int i=0;i<4096;i++) {
          var state=c.level.getBlockState(new BlockPos(cx*16+(i&15),64+(i>>8),(i>>4)&15));
          hash.update((canon(state)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        out.addProperty(Integer.toString(cx),HexFormat.of().formatHex(hash.digest()));
      }
      return out;
    } catch(Exception e) { throw new AssertionError(e); }
  }
  private static String canon(BlockState state) {
    var id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    var properties=new TreeMap<String,String>();for(var p:state.getProperties()) properties.put(p.getName(),property(state,p));
    var join=new StringJoiner(",",id+"[","]");properties.forEach((k,v)->join.add(k+"="+v));return properties.isEmpty() ? id : join.toString();
  }
  private static <T extends Comparable<T>> String property(BlockState s,net.minecraft.world.level.block.state.properties.Property<T> p) { return p.getName(s.getValue(p)); }
  private static JsonObject read(ClientGameTestContext ctx,String name) {
    var path=directory.resolve(name);ctx.waitFor(c->Files.isRegularFile(path),24000);
    try { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); } catch(Exception e) { throw new AssertionError(e); }
  }
  private static void write(String name,JsonElement data) {
    try { Files.writeString(directory.resolve(name),data.toString()); } catch(Exception e) { throw new AssertionError(e); }
  }
  private static void command(ClientGameTestContext ctx,String command) { ctx.runOnClient(c->ClientRuntime.get().command(command)); }
  private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
  private static void log(String message) { WorldGitClientGameTest.LOG.info("WGPAIR {}",message); }
}
