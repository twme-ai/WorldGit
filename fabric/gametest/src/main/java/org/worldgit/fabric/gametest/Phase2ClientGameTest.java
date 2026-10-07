package org.worldgit.fabric.gametest;

import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.level.LightLayer;
import org.worldgit.core.apply.Scope;
import org.worldgit.core.diff.ChangeKind;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.service.WorldOperations;
import org.worldgit.fabric.WorldGitMod;
import org.worldgit.fabric.client.ClientRuntime;
import org.worldgit.protocol.Protocol;
import org.lwjgl.glfw.GLFW;

/** 真實整合伺服器與客戶端；每個斷言失敗直接讓 Gradle task 失敗。 */
final class Phase2ClientGameTest {
    private static final UUID ENTITY=UUID.fromString("54b95000-0000-0000-0000-000000000001");
    private static Path artifacts;
    static void run(ClientGameTestContext ctx) {
        try(var world=ctx.worldBuilder().create()) {
            artifacts=Files.createTempDirectory(Path.of(".").toAbsolutePath().normalize(),"phase2-evidence-");
            log("artifacts="+artifacts);
            var server=world.getServer();
            server.runCommand("gamerule random_tick_speed 0");
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("gamemode creative @p");
            server.runCommand("time set noon"); server.runCommand("weather clear");
            // 先生成本輪所有相機／玩家位置的視距；切換時探索新邊緣會正確被視為 dirty。
            server.runCommand("tp @p 7 -57 3 0 27");ctx.waitTicks(160);
            server.runCommand("tp @p 7 -57 -4 0 27");
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
            server.runCommand("setblock 3 -60 3 minecraft:stone");
            server.runCommand("setblock 5 -60 5 minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
            server.runCommand("setblock 7 -60 7 minecraft:glowstone");
            server.runCommand("setblock 9 -60 5 minecraft:chest");
            server.runCommand("data merge block 9 -60 5 {Items:[{Slot:0b,id:\"minecraft:diamond\",count:1}]}");
            server.runCommand("setblock 11 -60 7 minecraft:lectern");
            server.runCommand("setblock 20 -60 5 minecraft:obsidian");
            server.runCommand("summon armor_stand 10 -60 10 {UUID:[I;1421430784,0,0,1],NoGravity:1b,Invulnerable:1b,PersistenceRequired:1b}");
            server.runCommand("execute in minecraft:the_nether run setblock 0 64 0 minecraft:glowstone");
            server.runCommand("execute in minecraft:the_nether run summon armor_stand 2 65 2 {UUID:[I;1421430784,0,0,2],NoGravity:1b,Invulnerable:1b,PersistenceRequired:1b,Tags:[\"cross_dimension\"]}");
            ctx.waitFor(c->ClientRuntime.get().handshaken(),600);
            WorldGitClientGameTest.trackAllEntities(server);
            server.runCommand("wg init --all"); awaitCommits(ctx,server,1);
            command(ctx,server,"wg branch A");
            for(var dimension:List.of("minecraft:the_nether","minecraft:the_end")) commandIn(ctx,server,dimension,"wg branch A");
            var a=head(ctx,server);
            log("A="+a);

            // B: remove/add/modify, chest inventory, UUID movement, biome, POI, adjacent chunk.
            server.runCommand("setblock 3 -60 3 minecraft:air");
            server.runCommand("setblock 4 -60 3 minecraft:diamond_block");
            server.runCommand("setblock 5 -60 5 minecraft:cobblestone");
            server.runCommand("setblock 7 -60 7 minecraft:air");
            server.runCommand("data merge block 9 -60 5 {Items:[{Slot:0b,id:\"minecraft:emerald\",count:2}]}");
            server.runCommand("setblock 11 -60 7 minecraft:air");
            server.runCommand("setblock 12 -60 7 minecraft:lectern");
            server.runCommand("setblock 20 -60 5 minecraft:gold_block");
            server.runCommand("fillbiome 0 -60 0 15 -57 15 minecraft:desert");
            server.runCommand("tp @e[type=minecraft:armor_stand] 22 -60 10");
            server.runCommand("execute in minecraft:the_nether run setblock 0 64 0 minecraft:gold_block");
            server.runCommand("execute in minecraft:the_nether as @e[tag=cross_dimension] in minecraft:overworld run tp @s 26 -60 10");
            server.runCommand("wg commit -m Phase2-B"); awaitCommits(ctx,server,2);
            // B 也修改了地獄；Phase 5 的玩家 commit 只提交目前維度，必須另存地獄分支。
            server.runCommand("execute in minecraft:the_nether run wg commit -m Phase2-B");
            awaitCommits(ctx,server,new DimensionId("minecraft:the_nether"),2);
            command(ctx,server,"wg branch B");
            for(var dimension:List.of("minecraft:the_nether","minecraft:the_end")) commandIn(ctx,server,dimension,"wg branch B");
            var b=head(ctx,server);
            log("B="+b);
            var verifyBefore=await(ctx,server.computeOnServer(s->WorldGitMod.runtime(s).live(org.worldgit.core.model.DimensionId.OVERWORLD,ops->ops.verify("B",null,Scope.all(),true))),2400);
            check(verifyBefore.success(),"B commit 與世界不符："+verifyBefore);
            checkpoint(server,"B-before-preview");

            // Use the real command, decode the same v2 cells and compare with core working→A.
            send(ctx,"wg preview A --radius 1");
            ctx.waitFor(c->ClientRuntime.get().summary()!=null,2400);
            ctx.waitTicks(30);
            var preview=await(ctx,server.computeOnServer(s->WorldGitMod.runtime(s).preview(DimensionId.OVERWORLD,"A",Scope.chunkRadius(0,-1,1).chunks(),true,-64,320)),2400);
            var decoded=new ArrayList<Protocol.Cell>();
            for(var packet:preview.packets()) {
                var msg=Protocol.decode(packet);
                if(msg instanceof Protocol.DiffPart part) decoded.addAll(part.cells());
            }
            check(!decoded.isEmpty(),"preview 無方塊");
            check(new HashSet<>(ctx.computeOnClient(c->ClientRuntime.get().previewCells())).equals(new HashSet<>(decoded)),"客戶端鬼影與 working→A diff 不一致");
            check(decoded.stream().anyMatch(c->c.x()==3 && c.y()==-60 && c.z()==3 && c.kind()==ChangeKind.ADDED),"preview A 新增方向錯誤");
            check(decoded.stream().anyMatch(c->c.x()==4 && c.y()==-60 && c.z()==3 && c.kind()==ChangeKind.REMOVED),"preview A 移除方向錯誤");
            log("preview-cells="+decoded);
            var cellsJson=new com.google.gson.JsonArray();
            for(var cell:decoded) {
                var row=new com.google.gson.JsonObject();
                row.addProperty("x",cell.x());row.addProperty("y",cell.y());row.addProperty("z",cell.z());
                row.addProperty("kind",cell.kind().name().toLowerCase(Locale.ROOT));
                row.addProperty("before",cell.before());row.addProperty("after",cell.after());cellsJson.add(row);
            }
            Files.writeString(artifacts.resolve("preview-cells.json"),cellsJson.toString());
            toggleGui(ctx); ctx.waitTicks(10); ctx.takeScreenshot("phase2-01-preview-A");
            var verifyAfter=await(ctx,server.computeOnServer(s->WorldGitMod.runtime(s).live(org.worldgit.core.model.DimensionId.OVERWORLD,ops->ops.verify("B",null,Scope.all(),true))),2400);
            check(verifyAfter.success(),"preview 修改了世界");
            checkpoint(server,"B-after-preview");
            send(ctx,"wg preview A --radius 1");ctx.waitFor(c->ClientRuntime.get().summary()!=null,2400);
            send(ctx,"wg preview off"); ctx.waitFor(c->ClientRuntime.get().summary()==null,600);
            log("preview-readonly=true clear=true");
            send(ctx,"wg status --show");ctx.waitTicks(30);
            send(ctx,"wg preview A --radius 1");ctx.waitFor(c->ClientRuntime.get().summary()!=null,2400);

            // A player remains in a block that becomes solid. No teleport, selective damage suppression.
            server.runCommand("tp @p 3.5 -60 3.5");
            // 測玩家傷害保護，只改玩家；26.2 單人 gamemode 命令亦改世界預設 GameType。
            server.computeOnServer(s->s.getPlayerList().getPlayers().getFirst().setGameMode(net.minecraft.world.level.GameType.SURVIVAL));
            var switched=(WorldOperations.Result)command(ctx,server,"wg switch A");
            check(switched.success(),"switch A 失敗："+switched);
            ctx.waitTicks(40);
            compareBlocks(ctx,server,-1,31,-62,-57,-1,15);
            var protectedPlayer=server.computeOnServer(s->{
                var p=s.getPlayerList().getPlayers().getFirst();
                float before=p.getHealth(); var at=p.position();
                for(var type:List.of(DamageTypes.FALL,DamageTypes.IN_WALL,DamageTypes.DROWN)) p.hurtServer(s.overworld(),new net.minecraft.world.damagesource.DamageSource(s.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE).getOrThrow(type)),4);
                return before==p.getHealth() && p.position().equals(at);
            });
            check(protectedPlayer,"玩家保護失敗");
            // 主世界保護檢查完成，再測地獄；另一個操作可能超過十秒的保護期限。
            server.computeOnServer(s->s.getPlayerList().getPlayers().getFirst().setGameMode(net.minecraft.world.level.GameType.CREATIVE));
            var lighting=server.computeOnServer(s->s.overworld().getBrightness(LightLayer.BLOCK,new BlockPos(7,-60,7)));
            check(lighting>0,"glowstone 光照未重算");
            check(server.computeOnServer(s->s.overworld().getPoiManager().getType(new BlockPos(11,-60,7)).isPresent()
                && s.overworld().getPoiManager().getType(new BlockPos(12,-60,7)).isEmpty()),"POI 未移除／重建");
            commandIn(ctx,server,"minecraft:the_nether","wg switch A");
            check(server.computeOnServer(s->s.getLevel(net.minecraft.world.level.Level.NETHER).getBlockState(new BlockPos(0,64,0)).is(net.minecraft.world.level.block.Blocks.GLOWSTONE)),"地獄 section 未還原");
            check(server.computeOnServer(s->{long count=0;for(var e:s.overworld().getAllEntities()) if(e.getUUID().equals(ENTITY)) count++;return count;})==1,"實體重複／遺失");
            check(server.computeOnServer(s->{long count=0;for(var level:s.getAllLevels()) for(var e:level.getAllEntities()) if(e.getUUID().equals(new UUID(0x54b9500000000000L,2))) count++;return count;})==1,"跨維度實體重複／遺失");
            check(summary(ctx)==null,"套用後仍有鬼影");
            var saved=await(ctx,server.computeOnServer(s->WorldGitMod.runtime(s).live(org.worldgit.core.model.DimensionId.OVERWORLD,ops->ops.verify("A",null,Scope.all(),true))),2400);
            check(saved.success(),"A 存檔 verify 非零："+saved);
            checkpoint(server,"A-switched");
            log("switch-A=true client-blocks=3366 protection=true light="+lighting+" entities=1 verify=0");
            server.runCommand("gamemode creative @p"); server.runCommand("tp @p 7 -57 -4 0 27");
            ctx.waitTicks(20); ctx.takeScreenshot("phase2-02-switch-A");

            commandIn(ctx,server,"minecraft:the_nether","wg switch B"); command(ctx,server,"wg switch B");
            server.runCommand("tp @p 7 -57 3 0 27"); // center chunk 0,0
            var dry=(WorldOperations.Result)command(ctx,server,"wg restore A --chunks 0 --dry-run");
            check(dry.state()==WorldOperations.State.DRY_RUN,"dry-run 寫回");
            check(block(server,20,-60,5).contains("gold_block"),"dry-run 動了範圍外");
            var restored=(WorldOperations.Result)command(ctx,server,"wg restore A --chunks 0");
            check(restored.success(),"restore 失敗");
            check(block(server,3,-60,3).contains("stone") && block(server,20,-60,5).contains("gold_block"),"restore 半徑裁切錯誤");
            check(head(ctx,server).equals(b),"restore 移動 HEAD");
            log("restore-radius=true head-unchanged=true dry-run=true");
            compareBlocks(ctx,server,0,31,-61,-59,0,15);
            ctx.waitTicks(20); ctx.takeScreenshot("phase2-03-restore-radius");
            command(ctx,server,"wg reset --hard");
            server.runCommand("setblock 3 -60 3 minecraft:lapis_block");
            var pushed=(WorldOperations.Result)command(ctx,server,"wg stash push phase2-roundtrip");
            check(pushed.success() && block(server,3,-60,3).contains("air"),"stash push 失敗");
            command(ctx,server,"wg stash list");
            var popped=(WorldOperations.Result)command(ctx,server,"wg stash pop");
            check(popped.success() && block(server,3,-60,3).contains("lapis_block"),"stash pop 失敗");
            log("stash-roundtrip=true");
            command(ctx,server,"wg reset --hard");
            server.runCommand("setblock 3 -60 3 minecraft:lapis_block");
            var stashedSwitch=(WorldOperations.Result)command(ctx,server,"wg switch A --stash");
            check(stashedSwitch.success(),"switch --stash 失敗");
            commandIn(ctx,server,"minecraft:the_nether","wg switch A");
            commandIn(ctx,server,"minecraft:the_nether","wg switch B"); command(ctx,server,"wg switch B");
            check(((WorldOperations.Result)command(ctx,server,"wg stash pop")).success(),"switch stash pop 失敗");
            command(ctx,server,"wg reset --hard");
            server.runCommand("setblock 3 -60 3 minecraft:lapis_block");
            command(ctx,server,"wg stash push discard"); command(ctx,server,"wg stash drop");
            var box=(WorldOperations.Result)command(ctx,server,"wg restore A --box 3 -60 3 3 -60 3");
            check(box.success() && block(server,4,-60,3).contains("diamond_block"),"box 範圍外被修改");
            command(ctx,server,"wg reset --hard");
            log("box=true switch-stash=true stash-drop=true reset=true");

            // Enough batches to cancel after a completed mutation, not before dispatch.
            server.runCommand("fill 0 -60 0 47 -45 15 minecraft:blue_wool");
            var previous=server.computeOnServer(s->WorldGitMod.runtime(s).lastOperation());
            send(ctx,"wg switch A --force");
            CompletableFuture<?> active=waitNewOperation(ctx,server,previous);
            for(int i=0;i<2400;i++) {
                var progress=server.computeOnServer(s->WorldGitMod.runtime(s).lastProgress());
                if(progress!=null && progress.completedSections()>0) break;
                check(!active.isDone(),"作業在可取消前已完成"); ctx.waitTick();
            }
            send(ctx,"wg cancel");
            var cancelled=(WorldOperations.Result)await(ctx,active,2400);
            check(cancelled.state()==WorldOperations.State.PARTIAL,"取消未回報 PARTIAL："+cancelled);
            check(head(ctx,server).equals(b),"取消移動 HEAD");
            var recovered=(WorldOperations.Result)command(ctx,server,"wg switch A --force");
            check(recovered.success(),"PARTIAL 無法恢復："+recovered);
            commandIn(ctx,server,"minecraft:the_nether","wg switch A");
            compareBlocks(ctx,server,0,47,-61,-45,0,15);
            log("cancel-partial=true recovered=true");
            checkpoint(server,"A-recovered");
            server.runCommand("tp @p 7 -57 -4 0 27");
            toggleGui(ctx); ctx.runOnClient(c->{c.options.languageCode="zh_tw";c.options.broadcastOptions();});
            ctx.waitTicks(10);
            check(server.computeOnServer(s->s.getPlayerList().getPlayers().getFirst().clientInformation().language().equals("zh_tw")),"繁中 client information 未更新");
            command(ctx,server,"wg branch"); ctx.waitTicks(20); ctx.takeScreenshot("phase2-04-recovered-zh-tw");
            server.runOnServer(s->s.saveEverything(true,true,true));
            log("world="+server.computeOnServer(s->WorldGitMod.runtime(s).worldRoot()));
            log("final-head="+a);
        } catch(Exception ex) { throw new AssertionError("Phase 2 gametest",ex); }
        log("DONE");
    }
    private static String head(ClientGameTestContext ctx,TestServerContext server) {
        return await(ctx,server.computeOnServer(s->WorldGitMod.runtime(s).log(DimensionId.OVERWORLD,10)),2400).getFirst().id();
    }
    private static String block(TestServerContext server,int x,int y,int z) { return server.computeOnServer(s->s.overworld().getBlockState(new BlockPos(x,y,z)).toString()); }
    private static ClientRuntime.Summary summary(ClientGameTestContext ctx) { return ctx.computeOnClient(c->ClientRuntime.get().summary()); }
    private static void compareBlocks(ClientGameTestContext ctx,TestServerContext server,int x1,int x2,int y1,int y2,int z1,int z2) {
        var expected=server.computeOnServer(s->{var list=new ArrayList<String>();for(int x=x1;x<=x2;x++)for(int y=y1;y<=y2;y++)for(int z=z1;z<=z2;z++)list.add(s.overworld().getBlockState(new BlockPos(x,y,z)).toString());return list;});
        ctx.waitFor(c->{var list=new ArrayList<String>();for(int x=x1;x<=x2;x++)for(int y=y1;y<=y2;y++)for(int z=z1;z<=z2;z++)list.add(c.level.getBlockState(new BlockPos(x,y,z)).toString());return list.equals(expected);},1200);
        log("client-server-blocks="+expected.size());
    }
    private static Object command(ClientGameTestContext ctx,TestServerContext server,String command) {
        var previous=server.computeOnServer(s->WorldGitMod.runtime(s).lastOperation());
        send(ctx,command); var future=waitNewOperation(ctx,server,previous); var result=await(ctx,future,3600);
        if(result instanceof WorldOperations.Result r) check(r.success(),command+": "+r);
        log("command="+command+" result="+result); return result;
    }
    private static Object commandIn(ClientGameTestContext ctx,TestServerContext server,String dimension,String command) {
        // 真指令明確設定維度；先移除 UUID 的來源，再切目的維度，保留唯一性。
        var primary=head(ctx,server);
        var previous=server.computeOnServer(s->WorldGitMod.runtime(s).lastOperation());
        server.runCommand("execute in "+dimension+" run "+command);
        var result=await(ctx,waitNewOperation(ctx,server,previous),3600);
        if(result instanceof WorldOperations.Result r) check(r.success(),command+": "+r);
        check(head(ctx,server).equals(primary),"地獄操作移動了主世界 HEAD："+command);
        log("dimension="+dimension+" command="+command+" result="+result);
        return result;
    }
    private static CompletableFuture<?> waitNewOperation(ClientGameTestContext ctx,TestServerContext server,CompletableFuture<?> previous) {
        for(int i=0;i<2400;i++) { var next=server.computeOnServer(s->WorldGitMod.runtime(s).lastOperation());if(next!=null && next!=previous)return next;ctx.waitTick(); }
        throw new AssertionError("指令未開始");
    }
    private static void send(ClientGameTestContext ctx,String command) { ctx.runOnClient(c->c.getConnection().sendCommand(command)); }
    private static void toggleGui(ClientGameTestContext ctx) {
        ctx.getInput().pressKey(GLFW.GLFW_KEY_F1); ctx.waitTick();
        ctx.waitTick();
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
    private static <T> T await(ClientGameTestContext ctx,CompletableFuture<T> future,int ticks) {
        for(int i=0;i<ticks && !future.isDone();i++)ctx.waitTick();check(future.isDone(),"操作逾時");return future.join();
    }
    private static void awaitCommits(ClientGameTestContext ctx,TestServerContext server,int count) {
        awaitCommits(ctx,server,DimensionId.OVERWORLD,count);
    }
    private static void awaitCommits(ClientGameTestContext ctx,TestServerContext server,DimensionId dimension,int count) {
        for(int i=0;i<180;i++) {try {if(await(ctx,server.computeOnServer(s->WorldGitMod.runtime(s).log(dimension,10)),600).size()>=count)return;}catch(CompletionException notYet){}ctx.waitTicks(10);}throw new AssertionError("commit 逾時："+dimension);
    }
    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    private static void log(String message) { WorldGitClientGameTest.LOG.info("WGTEST2 {}",message); }
}
