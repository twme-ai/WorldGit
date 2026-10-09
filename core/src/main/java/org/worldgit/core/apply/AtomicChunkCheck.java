package org.worldgit.core.apply;
import java.io.IOException;
import java.util.*;
import org.worldgit.core.capture.PlayerTouchedEntities;
import org.worldgit.core.model.ChunkSnapshot;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.core.normalize.SnapshotCodec;
import org.worldgit.core.store.ObjectStore;
import org.worldgit.core.store.TreeEditor;
/** Detached data prepared before owner work; compares contents rather than dirty events alone. */
public final class AtomicChunkCheck {
  private final Map<String,byte[]> expected;
  private final Set<UUID> touched;
  public AtomicChunkCheck(ObjectStore store,String baseTree,ChunkPos pos,Set<UUID> touched) throws IOException {
    this.touched=touched==null ? null : Set.copyOf(touched);
    expected=new TreeMap<>();var entry=TreeEditor.find(store,baseTree,pos.treePath());
    if(entry!=null) for(var file:store.readTree(entry.id()).values()) expected.put(file.name(),store.readBlob(file.id()));
  }
  public boolean matches(ChunkSnapshot snapshot) throws IOException {
    var actual=SnapshotCodec.chunkFiles(touched==null ? snapshot : PlayerTouchedEntities.filter(snapshot,touched));
    if(!actual.keySet().equals(expected.keySet()))return false;
    for(var entry:actual.entrySet())if(!Arrays.equals(entry.getValue(),expected.get(entry.getKey())))return false;
    return true;
  }
}
