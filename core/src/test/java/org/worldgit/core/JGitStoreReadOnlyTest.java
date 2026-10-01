package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.config.WorldGitConfig;
import org.worldgit.core.model.CommitMetadata;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.service.WorldRepositories;
import org.worldgit.core.anvil.WorldLayout;
import org.worldgit.core.store.JGitStore;

/** Hub 用的唯讀共享 Repository 模式：可讀、寫入一律失敗、close 不關閉共享 repository。 */
class JGitStoreReadOnlyTest {
  @TempDir Path temp;

  @Test
  void readsSharedRepositoryButRefusesWritesAndKeepsRepositoryOpen() throws Exception {
    Path world = temp.resolve("world");
    TestWorlds.copy(TestWorlds.fixture("26.2"), world);
    var repos = new WorldRepositories(WorldLayout.discover(world));
    var author = new CommitMetadata.Identity("test", "test@example.test");
    assertTrue(repos.init(null, "creative", WorldGitConfig.Track.ALL, author).success());
    Path dir = repos.tracked().get(DimensionId.OVERWORLD);

    try (var shared = new FileRepository(dir.toFile())) {
      String head;
      try (var writable = new JGitStore(dir, false)) {
        head = writable.head();
      }
      var ro = JGitStore.readOnly(shared);
      assertEquals(head, ro.head());
      var commit = ro.readCommit(head);
      assertFalse(ro.readTree(commit.tree()).isEmpty());
      assertEquals(1, ro.log(5).size());
      assertThrows(IOException.class, () -> ro.writeBlob(new byte[] {1, 2, 3}));
      assertThrows(IOException.class, () -> ro.writeTree(java.util.List.of()));
      ro.close();
      // 共享 repository 仍然可用（第二個 reader 可正常讀取）
      var second = JGitStore.readOnly(shared);
      assertEquals(head, second.head());
      second.close();
    }
  }
}
