package org.worldgit.paper;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WorldEdit／FAWE 的 EditSessionEvent：包一層 Extent，記錄「誰」改了「哪些 chunk」（作者歸屬 + dirty 標記）。
 * 此類別只在偵測到 WorldEdit 或 FAWE 時才載入（plugin 不直接引用它的型別）。
 *
 * <p>Phase 0 的結論（docs/04 §2）：純 WorldEdit 逐格都看得到；FAWE 預設會丟掉第三方 Extent（需設 extent.allowed-plugins），
 * 且 {@code //set}、{@code //replace} 只有 bulk 方法，要靠 IBatchProcessor 取得每個 chunk 的實際寫入。偵測碼因此分兩條路，
 * FAWE 專用類別 {@link FaweHook} 只在 FAWE 存在時才會被載入。
 */
final class WorldEditHook {
  private WorldEditHook() {}
  static org.worldgit.core.apply.Scope selection(org.bukkit.entity.Player player) {
    try {
      var session=WorldEdit.getInstance().getSessionManager().findByName(player.getName());
      if(session==null || session.getSelectionWorld()==null || !session.getSelectionWorld().getName().equals(player.getWorld().getName())) throw new IllegalArgumentException("請先在目前世界建立 WorldEdit cuboid 選取");
      var region=session.getSelection(session.getSelectionWorld());
      if(!(region instanceof com.sk89q.worldedit.regions.CuboidRegion)) throw new IllegalArgumentException("restore --selection 目前只接受 cuboid 選取");
      var a=region.getMinimumPoint(); var b=region.getMaximumPoint();
      return org.worldgit.core.apply.Scope.box(a.x(),a.y(),a.z(),b.x(),b.y(),b.z());
    } catch(com.sk89q.worldedit.IncompleteRegionException ex) { throw new IllegalArgumentException("WorldEdit 選取尚未完成",ex); }
      catch(NoClassDefFoundError ex) { throw new IllegalArgumentException("restore --selection 需要 WorldEdit／FAWE",ex); }
  }

  static void install(WorldGitPlugin plugin, boolean fawe) {
    WorldEdit.getInstance().getEventBus().register(new Listener(plugin, fawe));
  }

  /** 一次編輯的歸屬與 chunk 去重。 */
  static final class Sess {
    private final WorldGitPlugin plugin;
    private final String world;
    private final UUID player;
    private final String name;
    private final Set<Long> seen = ConcurrentHashMap.newKeySet();

    Sess(WorldGitPlugin plugin, String world, UUID player, String name) {
      this.plugin = plugin;
      this.world = world;
      this.player = player;
      this.name = name;
    }

    void blockChunk(int chunkX, int chunkZ) {
      if(plugin.edits().locked(world,chunkX,chunkZ)) throw new IllegalStateException("WorldGit 正在套用；WorldEdit 寫入被鎖定");
      if (!seen.add(((long) chunkX << 32) ^ (chunkZ & 0xffffffffL))) return;
      plugin.touchByName(world, chunkX, chunkZ, player, name, "worldedit");
      // 標記發生在實際寫入之前；稍後補標一次，避免 commit 剛好夾在中間而錯過寫入（旗標普查是第二道保險）。
      plugin.platform().asyncDelayed(3, () -> plugin.touchByName(world, chunkX, chunkZ, null, null, "worldedit"));
    }

    void entity(com.sk89q.worldedit.entity.Entity entity,com.sk89q.worldedit.util.Location location) {
      if(entity==null)return;
      org.bukkit.World target=org.bukkit.Bukkit.getWorld(world);if(target==null)return;
      UUID id=entity.getState().getUUID();
      plugin.platform().region(target,location.getBlockX()>>4,location.getBlockZ()>>4,()->{
        for(var live:target.getChunkAt(location.getBlockX()>>4,location.getBlockZ()>>4).getEntities())if(live.getUniqueId().equals(id))plugin.touched().touch(live);
      });
    }
    void entityIds(java.util.Set<UUID> ids,int x,int z) {
      if(ids.isEmpty())return;var target=org.bukkit.Bukkit.getWorld(world);if(target==null)return;
      plugin.platform().regionDelayed(target,x,z,1,()->{
        for(var live:target.getChunkAt(x,z).getEntities())if(ids.contains(live.getUniqueId()))plugin.touched().touch(live);
      });
    }

    void block(int x, int z) {
      blockChunk(x >> 4, z >> 4);
    }

    void region(Region region) {
      BlockVector3 a = region.getMinimumPoint(), b = region.getMaximumPoint();
      long chunks = (long) ((b.x() >> 4) - (a.x() >> 4) + 1) * ((b.z() >> 4) - (a.z() >> 4) + 1);
      if (chunks > 1_000_000) return; // 異常大的範圍交給旗標普查，避免在編輯執行緒上迴圈
      for (int cx = a.x() >> 4; cx <= b.x() >> 4; cx++) for (int cz = a.z() >> 4; cz <= b.z() >> 4; cz++) blockChunk(cx, cz);
    }
  }

  static final class Listener {
    private final WorldGitPlugin plugin;
    private final boolean fawe;

    Listener(WorldGitPlugin plugin, boolean fawe) {
      this.plugin = plugin;
      this.fawe = fawe;
    }

    @Subscribe
    public void onEdit(EditSessionEvent event) {
      if (event.getStage() != EditSession.Stage.BEFORE_CHANGE) return;
      var actor = event.getActor();
      if (event.getWorld() == null) return;
      var sess = new Sess(plugin, event.getWorld().getName(), actor==null ? null : actor.getUniqueId(), actor==null ? null : actor.getName());
      Extent extent = event.getExtent();
      if (fawe) {
        try {
          extent = FaweHook.wrap(extent, sess);
        } catch (Throwable t) {
          plugin.getLogger().warning("FAWE processor 掛接失敗：" + t);
        }
      }
      event.setExtent(new Log(extent, sess));
    }
  }

  /** 逐格與 bulk 呼叫都記錄；bulk 以 Region 涵蓋的 chunk 為準（是上界，不一定每格都改）。 */
  static final class Log extends AbstractDelegateExtent {
    private final Sess sess;

    Log(Extent extent, Sess sess) {
      super(extent);
      this.sess = sess;
    }

    @Override
    public com.sk89q.worldedit.entity.Entity createEntity(com.sk89q.worldedit.util.Location location,com.sk89q.worldedit.entity.BaseEntity state) {
      var entity=super.createEntity(location,state);sess.entity(entity,location);return entity;
    }
    @Override
    public com.sk89q.worldedit.entity.Entity createEntity(com.sk89q.worldedit.util.Location location,com.sk89q.worldedit.entity.BaseEntity state,UUID uuid) {
      var entity=super.createEntity(location,state,uuid);sess.entity(entity,location);return entity;
    }

    @Override
    public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 position, T block) throws WorldEditException {
      sess.block(position.x(), position.z());
      return super.setBlock(position, block);
    }

    @Override
    public <T extends BlockStateHolder<T>> boolean setBlock(int x, int y, int z, T block) throws WorldEditException {
      sess.block(x, z);
      return super.setBlock(x, y, z, block);
    }

    @Override
    public <B extends BlockStateHolder<B>> int setBlocks(Region region, B block) throws MaxChangedBlocksException {
      sess.region(region);
      return super.setBlocks(region, block);
    }

    @Override
    public int setBlocks(Region region, Pattern pattern) throws MaxChangedBlocksException {
      sess.region(region);
      return super.setBlocks(region, pattern);
    }

    @Override
    public int setBlocks(Set<BlockVector3> vectors, Pattern pattern) {
      for (BlockVector3 v : vectors) sess.block(v.x(), v.z());
      return super.setBlocks(vectors, pattern);
    }

    @Override
    public int replaceBlocks(Region region, Set<BaseBlock> filter, Pattern replacement) throws MaxChangedBlocksException {
      sess.region(region);
      return super.replaceBlocks(region, filter, replacement);
    }

    @Override
    public int replaceBlocks(Region region, Mask mask, Pattern replacement) throws MaxChangedBlocksException {
      sess.region(region);
      return super.replaceBlocks(region, mask, replacement);
    }
  }
}
