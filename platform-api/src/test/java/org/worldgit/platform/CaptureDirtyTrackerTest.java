package org.worldgit.platform;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.model.ChunkPos;

class CaptureDirtyTrackerTest {
  @Test void indexedStatusPreservesCommitTriggerAndLaterEvents() {
    var tracker=new DirtyChunkTracker();var pos=new ChunkPos(1,2);
    tracker.mark(pos);var first=tracker.indexBatch();tracker.indexed(first);
    assertTrue(tracker.indexBatch().generations().isEmpty());assertTrue(tracker.chunks().contains(pos));
    var commit=tracker.capture();tracker.mark(pos);
    tracker.indexed(first);tracker.acknowledge(commit);
    assertTrue(tracker.indexBatch().generations().containsKey(pos));assertTrue(tracker.chunks().contains(pos));
    tracker.acknowledge(tracker.capture());assertTrue(tracker.chunks().isEmpty());assertTrue(tracker.indexBatch().generations().isEmpty());
  }
}
