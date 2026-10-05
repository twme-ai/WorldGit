package org.worldgit.fabric.gametest;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.glfw.GLFW;
import org.worldgit.core.apply.Scope;
import org.worldgit.core.merge.MergeReport;
import org.worldgit.core.merge.MergeReport.Choice;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.service.WorldOperations;
import org.worldgit.fabric.WorldGitMod;
import org.worldgit.fabric.client.ClientRuntime;
import org.worldgit.fabric.logic.ClientConflicts;

/**
 * Phase 3：真實整合伺服器與客戶端的單人世界合併。涵蓋零衝突合併、衝突清單 UI（G 鍵）、
 * ours／theirs／base 疊圖（世界不變）、原地切換（方塊 state 逐格與快照相同）、manual、abort、continue。
 */
final class Phase3ClientGameTest {
    private static final int X1=0,X2=45,Y1=-61,Y2=-58,Z1=0,Z2=16;
    private static Path artifacts;
    private static String mainName;

    static void run(ClientGameTestContext ctx) {
        try(var world=ctx.worldBuilder().create()) {
            artifacts=Files.createTempDirectory(Path.of(".").toAbsolutePath().normalize(),"phase3-evidence-");
            log("artifacts="+artifacts);
            var server=world.getServer();
            server.runCommand("gamerule random_tick_speed 0");
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("gamemode creative @p");
            server.runCommand("time set noon"); server.runCommand("weather clear");
            server.runCommand("tp @p 24 -57 3 0 27"); ctx.waitTicks(160);
            server.runCommand("tp @p 14.5 -57 2 0 30");
            ctx.waitTicks(240);
            server.runCommand("tick freeze");
            for(var dimension:List.of(net.minecraft.world.level.Level.NETHER,net.minecraft.world.level.Level.END)) {
                String name=dimension.equals(net.minecraft.world.level.Level.NETHER)?"minecraft:the_nether":"minecraft:the_end";
                server.runCommand("execute in "+name+" run forceload add 0 0");
                for(int i=0;i<600;i++) {
                    if(server.computeOnServer(s->s.getLevel(dimension).getChunkSource().getChunkNow(0,0)!=null)) break;
                    ctx.waitTick();
                }
                check(server.computeOnServer(s->s.getLevel(dimension).getChunkSource().getChunkNow(0,0)!=null),name+" 測試 chunk 未載入");
            }
            ctx.waitTicks(100);
            ctx.waitFor(c->ClientRuntime.get().handshaken(),600);
            check(ctx.computeOnClient(c->ClientRuntime.get().mergeCapable()),"客戶端沒有 merge-regions-v1 capability");
            server.runCommand("wg init"); awaitHead(ctx,server,null);
            mainName=await(ctx,server.computeOnServer(s->WorldGitMod.runtime(s).live(ops->ops.branches().stream().filter(b->b.current()).findFirst().orElseThrow().name())),1800);
            log("main="+mainName);
            var base=head(ctx,server);
            var baseCells=cells(server);

            // ---- 1. 不同位置：零衝突 -------------------------------------------------
            set(server,3,-60,3,"minecraft:gold_block"); set(server,3,-60,4,"minecraft:gold_block");
            commit(ctx,server,"ours-clean");
            command(ctx,server,"wg branch t1 "+base);
            command(ctx,server,"wg switch t1");
            set(server,8,-60,3,"minecraft:lapis_block"); set(server,40,-60,3,"minecraft:lapis_block");
            commit(ctx,server,"t1-clean");
            command(ctx,server,"wg switch "+mainName);
            var clean=(WorldOperations.MergeResult)command(ctx,server,"wg merge t1");
            check(clean.success() && clean.merging()==null && "COMPLETE".equals(clean.state()),"零衝突合併應直接完成："+clean);
            check(await(ctx,server.computeOnServer(s->WorldGitMod.runtime(s).merging()),600)==null,"零衝突後仍 MERGING");
            var m2cells=cells(server);
            for(var e:Map.of(3+",-60,3","minecraft:gold_block",8+",-60,3","minecraft:lapis_block",40+",-60,3","minecraft:lapis_block").entrySet())
                check(e.getValue().equals(m2cells.get(e.getKey())),"零衝突結果缺少 "+e.getKey());
            var cleanVerify=verify(ctx,server);
            check(cleanVerify.success(),"零衝突合併 verify 非零："+cleanVerify);
            checkpoint(server,"clean-merge");
            log("clean-merge=true verify=0 regions=0");
            var m2=head(ctx,server);

            // ---- 2. 同位置衝突：門、柵欄（連接 state）、紅石 ------------------------------
            set(server,10,-60,10,"minecraft:oak_door[half=lower,facing=north,hinge=left,open=false,powered=false]");
            set(server,10,-59,10,"minecraft:oak_door[half=upper,facing=north,hinge=left,open=false,powered=false]");
            set(server,14,-60,10,"minecraft:oak_fence"); set(server,15,-60,10,"minecraft:oak_fence");
            set(server,18,-60,10,"minecraft:redstone_wire"); set(server,19,-60,10,"minecraft:redstone_wire");
            commit(ctx,server,"ours-conflict");
            var oursCells=cells(server);
            check("minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=false]".equals(oursCells.get("14,-60,10")),
                "ours 柵欄預期連向東："+oursCells.get("14,-60,10"));
            command(ctx,server,"wg branch topic "+m2);
            command(ctx,server,"wg switch topic");
            set(server,10,-60,10,"minecraft:iron_door[half=lower,facing=east,hinge=right,open=false,powered=false]");
            set(server,10,-59,10,"minecraft:iron_door[half=upper,facing=east,hinge=right,open=false,powered=false]");
            set(server,14,-60,10,"minecraft:nether_brick_fence");
            set(server,18,-60,10,"minecraft:repeater[facing=east,delay=2]");
            set(server,30,-60,6,"minecraft:emerald_block");
            commit(ctx,server,"topic-conflict");
            var theirsCells=cells(server);
            command(ctx,server,"wg switch "+mainName);
            check(cells(server).equals(oursCells),"switch 回 ours 後世界與 ours 快照不同");
            var oursHead=head(ctx,server);
            log("ours-head="+oursHead);

            // ---- 3. 開始合併，再 abort，確認逐格回到合併前 ------------------------------
            var started=(WorldOperations.MergeResult)command(ctx,server,"wg merge topic");
            check(started.success() && "MERGING".equals(started.state()) && started.merging().remaining()==3,"預期 3 個衝突區域："+started);
            var regions=regions(ctx,server);
            check(regions.size()==3,"區域數 "+regions.size());
            var s0=cells(server);
            check("minecraft:emerald_block".equals(s0.get("30,-60,6")),"自動合併的 theirs 方塊未寫入");
            var expectedS0=new TreeMap<>(oursCells); expectedS0.put("30,-60,6","minecraft:emerald_block");
            check(new TreeMap<>(s0).equals(expectedS0),"合併初始世界應為 ours＋自動合併："+diff(expectedS0,s0));
            checkpoint(server,"merging-initial");
            writeRegions(regions);
            ctx.waitFor(c->ClientRuntime.get().conflicts().regions().size()==3,600);
            log("client-regions="+ctx.computeOnClient(c->ClientRuntime.get().conflicts().regions().size()));

            var door=region(regions,10); var fence=region(regions,14); var wire=region(regions,18);
            check(wire.redstone() && !door.redstone(),"紅石旗標錯誤 door="+door.redstone()+" wire="+wire.redstone());
            check(!door.oursAuthors().isEmpty() && !door.theirsAuthors().isEmpty(),"區域缺少作者");
            check(door.blockCount()==2,"門區域應含兩個半格："+door.blockCount());
            // 交界提示：客戶端收到 updateShapes（不自動處理）
            var hints=ctx.<Integer,RuntimeException>computeOnClient(c->ClientRuntime.get().conflicts().regions().stream().mapToInt(r->r.hints().size()).sum());
            log("client-hints="+hints);

            if (System.getenv("WG_REGION_PROFILE_ONLY") != null) {
                for (var choice : List.of(Choice.THEIRS, Choice.BASE, Choice.OURS, Choice.THEIRS, Choice.BASE, Choice.OURS))
                    ui(ctx, server, door, choice, false);
                log("profile-only=true");
                return;
            }

            // 疊圖＋UI 預覽：先 G 鍵開清單（截圖），選區域，ours／theirs／base 的格子與快照相同，世界不變
            server.runCommand("tp @p 14.5 -57 2 0 30");
            ctx.waitTicks(10);
            ctx.getInput().pressKey(GLFW.GLFW_KEY_G); ctx.waitTicks(12);
            ctx.takeScreenshot("phase3-01-conflict-list");
            for(var r:regions) {
                for(var choice:List.of(Choice.THEIRS,Choice.OURS,Choice.BASE)) {
                    var expected=choice==Choice.THEIRS ? theirsCells : choice==Choice.OURS ? oursCells : m2cells;
                    previewAndCheck(ctx,r,choice,expected);
                    check(cells(server).equals(s0),"疊圖修改了世界 "+r.id()+" "+choice);
                    if(r==door && choice==Choice.THEIRS) {
                        ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE); ctx.waitTicks(4);
                        ctx.getInput().pressKey(GLFW.GLFW_KEY_F1); ctx.waitTicks(12);
                        ctx.takeScreenshot("phase3-02-overlay-theirs"); ctx.getInput().pressKey(GLFW.GLFW_KEY_F1); ctx.waitTicks(4);
                        ctx.getInput().pressKey(GLFW.GLFW_KEY_G); ctx.waitTicks(10);
                    }
                    if(r==door && choice==Choice.OURS) {
                        ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE); ctx.waitTicks(4);
                        ctx.getInput().pressKey(GLFW.GLFW_KEY_F1); ctx.waitTicks(12);
                        ctx.takeScreenshot("phase3-03-overlay-ours"); ctx.getInput().pressKey(GLFW.GLFW_KEY_F1); ctx.waitTicks(4);
                        ctx.getInput().pressKey(GLFW.GLFW_KEY_G); ctx.waitTicks(10);
                    }
                }
            }
            ctx.takeScreenshot("phase3-04-conflict-detail");
            ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE); ctx.waitTicks(4);
            ctx.runOnClient(c->ClientRuntime.get().hideConflictPreview());
            ctx.getInput().pressKey(GLFW.GLFW_KEY_F1); ctx.waitTicks(15);
            ctx.takeScreenshot("phase3-05-region-outline"); ctx.getInput().pressKey(GLFW.GLFW_KEY_F1); ctx.waitTicks(4);
            check(cells(server).equals(s0),"疊圖流程後世界被修改");
            log("overlay=true world-unchanged=true");

            // UI 解決兩個區域後 abort，必須逐格回到合併前
            ui(ctx,server,door,Choice.THEIRS,true);
            ui(ctx,server,fence,Choice.THEIRS,false);
            var aborted=(WorldOperations.MergeResult)command(ctx,server,"wg merge --abort");
            check(aborted.success() && aborted.merging()==null,"abort 失敗："+aborted);
            check(cells(server).equals(oursCells),"abort 後世界與合併前不同："+diff(oursCells,cells(server)));
            check(head(ctx,server).equals(oursHead),"abort 移動了 HEAD");
            check(await(ctx,server.computeOnServer(s->WorldGitMod.runtime(s).merging()),600)==null,"abort 後仍 MERGING");
            ctx.waitFor(c->ClientRuntime.get().conflicts().regions().isEmpty(),600);
            checkpoint(server,"after-abort");
            log("abort=true cells-identical="+oursCells.size());

            // ---- 4. 再合併一次，用 UI 解決並 continue -------------------------------------
            var again=(WorldOperations.MergeResult)command(ctx,server,"wg merge topic");
            check(again.success() && again.merging().remaining()==3,"二次合併失敗："+again);
            regions=regions(ctx,server);
            door=region(regions,10); fence=region(regions,14); wire=region(regions,18);
            ctx.waitFor(c->ClientRuntime.get().conflicts().regions().size()==3,600);
            check(cells(server).equals(s0),"二次合併初始世界不同");
            // 逐一切換 ours/theirs/base（不標解決），只改區域原子，且 state 與快照相同
            for(var r:regions) for(var choice:List.of(Choice.THEIRS,Choice.BASE,Choice.OURS)) {
                ui(ctx,server,r,choice,false);
                var expected=choice==Choice.THEIRS ? theirsCells : choice==Choice.OURS ? oursCells : m2cells;
                var now=cells(server); var atoms=atoms(r);
                for(var e:s0.entrySet()) {
                    var want=atoms.contains(e.getKey()) ? expected.get(e.getKey()) : e.getValue();
                    check(want.equals(now.get(e.getKey())),"切換 #"+r.id()+" "+choice+" 在 "+e.getKey()+" 預期 "+want+" 實際 "+now.get(e.getKey()));
                }
                // 其他區域保持 ours（未被切換）；鄰居未被更新
                log("switch region="+r.id()+" choice="+choice+" ok=true");
            }
            // 柵欄：theirs 時 14 為 nether brick（east=false），15 仍是 ours 的 oak_fence[west=true]（不重算）
            ui(ctx,server,fence,Choice.THEIRS,false);
            var fenceNow=cells(server);
            check(theirsCells.get("14,-60,10").equals(fenceNow.get("14,-60,10")),"柵欄 14 theirs state 不同");
            check(oursCells.get("15,-60,10").equals(fenceNow.get("15,-60,10")) && fenceNow.get("15,-60,10").contains("west=true"),
                "柵欄 15 的連接 state 被改動："+fenceNow.get("15,-60,10"));
            log("fence-state-kept=true "+fenceNow.get("15,-60,10"));
            final int fenceId=fence.id();
            ctx.waitFor(c->ClientRuntime.get().conflicts().regions().stream().anyMatch(r->r.key().region()==fenceId && !r.hints().isEmpty()),600);
            log("fence-hints="+ctx.<Integer,RuntimeException>computeOnClient(c->ClientRuntime.get().conflicts().regions().stream().filter(r->r.key().region()==fenceId).mapToInt(r->r.hints().size()).sum())+" (theirs 選擇後的交界提示)");
            ctx.runOnClient(c->{});
            server.runCommand("tp @p 14.5 -57 2 0 30"); ctx.waitTicks(10);

            ui(ctx,server,door,Choice.THEIRS,true);
            ui(ctx,server,fence,Choice.THEIRS,true);
            ui(ctx,server,wire,Choice.THEIRS,false);
            set(server,18,-60,10,"minecraft:redstone_lamp[lit=false]");
            var manualNeighbor=cells(server).get("19,-60,10"); // 玩家自己的 setblock 會讓原版更新鄰居（不是 WorldGit 造成）
            ui(ctx,server,wire,Choice.MANUAL,true);
            var state=await(ctx,server.computeOnServer(s->WorldGitMod.runtime(s).merging()),600);
            check(state!=null && state.remaining()==0,"應全部解決："+(state==null ? null : state.remaining()));
            var expectedFinal=new TreeMap<>(s0);
            for(var r:List.of(door,fence)) for(var p:atoms(r)) expectedFinal.put(p,theirsCells.get(p));
            expectedFinal.put("18,-60,10","minecraft:redstone_lamp[lit=false]");
            expectedFinal.put("19,-60,10",manualNeighbor);
            var beforeContinue=cells(server);
            check(new TreeMap<>(beforeContinue).equals(expectedFinal),"解決後世界與預期不同："+diff(expectedFinal,beforeContinue));
            checkpoint(server,"all-resolved");
            var done=(WorldOperations.MergeResult)command(ctx,server,"wg merge --continue");
            check(done.success() && done.merging()==null,"continue 失敗："+done);
            check(await(ctx,server.computeOnServer(s->WorldGitMod.runtime(s).merging()),600)==null,"continue 後仍 MERGING");
            var finalCells=cells(server);
            check(new TreeMap<>(finalCells).equals(expectedFinal),"continue 後世界改變："+diff(expectedFinal,finalCells));
            var finalVerify=verify(ctx,server);
            check(finalVerify.success(),"最終 verify 非零："+finalVerify);
            ctx.waitFor(c->ClientRuntime.get().conflicts().regions().isEmpty(),600);
            ctx.waitTicks(10);
            ctx.getInput().pressKey(GLFW.GLFW_KEY_F1); ctx.waitTicks(10);
            ctx.takeScreenshot("phase3-06-resolved-clean"); ctx.getInput().pressKey(GLFW.GLFW_KEY_F1);
            var finalJson=new com.google.gson.JsonObject();
            for(var e:expectedFinal.entrySet()) if(e.getKey().matches("1[0-9],-6[0-9],10|10,-59,10")) finalJson.addProperty(e.getKey(),e.getValue());
            Files.writeString(artifacts.resolve("expected-final.json"),finalJson.toString());
            checkpoint(server,"final");
            server.runOnServer(s->s.saveEverything(true,true,true));
            log("final-head="+head(ctx,server)+" verify=0 merging=null");
        } catch(Exception ex) { throw new AssertionError("Phase 3 gametest",ex); }
        log("DONE");
    }

    // ---- 客戶端 UI 動作 -------------------------------------------------------------

    private static void previewAndCheck(ClientGameTestContext ctx,MergeReport.Region r,Choice choice,Map<String,String> expected) {
        var key=new ClientConflicts.Key(r.dimension(),r.id());
        ctx.runOnClient(c->{ClientRuntime.get().selectConflict(key);});
        ctx.waitTicks(3);
        ctx.runOnClient(c->ClientRuntime.get().previewConflict(choice));
        ctx.waitFor(c->!ClientRuntime.get().conflictPreviewCells().isEmpty(),1200);
        ctx.waitTicks(10);
        var got=new TreeMap<String,String>();
        for(var cell:ctx.<List<org.worldgit.protocol.MergeProtocol.PreviewCell>,RuntimeException>computeOnClient(c->ClientRuntime.get().conflictPreviewCells()))
            got.put(cell.position().x()+","+cell.position().y()+","+cell.position().z(),cell.state());
        var want=new TreeMap<String,String>();
        for(var p:atoms(r)) want.put(p,expected.get(p));
        check(got.equals(want),"疊圖 #"+r.id()+" "+choice+" 與快照不符："+diff(want,got));
        log("preview region="+r.id()+" choice="+choice+" cells="+got.size()+" match=true");
    }

    /** 以 UI 使用的相同路徑（客戶端命令）切換／解決，等伺服器操作完成。 */
    private static void ui(ClientGameTestContext ctx,TestServerContext server,MergeReport.Region r,Choice choice,boolean resolve) {
        var key=new ClientConflicts.Key(r.dimension(),r.id());
        ctx.runOnClient(c->ClientRuntime.get().selectConflict(key));
        var previous=server.computeOnServer(s->WorldGitMod.runtime(s).lastOperation());
        long switchStarted=System.nanoTime();
        ctx.runOnClient(c->ClientRuntime.get().applyConflict(choice,resolve));
        var result=await(ctx,waitNewOperation(ctx,server,previous),3600);
        if(result instanceof WorldOperations.MergeResult m) check(m.success(),"UI 切換 #"+r.id()+" "+choice+" 失敗："+m);
        log("region-latency id="+r.id()+" choice="+choice+" resolve="+resolve+" seconds="+(System.nanoTime()-switchStarted)/1_000_000_000.0);
        ctx.waitTicks(4);
    }

    // ---- 世界狀態 --------------------------------------------------------------------

    private static String canon(BlockState s) {
        var id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
        var props=new TreeMap<String,String>();
        for(var p:s.getProperties()) props.put(p.getName(),value(s,p));
        if(props.isEmpty()) return id;
        var j=new StringJoiner(",",id+"[","]"); props.forEach((k,v)->j.add(k+"="+v)); return j.toString();
    }
    private static <T extends Comparable<T>> String value(BlockState s,net.minecraft.world.level.block.state.properties.Property<T> p) { return p.getName(s.getValue(p)); }
    private static TreeMap<String,String> cells(TestServerContext server) {
        return server.computeOnServer(s->{
            var map=new TreeMap<String,String>();
            for(int x=X1;x<=X2;x++) for(int y=Y1;y<=Y2;y++) for(int z=Z1;z<=Z2;z++)
                map.put(x+","+y+","+z,canon(s.overworld().getBlockState(new BlockPos(x,y,z))));
            return map;
        });
    }
    private static void set(TestServerContext server,int x,int y,int z,String block) { server.runCommand("setblock "+x+" "+y+" "+z+" "+block); }
    private static Set<String> atoms(MergeReport.Region r) {
        var out=new TreeSet<String>();
        for(var a:r.atoms()) if(a.kind()==MergeReport.Kind.BLOCK) out.add(a.position().x()+","+a.position().y()+","+a.position().z());
        return out;
    }
    private static List<MergeReport.Region> regions(ClientGameTestContext ctx,TestServerContext server) {
        var state=await(ctx,server.computeOnServer(s->WorldGitMod.runtime(s).merging()),600);
        check(state!=null,"不在 MERGING");
        return state.regions();
    }
    private static MergeReport.Region region(List<MergeReport.Region> list,int minX) {
        return list.stream().filter(r->r.bounds()!=null && r.bounds().minX()==minX).findFirst().orElseThrow(()->new AssertionError("找不到 x="+minX+" 的區域"));
    }
    private static void writeRegions(List<MergeReport.Region> regions) throws Exception {
        var array=new com.google.gson.JsonArray();
        for(var r:regions) {
            var o=new com.google.gson.JsonObject(); o.addProperty("id",r.id()); o.addProperty("count",r.blockCount());
            var b=r.bounds(); o.addProperty("bounds",b.minX()+","+b.minY()+","+b.minZ()+","+b.maxX()+","+b.maxY()+","+b.maxZ());
            o.addProperty("redstone",r.redstone()); array.add(o);
        }
        Files.writeString(artifacts.resolve("regions.json"),array.toString());
    }
    private static String diff(Map<String,String> want,Map<String,String> got) {
        var out=new ArrayList<String>();
        for(var k:new TreeSet<>(want.keySet())) if(!Objects.equals(want.get(k),got.get(k))) out.add(k+": want "+want.get(k)+" got "+got.get(k));
        for(var k:got.keySet()) if(!want.containsKey(k)) out.add(k+": unexpected "+got.get(k));
        return out.stream().limit(12).toList().toString();
    }

    // ---- 指令／repo ---------------------------------------------------------------------

    private static String head(ClientGameTestContext ctx,TestServerContext server) {
        return await(ctx,server.computeOnServer(s->WorldGitMod.runtime(s).log(10)),2400).stream().filter(row->row.commits().containsKey(DimensionId.OVERWORLD)).findFirst().orElseThrow().commits().get(DimensionId.OVERWORLD);
    }
    private static void awaitHead(ClientGameTestContext ctx,TestServerContext server,String previous) {
        for(int i=0;i<180;i++) {
            try { var h=head(ctx,server); if(!h.equals(previous)) return; } catch(CompletionException|NoSuchElementException notYet) {}
            ctx.waitTicks(10);
        }
        throw new AssertionError("commit 逾時");
    }
    private static void commit(ClientGameTestContext ctx,TestServerContext server,String message) {
        var previous=head(ctx,server); server.runCommand("wg commit -m "+message); awaitHead(ctx,server,previous);
    }
    private static WorldOperations.Result verify(ClientGameTestContext ctx,TestServerContext server) {
        return await(ctx,server.computeOnServer(s->WorldGitMod.runtime(s).live(ops->ops.verify(mainName,null,Scope.all(),true))),2400);
    }
    private static Object command(ClientGameTestContext ctx,TestServerContext server,String command) {
        var previous=server.computeOnServer(s->WorldGitMod.runtime(s).lastOperation());
        ctx.runOnClient(c->c.getConnection().sendCommand(command));
        var result=await(ctx,waitNewOperation(ctx,server,previous),3600);
        if(result instanceof WorldOperations.Result r) check(r.success(),command+": "+r);
        log("command="+command+" result="+result); ctx.waitTicks(2); return result;
    }
    private static CompletableFuture<?> waitNewOperation(ClientGameTestContext ctx,TestServerContext server,CompletableFuture<?> previous) {
        for(int i=0;i<2400;i++) { var next=server.computeOnServer(s->WorldGitMod.runtime(s).lastOperation());if(next!=null && next!=previous)return next;ctx.waitTick(); }
        throw new AssertionError("指令未開始");
    }
    private static <T> T await(ClientGameTestContext ctx,CompletableFuture<T> future,int ticks) {
        for(int i=0;i<ticks && !future.isDone();i++)ctx.waitTick();check(future.isDone(),"操作逾時");return future.join();
    }
    private static void checkpoint(TestServerContext server,String label) throws Exception {
        var source=server.computeOnServer(s->{s.saveEverything(true,true,true);return WorldGitMod.runtime(s).worldRoot();});
        var destination=artifacts.resolve(label);
        copy(source,destination.resolve("world"));
        if(!Files.isDirectory(source.resolve(".worldgit"))) copy(source.getParent().resolve(".worldgit").resolve(source.getFileName()),destination.resolve(".worldgit/world"));
        log("checkpoint="+label);
    }
    private static void copy(Path from,Path to) throws Exception {
        try(var paths=Files.walk(from)) {
            for(var source:paths.toList()) {
                var target=to.resolve(from.relativize(source));
                if(Files.isDirectory(source))Files.createDirectories(target);
                else if(!source.getFileName().toString().equals("session.lock")) Files.copy(source,target);
            }
        }
    }
    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    private static void log(String message) { WorldGitClientGameTest.LOG.info("WGTEST3 {}",message); }
}
