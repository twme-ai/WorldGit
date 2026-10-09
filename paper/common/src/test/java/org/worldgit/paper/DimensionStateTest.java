package org.worldgit.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.model.*;

class DimensionStateTest {
  @Test
  void entityMovesDirtyBothChunksWithoutTerrainFlagAndOldBatchCannotClearNewChanges() {
    var state = new DimensionState(DimensionId.OVERWORLD, "world");
    var entities = new HashSet<>(Set.of(new ChunkPos(0, 0)));
    var bridge = new NmsBridge() {
      public void applyChunk(World w,org.worldgit.core.apply.ApplyPlan.ChunkOp op,org.worldgit.core.config.IgnoreRules r) { throw new UnsupportedOperationException(); }
      public void removeEntities(World w,int x,int z,java.util.Set<java.util.UUID> ids) { throw new UnsupportedOperationException(); }
      public void spawnEntity(World w,org.worldgit.core.model.EntitySnapshot e) { throw new UnsupportedOperationException(); }
      public java.util.concurrent.CompletionStage<Void> finishChunk(World w,int x,int z) { throw new UnsupportedOperationException(); }
      public void saveRegion(World w) { throw new UnsupportedOperationException(); }
      public void flushIo(World w) { throw new UnsupportedOperationException(); }
      public AutoCloseable lockChunkTicks(World w,int x,int z) { throw new UnsupportedOperationException(); }
      public void refreshChunkLock(World w,int x,int z) {}
      public OwnerTick ownerTick(World w) { throw new UnsupportedOperationException(); }
      public String minecraftVersion() { return "test"; }
      public Nbt.Compound copyGameRules(World world) { return new Nbt.Compound(); }
      public RawChunk copy(World world, int x, int z) { throw new AssertionError(); }
      public void census(World world, CensusSink sink) {
        sink.chunk(0, 0, false, entities.contains(new ChunkPos(0, 0)));
        sink.chunk(1, 0, false, entities.contains(new ChunkPos(1, 0)));
      }
    };
    state.refresh(bridge, null);
    var old = state.dirty().capture();
    entities.clear(); entities.add(new ChunkPos(1, 0));
    assertEquals(Set.of(new ChunkPos(0, 0)), state.census().entityChunks()); // cached 普查尚未看到移動
    state.refresh(bridge, null);
    state.dirty().acknowledge(old);
    assertEquals(Set.of(new ChunkPos(0, 0), new ChunkPos(1, 0)), state.dirty().chunks());
    assertEquals(Set.of(new ChunkPos(1, 0)), state.census().entityChunks());
    assertTrue(state.census().unsaved().isEmpty());
  }
}
