package org.worldgit.paper;

import java.util.function.BiConsumer;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.*;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.StructureGrowEvent;
import org.worldgit.core.model.ChunkPos;

/**
 * Bukkit 事件 → dirty 標記與作者歸屬。事件只是「速度與作者」的來源，不影響正確性（commit 以內容雜湊為準）；
 * 抓不到的來源（FAWE 直寫、命令方塊、其他插件的 NMS 寫入）由 unsaved 旗標普查補上，歸屬記為未知。
 *
 * <p>全部在事件所屬的 region 執行緒上呼叫，只做並行安全的標記。
 */
final class ChangeListener implements Listener {
  private final WorldGitPlugin plugin;

  ChangeListener(WorldGitPlugin plugin) {
    this.plugin = plugin;
  }

  private void touch(Location location, Player player, String cause) {
    if (location == null || location.getWorld() == null) return;
    plugin.touch(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4, player, cause);
  }

  private void touch(Block block, Player player, String cause) {
    touch(block.getLocation(), player, cause);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPlace(BlockPlaceEvent e) {
    touch(e.getBlockPlaced(), e.getPlayer(), "block-place");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBreak(BlockBreakEvent e) {
    touch(e.getBlock(), e.getPlayer(), "block-break");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBucketEmpty(PlayerBucketEmptyEvent e) {
    touch(e.getBlock(), e.getPlayer(), "bucket");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBucketFill(PlayerBucketFillEvent e) {
    touch(e.getBlock(), e.getPlayer(), "bucket");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onSign(org.bukkit.event.block.SignChangeEvent e) {
    touch(e.getBlock(), e.getPlayer(), "sign");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onStructureGrow(StructureGrowEvent e) {
    Player player = e.getPlayer();
    e.getBlocks().forEach(state -> touch(state.getLocation(), player, "structure-grow"));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onContainerClose(InventoryCloseEvent e) {
    if (e.getPlayer() instanceof Player player && e.getInventory().getLocation() != null)
      touch(e.getInventory().getLocation(), player, "container");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onEntityExplode(EntityExplodeEvent e) {
    Player owner = e.getEntity() instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p ? p : null;
    e.blockList().forEach(b -> touch(b, owner, "explosion"));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBlockExplode(BlockExplodeEvent e) {
    e.blockList().forEach(b -> touch(b, null, "explosion"));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPistonExtend(BlockPistonExtendEvent e) {
    pistonTouch(e.getBlocks(), e.getDirection());
    touch(e.getBlock(), null, "piston");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPistonRetract(BlockPistonRetractEvent e) {
    pistonTouch(e.getBlocks(), e.getDirection());
    touch(e.getBlock(), null, "piston");
  }

  private void pistonTouch(java.util.List<Block> blocks, BlockFace direction) {
    for (Block b : blocks) {
      touch(b, null, "piston");
      touch(b.getRelative(direction), null, "piston");
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onEntityChangeBlock(EntityChangeBlockEvent e) {
    touch(e.getBlock(), e.getEntity() instanceof Player p ? p : null, "entity-block");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onHangingPlace(HangingPlaceEvent e) {
    touch(e.getEntity().getLocation(), e.getPlayer(), "hanging");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onHangingBreak(HangingBreakByEntityEvent e) {
    touch(e.getEntity().getLocation(), e.getRemover() instanceof Player p ? p : null, "hanging");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onArmorStand(PlayerArmorStandManipulateEvent e) {
    touch(e.getRightClicked().getLocation(), e.getPlayer(), "entity");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBurn(BlockBurnEvent e) {
    touch(e.getBlock(), null, "fire");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onLeavesDecay(LeavesDecayEvent e) {
    touch(e.getBlock(), null, "decay");
  }
}
