package org.worldgit.fixture;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;

/** 測試專用：讓真正 SetBlockBufferOperation 等未完成 future，驗證套用屏障取消既有操作。 */
public final class AxiomFixture extends JavaPlugin {
  private static Field field(Object object, String name) throws Exception {
    var field = object.getClass().getDeclaredField(name);field.setAccessible(true);return field;
  }
  @SuppressWarnings("unchecked")
  public void onEnable() {
    getCommand("axiomfixture").setExecutor((sender, command, label, args) -> {
      try {
        var axiom = Bukkit.getPluginManager().getPlugin("AxiomPaper");
        var player = Bukkit.getPlayerExact("WgBot");
        if (args[0].equals("bypass")) {
          player.addAttachment(this,"axiomadmin.bypass_region_checks",Boolean.parseBoolean(args[1]));
          sender.sendMessage("AXIOM_BYPASS="+player.hasPermission("axiomadmin.bypass_region_checks"));return true;
        }
        Object queue = field(axiom,"operationQueue").get(axiom);
        var maps = List.of((Map<Object,List<Object>>)field(queue,"newPendingOperations").get(queue),
            (Map<Object,List<Object>>)field(queue,"pendingOperations").get(queue));
        if (args[0].equals("seed")) {
          Object handle = player.getClass().getMethod("getHandle").invoke(player);
          Object world = handle.getClass().getMethod("level").invoke(handle);
          Object registry = axiom.getClass().getMethod("getBlockRegistry",UUID.class).invoke(axiom,player.getUniqueId());
          var loader = axiom.getClass().getClassLoader();
          var bufferClass = Class.forName("com.moulberry.axiom.buffer.BlockBuffer",true,loader);
          var ctor = Arrays.stream(bufferClass.getConstructors()).filter(c->c.getParameterCount()==1).findFirst().orElseThrow();
          Object buffer = ctor.newInstance(registry);
          var opClass = Class.forName("com.moulberry.axiom.operations.SetBlockBufferOperation",true,loader);
          Object operation = Arrays.stream(opClass.getConstructors()).filter(c->c.getParameterCount()==3).findFirst().orElseThrow().newInstance(handle,buffer,true);
          Field sections = field(operation,"sectionsForChunks");sections.set(operation,sections.getType().getConstructor().newInstance());
          Field futures = field(operation,"getChunkFutures");futures.set(operation,futures.getType().getConstructor().newInstance());
          ((List<CompletableFuture<?>>)field(operation,"chunkFutures").get(operation)).add(new CompletableFuture<>());
          maps.getFirst().computeIfAbsent(world,k->new ArrayList<>()).add(operation);
          sender.sendMessage("AXIOM_QUEUED=1");
        } else {
          int count=0;for(var map:maps)for(var list:map.values())for(var op:list)if(op.getClass().getSimpleName().equals("SetBlockBufferOperation"))count++;
          sender.sendMessage("AXIOM_QUEUED="+count);
        }
      } catch(Exception error) {error.printStackTrace();sender.sendMessage("AXIOM_FIXTURE_ERROR="+error);}
      return true;
    });
  }
}
