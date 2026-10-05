package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.*;
import org.worldgit.core.apply.*;
import org.worldgit.core.capture.SnapshotSource;
import org.worldgit.core.config.WorldGitConfig;
import org.worldgit.core.model.*;
import org.worldgit.core.service.*;

/** 線上注入入口的契約；遊戲持有 session，core 仍先單維度預檢再 apply／verify／HEAD。 */
class LiveOperationsTest {
  @TempDir Path temp;
  @Test void hostSessionPreflightAndSingleDimensionWriter() throws Exception {
    TestWorlds.copy(TestWorlds.fixture("26.2"),temp.resolve("server"));
    var layout=WorldLayout.discover(temp.resolve("server"));
    var worlds=new WorldRepositories(layout);
    var author=new CommitMetadata.Identity("test","test@example.org");
    assertTrue(worlds.initAll("creative", WorldGitConfig.Track.ALL, author, WorldGitConfig.Entities.ALL).success());
    try(var ops=new WorldOperations(layout)) {ops.createBranch("A",null);}
    TestWorlds.oneBlock(layout,DimensionId.OVERWORLD,new ChunkPos(0,0),9,0);
    assertTrue(worlds.commit(null,"B",author,2).success());
    var validated=new AtomicInteger(); var applied=new AtomicInteger();
    try(var host=WorldSessionLock.acquire(layout)) {
      var access=new WorldOperations.LiveAccess() {
        public SnapshotSource source(WorldLayout.Dimension dimension) {return new OfflineSnapshotSource(layout,dimension);}
        public void validate(ApplyPlan plan) throws IOException {
          if(validated.incrementAndGet()==1) throw new IOException("adapter preflight rejected");
        }
        public void applyAll(Collection<ApplyPlan> plans) throws IOException {
          applied.incrementAndGet();new OfflineApplier(layout).applyAll(plans,host);
        }
      };
      try(var ops=WorldOperations.live(layout,access)) {
        assertThrows(IOException.class,()->ops.switchTo("A",false,false,false,false));
        assertEquals(0,applied.get());
        assertTrue(ops.journal().isEmpty());
        assertTrue(ops.branches().stream().anyMatch(b->b.name().equals("main") && b.current()));
        validated.set(10);
        assertTrue(ops.switchTo("A",false,false,false,false).success());
        assertEquals(1,applied.get(),"必須只呼叫一次目標維度 adapter barrier");
        assertTrue(ops.verify("A",null,Scope.all(),true).success());
      }
      assertThrows(IOException.class,()->new WorldOperations(layout),"關閉 live 入口不得釋放 host session");
    }
    try(var ops=new WorldOperations(layout)) {assertTrue(ops.verify("A",null,Scope.all(),true).success());}
  }
}
