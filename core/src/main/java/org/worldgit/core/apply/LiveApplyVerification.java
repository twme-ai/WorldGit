package org.worldgit.core.apply;

import java.io.IOException;
import java.util.*;
import org.worldgit.core.model.ChunkPos;
import org.worldgit.core.store.ObjectStore;
import org.worldgit.core.store.TreeEditor;

/** A live world may evolve outside the writer's footprint. Those changes remain working changes. */
public final class LiveApplyVerification {
  private LiveApplyVerification() {}
  public static Set<ChunkPos> affected(ApplyPlan plan) {
    var chunks=new TreeSet<>(plan.chunks().keySet());
    for(var op:plan.entities()) {if(op.hint()!=null)chunks.add(op.hint());if(op.targetChunk()!=null)chunks.add(op.targetChunk());}
    return chunks;
  }
  public static String observedFootprint(ObjectStore store,ApplyPlan plan,String observed) throws IOException {
    var editor=new TreeEditor(store,plan.baseTree());
    for(var pos:affected(plan)) editor.putEntry(pos.treePath(),TreeEditor.find(store,observed,pos.treePath()));
    for(var path:plan.worldMeta().keySet()) editor.putEntry(path,TreeEditor.find(store,observed,path));
    return editor.write();
  }
}
