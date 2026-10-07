package org.worldgit.fabric;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.anvil.WorldLayout;
import org.worldgit.core.capture.PlayerTouchedEntities;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.service.WorldRepositories;
import org.worldgit.core.service.DimensionRepository;

/**
 * creative 範本的 player-touched 實體：server 執行緒只收集無 Minecraft 物件的 UUID 關聯（根載具與乘客閉包），
 * sidecar 由 repo 作業佇列串行寫入（與 capture／commit 同一順序）；絕不在事件執行緒寫檔。
 * UUID 轉換（變形）繼承資格；跨維度傳送時目的維度先記錄，來源維度在下一次完整 commit 移除。
 */
public final class TouchedEntities {
    private final ServerRuntime rt;
    /** UUID → 最近一次記錄它的維度；用來判斷載入的實體是否換了維度。 */
    private final Map<UUID, DimensionId> known = new ConcurrentHashMap<>();
    /** init 之前玩家已觸及的實體（本次開服記憶體）；init 時由 source factory 持鎖繼承。 */
    private final Map<DimensionId, Set<UUID>> beforeInit = new ConcurrentHashMap<>();

    TouchedEntities(ServerRuntime rt) {
        this.rt = rt;
    }

    public boolean known(UUID id) { return known.containsKey(id); }

    /** repo 執行緒：讀取各維度 sidecar，載入既有集合。 */
    void refresh() throws IOException {
        var layout = WorldLayout.discover(rt.worldRoot());
        for (var entry : new WorldRepositories(layout).tracked().entrySet())
            for (UUID id : PlayerTouchedEntities.read(entry.getValue())) known.put(id, entry.getKey());
    }

    private static void collect(Entity entity, Set<UUID> ids) {
        // 玩家本身不入庫，但不能漏掉載在玩家上的非玩家乘客。
        if (!(entity instanceof Player) && !ids.add(entity.getUUID())) return;
        for (Entity passenger : entity.getPassengers()) collect(passenger, ids);
    }

    /** 伺服器執行緒：玩家造成的實體異動。 */
    public void touch(Entity entity) {
        if (entity == null || entity instanceof Player || !(entity.level() instanceof ServerLevel level) || rt.isClosed()) return;
        Entity root = entity;
        var visited = new HashSet<UUID>();
        while (root.getVehicle() != null && visited.add(root.getUUID())) root = root.getVehicle();
        var ids = new TreeSet<UUID>();
        collect(root, ids);
        if (ids.isEmpty()) return;
        var dimension = ServerRuntime.dimensionId(level);
        for (UUID id : ids) known.put(id, dimension);
        rt.tracker(dimension).mark(ServerRuntime.corePos(entity.chunkPosition()));
        var data = nbt(ids);
        rt.runRepo(() -> {
            var path = new WorldRepositories(WorldLayout.discover(rt.worldRoot())).tracked().get(dimension);
            if (path != null) try (var repository = new DimensionRepository(path, dimension, false)) {
                PlayerTouchedEntities.touch(repository.directory(), data);
            }
            else beforeInit.computeIfAbsent(dimension, key -> ConcurrentHashMap.newKeySet()).addAll(ids);
            return null;
        }).exceptionally(error -> {
            ServerRuntime.LOG.warn("WORLDGIT 保存觸及實體失敗：{}", rt.mask(String.valueOf(error.getMessage())));
            return null;
        });
    }

    /** 實體載入（含跨維度傳送、區塊重新載入）：只有資格紀錄在別的維度時才更新。 */
    public void loaded(Entity entity, ServerLevel level) {
        var recorded = known.get(entity.getUUID());
        if (recorded != null && !recorded.equals(ServerRuntime.dimensionId(level))) touch(entity);
    }

    /** 由 init 的 source factory 呼叫，此時 core 已持有該維度 repo lock。 */
    void seed(DimensionId dimension, Path repository) {
        var ids = beforeInit.remove(dimension);
        if (ids == null || ids.isEmpty()) return;
        try {
            PlayerTouchedEntities.touch(repository, nbt(new TreeSet<>(ids)));
        } catch (IOException error) {
            beforeInit.computeIfAbsent(dimension, key -> ConcurrentHashMap.newKeySet()).addAll(ids);
            throw new java.io.UncheckedIOException(error);
        }
    }

    /** 轉換事件：資格從舊 UUID 繼承到新實體。 */
    public void converted(Entity from, Entity to) {
        if (from != null && to != null && known.containsKey(from.getUUID())) touch(to);
    }

    static Nbt.Compound nbt(SortedSet<UUID> ids) {
        var iterator = ids.iterator();
        var root = uuid(iterator.next());
        var children = new ArrayList<Object>();
        while (iterator.hasNext()) children.add(uuid(iterator.next()));
        if (!children.isEmpty()) root.put("Passengers", new Nbt.ListTag(10, children));
        return root;
    }

    private static Nbt.Compound uuid(UUID id) {
        return new Nbt.Compound().with("UUID", new int[] {(int) (id.getMostSignificantBits() >>> 32), (int) id.getMostSignificantBits(),
            (int) (id.getLeastSignificantBits() >>> 32), (int) id.getLeastSignificantBits()});
    }
}
