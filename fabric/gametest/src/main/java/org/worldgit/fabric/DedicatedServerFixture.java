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
import net.minecraft.world.level.ServerExplosion;
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
    @Override public void onInitialize() {
        if(!Boolean.getBoolean("worldgit.acceptance")) return;
        ServerTickEvents.END_SERVER_TICK.register(server->ticks.add(System.nanoTime()));
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
                        case "sample" -> {
                            int cx=Integer.parseInt(args[1]),cz=Integer.parseInt(args[2]),sy=Integer.parseInt(args[3]);
                            var hash=MessageDigest.getInstance("SHA-256");
                            for(int i=0;i<4096;i++) hash.update((BlockStateParser.serialize(level.getBlockState(new BlockPos(cx*16+(i&15),sy*16+(i>>8),cz*16+((i>>4)&15))))+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            say(source,"WGSAMPLE hash="+HexFormat.of().formatHex(hash.digest()));
                        }
                        case "fill" -> {
                            int side=Integer.parseInt(args[1]),count=Integer.parseInt(args[3]),local=Integer.parseInt(args[4]);
                            var block=Blocks.AIR.defaultBlockState();
                            block=BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),args[2],false).blockState();
                            for(int i=0;i<count;i++) {
                                int x=i%side-side/2,z=i/side-side/2; var chunk=new ChunkPos(x,z);tickets.add(chunk);
                                level.getChunkSource().addTicketWithRadius(TICKET,new net.minecraft.world.level.ChunkPos(x,z),0);
                                level.setBlock(new BlockPos(x*16+local,64,z*16+10),block,2);
                            }
                            say(source,"WGFILL done count="+count);
                        }
                        case "release" -> {for(var pos:tickets) level.getChunkSource().removeTicketWithRadius(TICKET,new net.minecraft.world.level.ChunkPos(pos.x(),pos.z()),0);tickets.clear();say(source,"WGRELEASE done");}
                        case "tick-reset" -> {ticks.clear();say(source,"WGTICKS reset");}
                        case "ticks" -> {
                            var values=new ArrayList<Double>();for(int i=1;i<ticks.size();i++)values.add((ticks.get(i)-ticks.get(i-1))/1e6);
                            double total=values.stream().mapToDouble(Double::doubleValue).sum();Collections.sort(values);
                            say(source,"WGTICKS count="+values.size()+" tps="+(values.size()*1000/total)+" p99="+values.get(Math.min(values.size()-1,(int)(values.size()*.99)))+" max="+values.getLast());
                        }
                        case "locks" -> {locks(rt,level);say(source,"WGLOCKS block=true outside=true piston=true explosion=true fertilizer=true console=true");}
                        case "natural-cow" -> {
                            // 與玩家動作無關的自然出生（不在 TouchScope 內）：不得進入 player-touched 集合。
                            var cow=(net.minecraft.world.entity.Mob)net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.parse("minecraft:cow")).create(level,net.minecraft.world.entity.EntitySpawnReason.NATURAL);
                            cow.setPos(Double.parseDouble(args[1]),Double.parseDouble(args[2]),Double.parseDouble(args[3]));
                            cow.setNoAi(true);cow.setNoGravity(true);level.addFreshEntity(cow);
                            say(source,"WGCOW uuid="+cow.getUUID());
                        }
                        case "entities" -> {
                            var rows=new ArrayList<String>();
                            for(var entity:level.getAllEntities()) if(!(entity instanceof net.minecraft.world.entity.player.Player)) rows.add(entity.getType().builtInRegistryHolder().key().identifier()+":"+entity.getUUID());
                            Collections.sort(rows);say(source,"WGENTITIES "+level.dimension().identifier()+" "+rows);
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
                                float health=player.getHealth();player.hurtServer(level,damage,3);require(player.getHealth()==health,"protected damage");
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
    private static final net.minecraft.server.level.TicketType TICKET=new net.minecraft.server.level.TicketType(0,net.minecraft.server.level.TicketType.FLAG_LOADING);
    private static void locks(ServerRuntime rt,ServerLevel level) throws Exception {
        var inside=new BlockPos(1,90,1);var outside=new BlockPos(17,90,1);var piston=new BlockPos(15,90,5);var crop=new BlockPos(17,90,8);
        var saved=new HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        for(var pos:List.of(inside,outside,piston,crop))saved.put(pos,level.getBlockState(pos));
        level.setBlock(inside,Blocks.STONE.defaultBlockState(),2);level.setBlock(outside,Blocks.STONE.defaultBlockState(),2);
        level.setBlock(piston,Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING,Direction.WEST),2);
        level.setBlock(crop,Blocks.WHEAT.defaultBlockState(),2);
        var ticks=rt.server().tickRateManager();
        boolean frozen=ticks.isFrozen();int steps=ticks.frozenTicksToRun();
        ticks.setFrozen(true);ticks.setFrozenTicksToRun(5);
        try(var lock=rt.lockChunks(Map.of(ServerRuntime.dimensionId(level),Set.of(new ChunkPos(0,0))))) {
            require(ticks.frozenTicksToRun()==0,"stepping while locked");
            require(!level.setBlock(inside,Blocks.GOLD_BLOCK.defaultBlockState(),2),"block lock");
            require(level.setBlock(outside,Blocks.GOLD_BLOCK.defaultBlockState(),2),"outside blocked");
            require(!level.getBlockState(piston).triggerEvent(level,piston,0,Direction.WEST.get3DDataValue()),"piston");
            require(new ServerExplosion(level,null,null,null,new Vec3(17,90,1),4,false,Explosion.BlockInteraction.DESTROY).explode()==0,"explosion");
            var bone=new ItemStack(Items.BONE_MEAL,3);require(!BoneMealItem.growCrop(bone,level,crop) && bone.getCount()==3,"fertilizer");
            rt.server().getCommands().performPrefixedCommand(rt.server().createCommandSourceStack(),"setblock 17 90 1 diamond_block");
            require(level.getBlockState(outside).is(Blocks.GOLD_BLOCK),"console");
        } finally {
            require(ticks.isFrozen() && ticks.frozenTicksToRun()==5,"freeze/step not restored");
            ticks.setFrozen(frozen);ticks.setFrozenTicksToRun(steps);
            for(var e:saved.entrySet())level.setBlock(e.getKey(),e.getValue(),2);
        }
    }
    private static void require(boolean ok,String reason) {if(!ok)throw new AssertionError(reason);}
    private static void say(net.minecraft.commands.CommandSourceStack source,String text) {source.sendSuccess(()->net.minecraft.network.chat.Component.literal(text),false);}
}
