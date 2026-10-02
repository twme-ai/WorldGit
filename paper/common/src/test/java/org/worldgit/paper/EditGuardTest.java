package org.worldgit.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
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
