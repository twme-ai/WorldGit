package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.model.*;
import org.worldgit.core.store.*;

class PackLimitTest {
  @TempDir Path temp;

  @Test
  void repackAndGcSplitOver95MBAndRemainReadable() throws Exception {
    var blobs = new ArrayList<ObjectStore.Entry>();
    var expected = new HashMap<String, byte[]>();
    Path path = temp.resolve("repo");
    try (var store = new JGitStore(path, true)) {
      for (int i = 0; i < 8; i++) {
        byte[] data = new byte[14_000_000];
        new Random(i).nextBytes(data);
        String id = store.writeBlob(data);
        blobs.add(new ObjectStore.Entry("random-" + i, ObjectStore.Kind.BLOB, id));
        expected.put(id, java.security.MessageDigest.getInstance("SHA-256").digest(data));
      }
      String tree = store.writeTree(blobs);
      var author = new CommitMetadata.Identity("test", "test@example.test");
      store.commit(
          tree,
          null,
          new CommitMetadata(
              author,
              author,
              "pack test",
              Instant.now(),
              4903,
              DimensionId.OVERWORLD,
              CommitMetadata.Source.CLI,
              false,
              UUID.randomUUID(),
              List.of()));
      List<Long> sizes = store.repack();
      assertTrue(sizes.size() >= 2);
      assertTrue(sizes.stream().allMatch(n -> n <= JGitStore.PACK_LIMIT && n < 100_000_000));
      System.out.println("PACK_SPLIT " + sizes);
    }
    try (var store = new JGitStore(path, false)) {
      for (var e : expected.entrySet())
        assertArrayEquals(
            e.getValue(),
            java.security.MessageDigest.getInstance("SHA-256").digest(store.readBlob(e.getKey())));
      assertTrue(store.gc().stream().allMatch(n -> n <= JGitStore.PACK_LIMIT));
    }
    try (var store = new JGitStore(path, false)) {
      assertEquals(8, store.readTree(store.log(1).getFirst().tree()).size());
    }
  }
}
