package org.worldgit.paper;

import com.moulberry.axiom.event.*;
import com.moulberry.axiom.integration.Integration;
import com.moulberry.axiom.integration.SectionPermissionChecker;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.locks.Lock;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;

/** 選用 API；只在 Paper 且 AxiomPaper 已啟用時載入。決定 #142。 */
final class AxiomHook implements Listener, Integration.CustomIntegration {
  private final WorldGitPlugin plugin;
  private final Object queue;
  private final Lock executionLock, queueLock;
  private final List<Map<?, ?>> pending;
  private final Map<UUID, Long> notices = new HashMap<>();
  private static final StackWalker CALLERS = StackWalker.getInstance();
  private static final Set<String> WRITE_CALLERS = Set.of(
      "com.moulberry.axiom.packet.impl.SetBlockPacketListener",
      "com.moulberry.axiom.packet.impl.SetBlockBufferPacketListener",
      "com.moulberry.axiom.operations.SetBlockBufferOperation");

  private AxiomHook(WorldGitPlugin plugin, org.bukkit.plugin.Plugin axiom) throws ReflectiveOperationException {
    this.plugin = plugin;
    queue = field(axiom, "operationQueue");
    executionLock = (Lock) field(queue, "executionLock");
    queueLock = (Lock) field(queue, "queueLock");
    pending = List.of((Map<?, ?>) field(queue, "pendingOperations"), (Map<?, ?>) field(queue, "newPendingOperations"));
  }

  static void install(WorldGitPlugin plugin, org.bukkit.plugin.Plugin axiom) throws ReflectiveOperationException {
    var hook = new AxiomHook(plugin, axiom);
    hook.installPacketGuards(axiom);
    Integration.registerCustomIntegration(plugin, hook);
    plugin.getServer().getPluginManager().registerEvents(hook, plugin);
    plugin.axiomBarrier(hook::quiesce);
  }

  private static Object field(Object object, String name) throws ReflectiveOperationException {
    Field field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(object);
  }

  /** tick_blocks 沒有 modify 事件；set_block 的拒絕也必須確認原版預測序號。直送與 tunnel 共用代理。 */
  @SuppressWarnings("unchecked")
  private void installPacketGuards(org.bukkit.plugin.Plugin axiom) throws ReflectiveOperationException {
    var packets = (Map<Object, Object>) field(axiom, "supportedServerboundPackets");
    Class<?> api = Class.forName("com.moulberry.axiom.packet.PacketHandler", false, axiom.getClass().getClassLoader());
    for (String channel : List.of("axiom:tick_blocks", "axiom:set_block")) {
      var entry = packets.entrySet().stream().filter(e -> e.getKey().toString().equals(channel)).findFirst().orElseThrow();
      Object delegate = entry.getValue();
      Object guarded = Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, (proxy, method, args) -> {
        if (method.getName().equals("onReceive")) {
          Player player = (Player) args[0];
          if (!allowed(player, player.getWorld())) {
            if (channel.equals("axiom:set_block")) acknowledge(player, args[1]);
            return null;
          }
        }
        try { return method.invoke(delegate, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
      });
      int registrations = 0;
      for (var registration : Bukkit.getMessenger().getIncomingChannelRegistrations(axiom)) {
        if (!registration.getChannel().equals(channel)) continue;
        Object listener = registration.getListener();
        Field handler = listener.getClass().getDeclaredField("packetHandler");
        handler.setAccessible(true);
        if (handler.get(listener) != delegate) throw new IllegalStateException("Unexpected Axiom packet registration");
        handler.set(listener, guarded);
        registrations++;
      }
      if (registrations != 1) throw new IllegalStateException("Missing Axiom packet registration: " + channel);
      entry.setValue(guarded);
    }
  }

  private static Object call(Object object, String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
    return object.getClass().getMethod(name, types).invoke(object, args);
  }
  private static int integer(Object buffer) throws ReflectiveOperationException {
    return (Integer) call(buffer, "readVarInt", new Class<?>[]{});
  }
  private static void skip(Object buffer, int count) throws ReflectiveOperationException {
    call(buffer, "skipBytes", new Class<?>[]{int.class}, count);
  }
  private static int count(Object buffer) throws ReflectiveOperationException {
    int size = integer(buffer);
    if (size < 0 || size > 262144) throw new IllegalArgumentException("Axiom packet count");
    return size;
  }
  private void acknowledge(Player player, Object buffer) {
    try {
      int count = count(buffer);
      for (int i=0; i<count; i++) { skip(buffer,8); integer(buffer); }
      if ((Boolean) call(buffer,"readBoolean",new Class<?>[]{})) skip(buffer,Math.multiplyExact(count(buffer),8));
      integer(buffer); // reason
      skip(buffer,1+8+1+12+2); // breaking + hit result
      integer(buffer); // hand
      int sequence = integer(buffer);
      if (sequence < 0) return;
      Object handle = call(player,"getHandle",new Class<?>[]{});
      Object connection = handle.getClass().getField("connection").get(handle);
      call(connection,"ackBlockChangesUpTo",new Class<?>[]{int.class},sequence);
    } catch (ReflectiveOperationException | IllegalArgumentException malformed) {
      plugin.getLogger().warning("Axiom refused packet acknowledgement failed: " + malformed.getClass().getSimpleName());
    }
  }

  /** 套用前在 server owner 清掉既有 buffer；否則 region bypass 可繞過 section 檢查。 */
  private void quiesce(World world) {
    if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Axiom barrier requires server owner");
    executionLock.lock();
    queueLock.lock();
    try {
      for (var map : pending) for (var entry : map.entrySet()) {
        World target = (World) entry.getKey().getClass().getMethod("getWorld").invoke(entry.getKey());
        if (!target.getUID().equals(world.getUID())) continue;
        var operations = (List<?>) entry.getValue();
        var iterator = operations.iterator();
        while (iterator.hasNext()) {
          Object operation = iterator.next();
          if (!operation.getClass().getName().equals("com.moulberry.axiom.operations.SetBlockBufferOperation")) continue;
          Object executor = operation.getClass().getMethod("executor").invoke(operation);
          Player player = (Player) executor.getClass().getMethod("getBukkitEntity").invoke(executor);
          iterator.remove();
          denied(player, world);
        }
      }
    } catch (ReflectiveOperationException | ClassCastException error) {
      throw new IllegalStateException("Cannot quiesce AxiomPaper operations; aborting WorldGit apply", error);
    } finally {
      queueLock.unlock();
      executionLock.unlock();
    }
  }

  private void denied(Player player, World world) {
    long now = System.nanoTime();
    Long previous = notices.get(player.getUniqueId());
    if (previous != null && now - previous < 1_000_000_000L) return;
    notices.put(player.getUniqueId(), now);
    Messages.inLocale(player, () -> player.sendMessage(Messages.line("common.axiom.locked")));
    // Axiom may optimistically render edits. Resend currently watched chunks without loading any.
    int radius = Math.min(16, Math.max(2, player.getClientViewDistance()));
    int x = player.getLocation().getBlockX() >> 4, z = player.getLocation().getBlockZ() >> 4;
    if (player.getWorld().equals(world)) for (int cx = x-radius; cx <= x+radius; cx++)
      for (int cz = z-radius; cz <= z+radius; cz++) if (world.isChunkLoaded(cx, cz)) world.refreshChunk(cx, cz);
  }

  private boolean allowed(Player player, World world) {
    if (!plugin.isEnabled() || !plugin.isEditLocked(world)) return true;
    denied(player, world);
    return false;
  }

  private boolean coordinate(Player player, World world, int x, int z) {
    if (!allowed(player, world)) return false;
    // The same permission API serves request_entity_data and /axiomdebug reads.
    // Match the verified synchronous writer frame, including scheduled biome lambdas.
    if (plugin.isEnabled() && CALLERS.walk(frames -> frames.limit(16).anyMatch(frame -> WRITE_CALLERS.contains(frame.getClassName()))))
      plugin.touch(world, x, z, player, "axiom");
    return true;
  }

  @Override public boolean canBreakBlock(Player player, org.bukkit.block.Block block) {
    return coordinate(player, block.getWorld(), block.getX() >> 4, block.getZ() >> 4);
  }
  @Override public boolean canPlaceBlock(Player player, Location location) {
    return coordinate(player, location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
  }
  @Override public SectionPermissionChecker checkSection(Player player, World world, int x, int y, int z) {
    return coordinate(player, world, x, z) ? SectionPermissionChecker.ALL_ALLOWED : SectionPermissionChecker.NONE_ALLOWED;
  }

  @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
  public void modify(AxiomModifyWorldEvent event) {
    if (!allowed(event.getPlayer(), event.getWorld())) event.setCancelled(true);
  }
  @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
  public void spawnGuard(AxiomSpawnEntityEvent event) {
    if (!allowed(event.getPlayer(), event.getEntity().getWorld())) event.setCancelled(true);
  }
  @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
  public void manipulateGuard(AxiomManipulateEntityEvent event) {
    if (!allowed(event.getPlayer(), event.getEntity().getWorld())) event.setCancelled(true);
  }
  @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
  public void removeGuard(AxiomRemoveEntityEvent event) {
    if (!allowed(event.getPlayer(), event.getEntity().getWorld())) event.setCancelled(true);
  }
  private void touched(Player player, Entity entity) {
    if (entity.isValid() && !entity.isDead()) {
      plugin.touched().touch(entity);
      var location = entity.getLocation();
      plugin.touch(entity.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4, player, "axiom");
    }
  }
  @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
  public void spawned(AxiomSpawnEntityEvent event) { touched(event.getPlayer(), event.getEntity()); }
  @EventHandler(priority=EventPriority.MONITOR)
  public void manipulated(AxiomAfterManipulateEntityEvent event) { touched(event.getPlayer(), event.getEntity()); }
  @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
  public void removed(AxiomRemoveEntityEvent event) {
    var loc = event.getEntity().getLocation();
    plugin.touch(loc.getWorld(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4, event.getPlayer(), "axiom");
    // Do not add a formerly untracked UUID. Complete commit prunes removed touched entities.
  }
  @EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent event) { notices.remove(event.getPlayer().getUniqueId()); }
}
