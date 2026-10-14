package org.worldgit.fabric;

import java.util.*;
import java.security.MessageDigest;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.phys.Vec3;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.platform.PlayerProtection;

/** 獨立驗收 mod；正式 jar 不包含此類別，僅主控台及 acceptance JVM 可啟用。 */
public final class DedicatedServerFixture implements ModInitializer {
    private static final List<Long> ticks=new ArrayList<>();
    private static final Set<ChunkPos> tickets=new HashSet<>();
    private static IsolationProbe isolationProbe;
    private static Adversary adversary;
    private static PendingProbe pendingProbe;
    @Override public void onInitialize() {
        if(!Boolean.getBoolean("worldgit.acceptance")) return;
        ServerTickEvents.END_SERVER_TICK.register(server->{ticks.add(System.nanoTime());if(isolationProbe!=null)isolationProbe.tick();if(adversary!=null)adversary.tick();if(pendingProbe!=null)pendingProbe.tick();});
        // 第三方模組的 /git：驗證 WorldGit 遇到衝突時跳過裸 /git。
        if(Boolean.getBoolean("worldgit.fixture.git")) CommandRegistrationCallback.EVENT.register((dispatcher,context,selection)->dispatcher.register(Commands.literal("git")
            .executes(ctx->{ctx.getSource().sendSuccess(()->net.minecraft.network.chat.Component.literal("OTHER_GIT"),false);return 1;})));
        CommandRegistrationCallback.EVENT.register((dispatcher,context,selection)->dispatcher.register(Commands.literal("wg").then(Commands.literal("test")
            .requires(source->source.getPlayer()==null)
            .then(Commands.argument("args",com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes(ctx->{
                var source=ctx.getSource();var level=source.getLevel();var rt=WorldGitMod.runtime(source.getServer());
                var args=com.mojang.brigadier.arguments.StringArgumentType.getString(ctx,"args").split(" ");
                try {
                    switch(args[0]) {
                        case "fixture-stable" -> {
                            source.getServer().getCommands().performPrefixedCommand(source,"gamerule randomTickSpeed 0");
                            say(source,"WGSTABLE ready; simulation running");
                        }
                        case "probe-start" -> {
                            var other=source.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
                            other.getChunkSource().addRegionTicket(PROBE_TICKET,new net.minecraft.world.level.ChunkPos(0,0),2,new net.minecraft.world.level.ChunkPos(0,0));
                            other.getChunk(0,0);
                            probeBasin(other,0);
                            level.getChunkSource().addRegionTicket(PROBE_TICKET,new net.minecraft.world.level.ChunkPos(64,0),2,new net.minecraft.world.level.ChunkPos(0,0));level.getChunk(64,0);
                            probeBasin(level,1024);
                            isolationProbe=new IsolationProbe(level,other);say(source,"WGPROBE started");
                        }
                        case "probe-adversary" -> {if(adversary!=null)throw new IllegalStateException("adversary active");adversary=new Adversary(rt,level,source);}
                        case "probe-storage" -> storageProbe(rt,level,source);
                        case "probe-pending" -> {if(pendingProbe!=null)throw new IllegalStateException("pending probe active");pendingProbe=new PendingProbe(rt,level,source);}
                        case "probe-reset" -> {isolationProbe.reset();say(source,"WGPROBE reset");}
                        case "probe-stats" -> say(source,"WGPROBE "+isolationProbe.json());
                        case "sample" -> {
                            int cx=Integer.parseInt(args[1]),cz=Integer.parseInt(args[2]),sy=Integer.parseInt(args[3]);
                            var hash=MessageDigest.getInstance("SHA-256");
                            for(int i=0;i<4096;i++) hash.update((BlockStateParser.serialize(level.getBlockState(new BlockPos(cx*16+(i&15),sy*16+(i>>8),cz*16+((i>>4)&15))))+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            say(source,"WGSAMPLE hash="+HexFormat.of().formatHex(hash.digest()));
                        }
                        case "fill" -> {
                            int side=Integer.parseInt(args[1]),count=Integer.parseInt(args[3]),local=Integer.parseInt(args[4]);
                            var block=Blocks.AIR.defaultBlockState();
                            block=BlockStateParser.parseForBlock(level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BLOCK).asLookup(),args[2],false).blockState();
                            for(int i=0;i<count;i++) {
                                int x=i%side-side/2,z=i/side-side/2; var chunk=new ChunkPos(x,z);tickets.add(chunk);
                                level.getChunkSource().addRegionTicket(TICKET,new net.minecraft.world.level.ChunkPos(x,z),0,new net.minecraft.world.level.ChunkPos(x,z));
                                level.setBlock(new BlockPos(x*16+local,64,z*16+10),block,2);
                            }
                            say(source,"WGFILL done count="+count);
                        }
                        case "release" -> {for(var pos:tickets) level.getChunkSource().removeRegionTicket(TICKET,new net.minecraft.world.level.ChunkPos(pos.x(),pos.z()),0,new net.minecraft.world.level.ChunkPos(pos.x(),pos.z()));tickets.clear();say(source,"WGRELEASE done");}
                        case "tick-reset" -> {ticks.clear();say(source,"WGTICKS reset");}
                        case "ticks" -> {
                            var values=new ArrayList<Double>();for(int i=1;i<ticks.size();i++)values.add((ticks.get(i)-ticks.get(i-1))/1e6);
                            double total=values.stream().mapToDouble(Double::doubleValue).sum();Collections.sort(values);
                            say(source,"WGTICKS count="+values.size()+" tps="+(values.size()*1000/total)+" p99="+values.get(Math.min(values.size()-1,(int)(values.size()*.99)))+" max="+values.getLast());
                        }
                        case "locks" -> {locks(rt,level);say(source,"WGLOCKS block=true outside=true piston=true explosion=true fertilizer=true console=true");}
                        case "natural-cow" -> {
                            // 與玩家動作無關的自然出生（不在 TouchScope 內）：不得進入 player-touched 集合。
                            var cow=(net.minecraft.world.entity.Mob)net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(new net.minecraft.resources.ResourceLocation("minecraft:cow")).create(level);
                            cow.setPos(Double.parseDouble(args[1]),Double.parseDouble(args[2]),Double.parseDouble(args[3]));
                            cow.setNoAi(true);cow.setNoGravity(true);level.addFreshEntity(cow);
                            say(source,"WGCOW uuid="+cow.getUUID());
                        }
                        case "entities" -> {
                            var rows=new ArrayList<String>();
                            for(var entity:level.getAllEntities()) if(!(entity instanceof net.minecraft.world.entity.player.Player)) rows.add(entity.getType().builtInRegistryHolder().key().location()+":"+entity.getUUID());
                            Collections.sort(rows);say(source,"WGENTITIES "+level.dimension().location()+" "+rows);
                        }
                        case "known" -> {
                            var id=UUID.fromString(args[1]);
                            var manager=(org.worldgit.fabric.mixin.EntityManagerAccess<net.minecraft.world.entity.Entity>)(Object)((org.worldgit.fabric.mixin.ServerLevelAccess)level).worldgit$entities();
                            var sections=new ArrayList<String>();
                            for(long packed:manager.worldgit$chunksToSave()) manager.worldgit$sections().getExistingSectionsInChunk(packed).flatMap(section->section.getEntities())
                                .filter(entity->entity.getUUID().equals(id)).forEach(entity->sections.add(entity.chunkPosition()+" saved="+entity.shouldBeSaved()+" removed="+entity.isRemoved()));
                            say(source,"WGKNOWN "+level.dimension().location()+" "+((org.worldgit.fabric.mixin.ServerLevelAccess)level).worldgit$entities().isLoaded(id)+" touched="+rt.touched().known(id)+" sections="+sections);
                        }
                        case "be-remove", "be-check" -> {
                            var at=new BlockPos(Integer.parseInt(args[1]),Integer.parseInt(args[2]),Integer.parseInt(args[3]));
                            var chunk=level.getChunkAt(at);
                            if(args[0].equals("be-remove")) chunk.removeBlockEntity(at);
                            boolean present=chunk.getBlockEntity(at,net.minecraft.world.level.chunk.LevelChunk.EntityCreationType.CHECK)!=null;
                            say(source,"WGBE present="+present);
                        }
                        case "auto" -> {rt.autoCommit(org.worldgit.fabric.logic.Msg.of(org.worldgit.fabric.logic.MessageKeys.COMMIT_AUTO_LOGOUT,"player","fixture"));say(source,"WGAUTO queued");}
                        case "protection" -> {
                            var player=source.getServer().getPlayerList().getPlayerByName(args[1]);
                            if(player==null)throw new IllegalArgumentException("player");
                            if(args.length>2 && args[2].equals("expired")) {
                                require(!rt.protects(player,level.damageSources().fall()),"expiry");say(source,"WGPROTECT expired=true");break;
                            }
                            for(var damage:List.of(level.damageSources().fall(),level.damageSources().inWall(),level.damageSources().drown())) {
                                require(rt.protects(player,damage),"protection absent");
                                float health=player.getHealth();player.hurt(damage,3);require(player.getHealth()==health,"protected damage");
                            }
                            require(!rt.protects(player,level.damageSources().generic()),"generic protected");
                            say(source,"WGPROTECT fall=true suffocation=true drowning=true generic=false");
                        }
                        default -> throw new IllegalArgumentException("fixture command");
                    }
                    return 1;
                } catch(Throwable e) {
                    source.sendFailure(net.minecraft.network.chat.Component.literal("WGTESTFAIL "+e));
                    e.printStackTrace();return 0;
                }
            })))));
    }
    private static final net.minecraft.server.level.TicketType TICKET=net.minecraft.server.level.TicketType.create("worldgit-probe",java.util.Comparator.comparingLong(net.minecraft.world.level.ChunkPos::toLong));
    private static final net.minecraft.server.level.TicketType<net.minecraft.world.level.ChunkPos> PROBE_TICKET=net.minecraft.server.level.TicketType.create("worldgit-probe",java.util.Comparator.comparingLong(net.minecraft.world.level.ChunkPos::toLong));
    /** A contained basin keeps natural Nether terrain from choosing another flow direction. */
    private static void probeBasin(ServerLevel level,int offset) {
        for(int x=6;x<=10;x++)for(int z=6;z<=10;z++) {
            level.setBlock(new BlockPos(offset+x,89,z),Blocks.STONE.defaultBlockState(),2);
            for(int y=90;y<=93;y++)level.setBlock(new BlockPos(offset+x,y,z),
                y<=91 && (x==6 || x==10 || z==6 || z==10) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(),2);
        }
        for(int x=12;x<=15;x++)for(int z=7;z<=9;z++)for(int y=91;y<=94;y++)
            level.setBlock(new BlockPos(offset+x,y,z),Blocks.AIR.defaultBlockState(),2);
    }
    private static final class IsolationProbe {
        final ServerLevel a,b;
        long start,aLast,bLast,bTicks,aSamples,aChanges,bAdvances,flowAdvances,redstoneAdvances,lastNanos;
        double maxGap;
        boolean previousPaused,flowStarted,lastPowered,farStarted,farPowered;
        long farTicks,farFlow,farRedstone,farLast=-1;
        IsolationProbe(ServerLevel a,ServerLevel b) {this.a=a;this.b=b;reset();}
        void reset() {farTicks=0;farFlow=0;farRedstone=0;farLast=-1;farStarted=false;farPowered=false;start=System.nanoTime();aLast=-1;bLast=-1;bTicks=0;aSamples=0;aChanges=0;bAdvances=0;flowAdvances=0;redstoneAdvances=0;maxGap=0;lastNanos=0;previousPaused=false;flowStarted=false;lastPowered=false;}
        void tick() {
            long now=System.nanoTime(),at=a.getGameTime(),bt=b.getGameTime();boolean paused=locked();far(paused);
            if(lastNanos!=0)maxGap=Math.max(maxGap,(now-lastNanos)/1e6);lastNanos=now;
            if(paused) {aSamples++;if(previousPaused && aLast!=-1 && at!=aLast)aChanges++;}
            if(bLast!=-1 && bt>bLast) {bTicks+=bt-bLast;if(paused && previousPaused)bAdvances+=bt-bLast;}
            aLast=at;bLast=bt;previousPaused=paused;
            if(paused && !flowStarted) {
                for(int x=7;x<=9;x++)for(int z=7;z<=9;z++)b.setBlock(new BlockPos(x,90,z),Blocks.AIR.defaultBlockState(),2);
                b.setBlock(new BlockPos(8,90,8),Blocks.WATER.defaultBlockState(),3);flowStarted=true;
                for(int x=13;x<=14;x++)b.setBlock(new BlockPos(x,92,8),Blocks.AIR.defaultBlockState(),3);
                for(int x=13;x<=14;x++)b.setBlock(new BlockPos(x,92,8),Blocks.OBSERVER.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.ObserverBlock.FACING,x==13 ? Direction.EAST : Direction.WEST),3);
            }
            if(paused && flowStarted && b.getFluidState(new BlockPos(9,90,8)).is(net.minecraft.tags.FluidTags.WATER))flowAdvances++;
            var observer=b.getBlockState(new BlockPos(13,92,8));
            if(flowStarted && observer.is(Blocks.OBSERVER)) {
                boolean powered=observer.getValue(net.minecraft.world.level.block.ObserverBlock.POWERED);
                if(paused && previousPaused && powered!=lastPowered)redstoneAdvances++;
                lastPowered=powered;
            }
        }
        boolean locked() {
            var runtime=WorldGitMod.runtime(a.getServer());
            try {return runtime.ticksLocked(ServerRuntime.dimensionId(a),new ChunkPos(0,0));}
            catch(NoSuchMethodError legacy) {return runtime.editsLocked();}
        }
        void far(boolean locked) {
            long time=a.getGameTime();if(locked && farLast!=-1 && time>farLast)farTicks+=time-farLast;farLast=time;
            if(locked && !farStarted) {
                for(int x=1031;x<=1033;x++)for(int z=7;z<=9;z++)a.setBlock(new BlockPos(x,90,z),Blocks.AIR.defaultBlockState(),2);
                a.setBlock(new BlockPos(1032,90,8),Blocks.WATER.defaultBlockState(),3);
                for(int x=1037;x<=1038;x++)a.setBlock(new BlockPos(x,92,8),Blocks.AIR.defaultBlockState(),3);
                for(int x=1037;x<=1038;x++)a.setBlock(new BlockPos(x,92,8),Blocks.OBSERVER.defaultBlockState().setValue(net.minecraft.world.level.block.ObserverBlock.FACING,x==1037 ? Direction.EAST : Direction.WEST),3);
                farStarted=true;
            }
            if(locked && farStarted && a.getFluidState(new BlockPos(1032,90,9)).is(net.minecraft.tags.FluidTags.WATER))farFlow++;
            var observer=a.getBlockState(new BlockPos(1037,92,8));
            if(farStarted && observer.is(Blocks.OBSERVER)) {boolean powered=observer.getValue(net.minecraft.world.level.block.ObserverBlock.POWERED);if(locked && powered!=farPowered)farRedstone++;farPowered=powered;}
        }
        String json() {return String.format(java.util.Locale.ROOT,"{\"b_tps\":%.3f,\"b_ticks\":%d,\"locked_samples\":%d,\"a_game_time_advances\":%d,\"b_advances_while_locked\":%d,\"b_flow_while_locked\":%d,\"b_redstone_while_locked\":%d,\"max_gap_ms\":%.3f,\"far_ticks\":%d,\"far_flow\":%d,\"far_redstone\":%d}",bTicks/((System.nanoTime()-start)/1e9),bTicks,aSamples,aChanges,bAdvances,flowAdvances,redstoneAdvances,maxGap,farTicks,farFlow,farRedstone);}
    }
    private static final class Adversary {
        final ServerRuntime runtime;final ServerLevel level;final net.minecraft.commands.CommandSourceStack source;
        final org.worldgit.core.config.EntityTagRegistry semantics;
        AutoCloseable lock;String before;int start;boolean released;
        final net.minecraft.world.entity.item.FallingBlockEntity incoming;
        final net.minecraft.world.entity.Entity incomingProjectile;
        Adversary(ServerRuntime runtime,ServerLevel level,net.minecraft.commands.CommandSourceStack source) throws Exception {
            this.runtime=runtime;this.level=level;this.source=source;
            semantics=org.worldgit.core.config.EntityTagRegistry.load(runtime.worldRoot(),ChunkCapture.currentDataVersion());
            level.getChunkSource().addRegionTicket(PROBE_TICKET,new net.minecraft.world.level.ChunkPos(0,0),2,new net.minecraft.world.level.ChunkPos(0,0));level.getChunk(0,0);
            for(int x=12;x<=15;x++)for(int z=2;z<=14;z++)level.setBlock(new BlockPos(x,90,z),Blocks.STONE.defaultBlockState(),2);
            level.setBlock(new BlockPos(15,91,2),Blocks.WATER.defaultBlockState(),3);level.setBlock(new BlockPos(15,91,12),Blocks.LAVA.defaultBlockState(),3);
            for(int x=13;x<=14;x++)level.setBlock(new BlockPos(x,94,8),Blocks.OBSERVER.defaultBlockState().setValue(net.minecraft.world.level.block.ObserverBlock.FACING,x==13 ? Direction.EAST : Direction.WEST),3);
            level.setBlock(new BlockPos(15,94,8),Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING,Direction.EAST),3);
            level.setBlock(new BlockPos(14,99,6),Blocks.SAND.defaultBlockState(),2);
            var sand=net.minecraft.world.entity.item.FallingBlockEntity.fall(level,new BlockPos(14,99,6),Blocks.SAND.defaultBlockState());sand.setDeltaMovement(new Vec3(.1,-.3,0));
            var cow=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(new net.minecraft.resources.ResourceLocation("minecraft:cow")).create(level);cow.setPos(14.5,92,5.5);cow.setDeltaMovement(new Vec3(.1,0,0));level.addFreshEntity(cow);
            level.setBlock(new BlockPos(14,91,10),Blocks.HOPPER.defaultBlockState(),2);
            lock=runtime.lockChunks(Map.of(ServerRuntime.dimensionId(level),Set.of(new ChunkPos(0,0))));
            level.getChunkSource().addRegionTicket(PROBE_TICKET,new net.minecraft.world.level.ChunkPos(2,0),2,new net.minecraft.world.level.ChunkPos(0,0));level.getChunk(2,0);
            level.setBlock(new BlockPos(33,99,7),Blocks.SAND.defaultBlockState(),2);
            incoming=net.minecraft.world.entity.item.FallingBlockEntity.fall(level,new BlockPos(33,99,7),Blocks.SAND.defaultBlockState());incoming.setDeltaMovement(new Vec3(-1,0,0));
            double outsideX=incoming.getX();
            incoming.setPos(31.5,incoming.getY(),incoming.getZ());
            require(incoming.getX()==outsideX,"native setPos entered locked boundary");
            incoming.setPosRaw(31.5,incoming.getY(),incoming.getZ());
            require(incoming.getX()==outsideX,"native setPosRaw entered locked boundary");
            incomingProjectile=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(new net.minecraft.resources.ResourceLocation("minecraft:arrow")).create(level);
            incomingProjectile.setPos(33.5,104,7.5);incomingProjectile.setNoGravity(true);
            incomingProjectile.setDeltaMovement(new Vec3(-1,0,0));level.addFreshEntity(incomingProjectile);
            try {before=digest();}catch(Exception error){lock.close();throw error;}start=runtime.server().getTickCount();
        }
        String digest() throws Exception {
            var raw=ChunkCapture.capture(level,level.getChunk(0,0));var entities=new ArrayList<org.worldgit.core.anvil.Nbt.Compound>();
            for(var entity:raw.entities())entities.add(ChunkCapture.toCore(entity));
            var snapshot=new org.worldgit.core.normalize.ChunkNormalizer(org.worldgit.core.config.IgnoreRules.none(),semantics).normalize(new ChunkPos(0,0),ChunkCapture.toCore(raw.chunk()),entities);
            var hash=MessageDigest.getInstance("SHA-256");
            for(var entry:new TreeMap<>(org.worldgit.core.normalize.SnapshotCodec.chunkFiles(snapshot)).entrySet()) {hash.update(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));hash.update(entry.getValue());}
            return HexFormat.of().formatHex(hash.digest());
        }
        void tick() {
            try {
                int elapsed=runtime.server().getTickCount()-start;
                if(!released && elapsed>=8) {require(before.equals(digest()),"locked water/lava/piston/sand/entity/BE changed");require(!incoming.isRemoved() && incoming.getX()>=32 && incoming.getY()<99,"moving nonliving entity entered locked boundary or did not tick outside");require(!incomingProjectile.isRemoved() && incomingProjectile.getX()>=32 && incomingProjectile.tickCount>0,"projectile entered locked boundary or did not tick outside");lock.close();lock=null;released=true;}
                if(released && elapsed>=48) {require(!before.equals(digest()),"scheduled/entity ticks did not resume");require(level.getFluidState(new BlockPos(16,91,2)).is(net.minecraft.tags.FluidTags.WATER) && level.getFluidState(new BlockPos(16,91,12)).is(net.minecraft.tags.FluidTags.LAVA),"boundary fluids did not resume");say(source,"WGPROBE adversary passed held_stable=true resumed=true water=true lava=true piston=true falling_sand=true moving_entity=true block_entity=true");adversary=null;}
            }catch(Throwable e) {try {if(lock!=null)lock.close();}catch(Exception cleanup){e.addSuppressed(cleanup);}source.sendFailure(net.minecraft.network.chat.Component.literal("WGTESTFAIL adversary "+e));e.printStackTrace();adversary=null;}
        }
    }
    private static final class PendingProbe {
        final ServerRuntime runtime;final ServerLevel level;final net.minecraft.commands.CommandSourceStack source;
        final net.minecraft.world.level.chunk.LevelChunk chunk;
        final net.minecraft.world.ticks.LevelChunkTicks<net.minecraft.world.level.block.Block> originalBlock, blocks;
        final net.minecraft.world.ticks.LevelChunkTicks<net.minecraft.world.level.material.Fluid> originalFluid, fluids;
        final AutoCloseable lock;final int start;
        PendingProbe(ServerRuntime runtime,ServerLevel level,net.minecraft.commands.CommandSourceStack source) throws Exception {
            this.runtime=runtime;this.level=level;this.source=source;start=runtime.server().getTickCount();
            chunk=level.getChunk(20,20);originalBlock=(net.minecraft.world.ticks.LevelChunkTicks<net.minecraft.world.level.block.Block>)chunk.getBlockTicks();
            originalFluid=(net.minecraft.world.ticks.LevelChunkTicks<net.minecraft.world.level.material.Fluid>)chunk.getFluidTicks();
            blocks=new net.minecraft.world.ticks.LevelChunkTicks<>(List.of(new net.minecraft.world.ticks.SavedTick<>(Blocks.STONE,new BlockPos(321,90,321),37,net.minecraft.world.ticks.TickPriority.HIGH)));
            fluids=new net.minecraft.world.ticks.LevelChunkTicks<>(List.of(new net.minecraft.world.ticks.SavedTick<>(net.minecraft.world.level.material.Fluids.WATER,new BlockPos(322,90,321),71,net.minecraft.world.ticks.TickPriority.VERY_LOW)));
            require(blocks.getAll().count()==0 && fluids.getAll().count()==0,"fixture ticks must still be pending");
            var access=(org.worldgit.fabric.fixture.mixin.PendingTicksAccess)chunk;access.worldgit$blockTicks(blocks);access.worldgit$fluidTicks(fluids);
            level.getBlockTicks().removeContainer(chunk.getPos());level.getBlockTicks().addContainer(chunk.getPos(),blocks);
            level.getFluidTicks().removeContainer(chunk.getPos());level.getFluidTicks().addContainer(chunk.getPos(),fluids);
            lock=runtime.lockChunks(Map.of(ServerRuntime.dimensionId(level),Set.of(new ChunkPos(20,20))));
            require(blocks.getAll().count()==1 && fluids.getAll().count()==1,"lock did not unpack pending ticks");
        }
        void tick() {
            if(runtime.server().getTickCount()-start<8)return;
            try {
                ChunkTickLocks.beforeSave(level,new ChunkPos(20,20));
                long now=level.getGameTime();
                var block=blocks.save(now,b->net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).toString()).getCompound(0);
                var fluid=fluids.save(now,f->net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(f).toString()).getCompound(0);
                require(block.getInt("t")==37 && block.getInt("p")==net.minecraft.world.ticks.TickPriority.HIGH.getValue(),"pending block delay/priority changed");
                require(fluid.getInt("t")==71 && fluid.getInt("p")==net.minecraft.world.ticks.TickPriority.VERY_LOW.getValue(),"pending fluid delay/priority changed");
                lock.close();
                require(blocks.getAll().findFirst().orElseThrow().triggerTick()-now==37 && fluids.getAll().findFirst().orElseThrow().triggerTick()-now==71,"unlock changed remaining delay");
                say(source,"WGPENDING passed pending=true block_delay=37 fluid_delay=71 priority=true owner_clock=true");
            } catch(Throwable error) {source.sendFailure(net.minecraft.network.chat.Component.literal("WGTESTFAIL pending "+error));try{lock.close();}catch(Exception ignored){}}
            finally {
                var access=(org.worldgit.fabric.fixture.mixin.PendingTicksAccess)chunk;access.worldgit$blockTicks(originalBlock);access.worldgit$fluidTicks(originalFluid);
                level.getBlockTicks().removeContainer(chunk.getPos());level.getBlockTicks().addContainer(chunk.getPos(),originalBlock);
                level.getFluidTicks().removeContainer(chunk.getPos());level.getFluidTicks().addContainer(chunk.getPos(),originalFluid);pendingProbe=null;
            }
        }
    }
    private static void locks(ServerRuntime rt,ServerLevel level) throws Exception {
        var inside=new BlockPos(1,90,1);var outside=new BlockPos(64,90,1);var piston=new BlockPos(32,90,5);var crop=new BlockPos(17,90,8);
        var saved=new HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        for(var pos:List.of(inside,outside,piston,crop))saved.put(pos,level.getBlockState(pos));
        level.setBlock(inside,Blocks.STONE.defaultBlockState(),2);level.setBlock(outside,Blocks.STONE.defaultBlockState(),2);
        level.setBlock(piston,Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING,Direction.WEST),2);
        level.setBlock(crop,Blocks.WHEAT.defaultBlockState(),2);
        var clock=java.util.stream.StreamSupport.stream(rt.server().getAllLevels().spliterator(),false).map(world->world.getGameTime()).toList();
        int serverTick=rt.server().getTickCount();
        try(var lock=rt.lockChunks(Map.of(ServerRuntime.dimensionId(level),Set.of(new ChunkPos(0,0))))) {
            require(rt.server().getTickCount()==serverTick,"native server clock changed within owner callback");
            require(java.util.stream.StreamSupport.stream(rt.server().getAllLevels().spliterator(),false).map(world->world.getGameTime()).toList().equals(clock),"world clocks changed within owner callback");
            require(rt.ticksLocked(ServerRuntime.dimensionId(level),new ChunkPos(0,0)),"target chunk ticks not guarded");
            require(!rt.ticksLocked(ServerRuntime.dimensionId(level),new ChunkPos(4,0)),"distant chunk ticks stopped");
            require(!level.setBlock(inside,Blocks.GOLD_BLOCK.defaultBlockState(),2),"block lock");
            require(level.setBlock(outside,Blocks.GOLD_BLOCK.defaultBlockState(),2),"outside blocked");
            require(!level.getBlockState(piston).triggerEvent(level,piston,0,Direction.WEST.get3DDataValue()),"piston");
            var explosion=new Explosion(level,null,17,90,1,4,false,Explosion.BlockInteraction.DESTROY);explosion.explode();require(explosion.getToBlow().isEmpty(),"explosion");
            var bone=new ItemStack(Items.BONE_MEAL,3);require(!BoneMealItem.growCrop(bone,level,crop) && bone.getCount()==3,"fertilizer");
            rt.server().getCommands().performPrefixedCommand(rt.server().createCommandSourceStack(),"setblock 64 90 1 diamond_block");
            require(level.getBlockState(outside).is(Blocks.GOLD_BLOCK),"console");
        } finally {
            require(rt.server().getTickCount()==serverTick,"server clock state changed");
            require(!rt.ticksLocked(ServerRuntime.dimensionId(level),new ChunkPos(0,0)),"chunk guard not released");
            for(var e:saved.entrySet())level.setBlock(e.getKey(),e.getValue(),2);
        }
    }
    /** Verify persistence before shutdown can hide a missed empty-entity write. */
    private static void storageProbe(ServerRuntime rt,ServerLevel level,net.minecraft.commands.CommandSourceStack source) {
        rt.runRepo(()->{
            UUID id=rt.onServer(()->{
                var pos=new net.minecraft.world.level.ChunkPos(64,0);
                level.getChunkSource().addRegionTicket(PROBE_TICKET,pos,2,pos);
                level.getChunk(64,0);
                level.setBlock(new BlockPos(1034,94,8),Blocks.DIAMOND_BLOCK.defaultBlockState(),2);
                level.setBlock(new BlockPos(1036,94,8),Blocks.LECTERN.defaultBlockState(),2);
                var entity=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(new net.minecraft.resources.ResourceLocation("minecraft:armor_stand")).create(level);
                entity.setPos(1032,94,8);entity.setNoGravity(true);
                require(level.addFreshEntity(entity),"storage fixture spawn");return entity.getUUID();
            });
            try {
                rt.flushBlocking();require(storedEntity(rt,level,id),"entity was not persisted before removal");
                require(storedTerrainAndPoi(rt,level),"terrain/POI was not persisted before shutdown");
                rt.onServer(()->{var entity=storageFixtureEntity(level,id);require(entity!=null,"storage fixture vanished");entity.discard();return null;});
                rt.flushBlocking();require(!storedEntity(rt,level,id),"last removed entity remains on disk");
                rt.onServer(()->{say(source,"WGPROBE storage passed persisted=true last_entity_removed=true terrain=true poi=true");return null;});
            } finally {rt.onServer(()->{
                var entity=storageFixtureEntity(level,id);if(entity!=null)entity.discard();
                var pos=new net.minecraft.world.level.ChunkPos(64,0);level.getChunkSource().removeRegionTicket(PROBE_TICKET,pos,2,pos);
                return null;
            });}
            return null;
        }).whenComplete((result,error)->{if(error!=null)rt.postToServer(()->source.sendFailure(net.minecraft.network.chat.Component.literal("WGTESTFAIL storage "+error)));});
    }
    private static boolean storedTerrainAndPoi(ServerRuntime rt,ServerLevel level) throws Exception {
        var dimension=org.worldgit.core.anvil.WorldLayout.discover(rt.worldRoot()).dimensions().get(ServerRuntime.dimensionId(level));
        var pos=new ChunkPos(64,0);boolean terrain=false,poi=false;
        try(var region=new org.worldgit.core.anvil.RegionFile(dimension.region().resolve(pos.regionName()+".mca"))) {
            for(var value:region.read(pos.regionIndex()).list("sections").values()) {
                var section=(org.worldgit.core.anvil.Nbt.Compound)value;
                var names=new java.util.HashSet<String>();
                for(var entry:section.compound("block_states").list("palette").values()) names.add(((org.worldgit.core.anvil.Nbt.Compound)entry).string("Name"));
                if(names.contains("minecraft:diamond_block") && names.contains("minecraft:lectern"))terrain=true;
            }
        }
        try(var region=new org.worldgit.core.anvil.RegionFile(dimension.poi().resolve(pos.regionName()+".mca"))) {
            for(var value:region.read(pos.regionIndex()).compound("Sections").values())
                for(var entry:((org.worldgit.core.anvil.Nbt.Compound)value).list("Records").values()) {
                    var record=(org.worldgit.core.anvil.Nbt.Compound)entry;
                    if(record.string("type").equals("minecraft:librarian") && record.get("pos") instanceof int[] at
                        && java.util.Arrays.equals(at,new int[]{1036,94,8}))poi=true;
                }
        }
        return terrain && poi;
    }
    private static net.minecraft.world.entity.Entity storageFixtureEntity(ServerLevel level,UUID id) {
        var manager=(org.worldgit.fabric.mixin.EntityManagerAccess<net.minecraft.world.entity.Entity>)(Object)((org.worldgit.fabric.mixin.ServerLevelAccess)level).worldgit$entities();
        return manager.worldgit$sections().getExistingSectionsInChunk(net.minecraft.world.level.ChunkPos.asLong(64,0))
            .flatMap(section->section.getEntities()).filter(entity->entity.getUUID().equals(id)).findFirst().orElse(null);
    }
    private static boolean storedEntity(ServerRuntime rt,ServerLevel level,UUID id) throws Exception {
        var dimension=org.worldgit.core.anvil.WorldLayout.discover(rt.worldRoot()).dimensions().get(ServerRuntime.dimensionId(level));
        var pos=new ChunkPos(64,0);var path=dimension.entities().resolve(pos.regionName()+".mca");
        if(!java.nio.file.Files.exists(path))return false;
        try(var region=new org.worldgit.core.anvil.RegionFile(path)) {
            if(!region.has(pos.regionIndex()))return false;
            for(var value:region.read(pos.regionIndex()).list("Entities").values()) {
                var entity=(org.worldgit.core.anvil.Nbt.Compound)value;
                if(entity.get("UUID") instanceof int[] a && a.length==4 && id.equals(new UUID(((long)a[0]<<32)|(a[1]&0xffffffffL),((long)a[2]<<32)|(a[3]&0xffffffffL))))return true;
            }
        }
        return false;
    }
    private static void require(boolean ok,String reason) {if(!ok)throw new AssertionError(reason);}
    private static void say(net.minecraft.commands.CommandSourceStack source,String text) {source.sendSuccess(()->net.minecraft.network.chat.Component.literal(text),false);}
}
