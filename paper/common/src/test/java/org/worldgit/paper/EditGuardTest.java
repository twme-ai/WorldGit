package org.worldgit.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.Test;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.platform.PlayerProtection;

class EditGuardTest {
  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type,Map<String,Object> values) {
    return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->values.get(m.getName()));
  }
  private static Player player(World world,int x) {
    return proxy(Player.class,Map.of("getUniqueId",UUID.randomUUID(),"getWorld",world,"getLocation",new Location(world,x,65,0)));
  }
  private static Block block(World world,int x) {
    return (Block)Proxy.newProxyInstance(Block.class.getClassLoader(),new Class<?>[]{Block.class},(p,m,a)->switch(m.getName()) {
      case "getWorld"->world;
      case "getLocation"->new Location(world,x,65,0);
      case "getRelative"->block(world,x+((BlockFace)a[0]).getModX());
      default->null;
    });
  }
  @Test void chunkLockChecksInteractionTargetAcrossBoundaryAndAllowsOutsideEdits() throws Exception {
    var guard=new EditGuard(null);var world=proxy(World.class,Map.of("getUID",UUID.randomUUID()));
    var player=player(world,15);
    try(var lock=guard.lock(world,Set.of(new ChunkPos(1,0)))) {
      var inside=new PlayerInteractEvent(player,Action.RIGHT_CLICK_BLOCK,null,block(world,16),BlockFace.WEST);
      guard.interact(inside);assertTrue(inside.isCancelled());
      var placement=new PlayerInteractEvent(player,Action.RIGHT_CLICK_BLOCK,null,block(world,15),BlockFace.EAST);
      guard.interact(placement);assertTrue(placement.isCancelled());
      var outside=new PlayerInteractEvent(player,Action.RIGHT_CLICK_BLOCK,null,block(world,12),BlockFace.WEST);
      guard.interact(outside);assertFalse(outside.isCancelled());
      var breaking=new BlockBreakEvent(block(world,15),player);
      guard.breaking(breaking);assertFalse(breaking.isCancelled());
    }
  }
  @Test void chunkLockChecksPistonDestinationsAndBatchMutationFootprints() throws Exception {
    var guard=new EditGuard(null);var world=proxy(World.class,Map.of("getUID",UUID.randomUUID()));
    try(var lock=guard.lock(world,Set.of(new ChunkPos(1,0)))) {
      var piston=new BlockPistonExtendEvent(block(world,14),List.of(block(world,15)),BlockFace.EAST);
      guard.piston(piston);assertTrue(piston.isCancelled());
      var outside=new BlockPistonExtendEvent(block(world,10),List.of(block(world,11)),BlockFace.EAST);
      guard.piston(outside);assertFalse(outside.isCancelled());
      var retract=new BlockPistonRetractEvent(block(world,15),List.of(),BlockFace.WEST);
      guard.piston(retract);assertTrue(retract.isCancelled());
      var explosion=new BlockExplodeEvent(block(world,14),null,List.of(block(world,16)),1,ExplosionResult.DESTROY);
      guard.explode(explosion);assertTrue(explosion.isCancelled());
      var state=proxy(BlockState.class,Map.of("getBlock",block(world,16)));
      var fertilize=new BlockFertilizeEvent(block(world,14),null,List.of(state));
      guard.fertilize(fertilize);assertTrue(fertilize.isCancelled());
    }
  }
  @Test void oneOperationProtectsEveryDimensionIncludingPlayersEnteringLater() {
    var guard=new EditGuard(null);
    var a=proxy(World.class,Map.of("getUID",UUID.randomUUID()));
    var b=proxy(World.class,Map.of("getUID",UUID.randomUUID()));
    var id=UUID.randomUUID(); var chunks=Set.of(new ChunkPos(0,0));
    guard.protect(a,PlayerProtection.operation(chunks,id,true));
    guard.protect(b,PlayerProtection.operation(chunks,id,true));
    var pa=player(a,0); var pb=player(b,0); var outside=player(a,32);
    for(var damage:PlayerProtection.Damage.values()) {
      assertTrue(guard.protectedFrom(pa,damage));
      assertTrue(guard.protectedFrom(pb,damage));
      assertFalse(guard.protectedFrom(outside,damage));
    }
    guard.protect(a,new PlayerProtection(chunks,Duration.ZERO,Set.of(PlayerProtection.Damage.FALL),id,false));
    assertFalse(guard.protectedFrom(pa,PlayerProtection.Damage.FALL));
    assertTrue(guard.protectedFrom(pb,PlayerProtection.Damage.FALL));
  }
  @Test void everyListenerHasAConcreteBukkitHandlerList() throws Exception {
    for(var method:EditGuard.class.getDeclaredMethods()) if(method.isAnnotationPresent(EventHandler.class)) {
      var event=method.getParameterTypes()[0];
      assertTrue(Modifier.isStatic(event.getMethod("getHandlerList").getModifiers()),event.getName());
    }
  }
}
