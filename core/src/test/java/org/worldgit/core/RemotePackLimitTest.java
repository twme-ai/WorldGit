package org.worldgit.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.model.*;
import org.worldgit.core.remote.*;
import org.worldgit.core.store.*;

class RemotePackLimitTest {
  @TempDir Path temp;

  @Test
  void partitionsRealTransportOver100MBAndReopens() throws Exception {
    Path source = temp.resolve("source.git"),
        target = temp.resolve("target.git"),
        clone = temp.resolve("clone.git");
    String commit;
    var blobs = new ArrayList<String>();
    var author = new CommitMetadata.Identity("pack", "pack@local");
    var m =
        new CommitMetadata(
            author,
            author,
            "large",
            Instant.now(),
            4671,
            DimensionId.OVERWORLD,
            CommitMetadata.Source.CLI,
            false,
            UUID.randomUUID(),
            List.of());
    try (var s = new JGitStore(source, true);
        var t = new JGitStore(target, true);
        var c = new JGitStore(clone, true)) {
      var random = new Random(75);
      var editor = new TreeEditor(s, null);
      for (int i = 0; i < 5; i++) {
        byte[] data = new byte[22_000_000];
        random.nextBytes(data);
        String id = s.writeBlob(data);
        blobs.add(id);
        editor.putBlob("random-" + i, data);
      }
      commit = s.commit(editor.write(), null, m);
    }
    var secret = new Credentials.Secret(Credentials.Mode.BASIC, "anonymous", "");
    List<GitTransfer.PackSize> sent;
    try (var transfer = new GitTransfer(source, target.toUri().toString(), secret)) {
      transfer.stage(Map.of("refs/heads/main", commit), UUID.randomUUID(), m);
      transfer.publish(Map.of("refs/heads/main", commit), Map.of("refs/heads/main", ""), false);
      sent = transfer.sizes();
      assertTrue(sent.size() > 1);
      assertTrue(sent.stream().allMatch(p -> p.preparedBytes() <= JGitStore.PACK_LIMIT));
      assertTrue(sent.stream().mapToLong(GitTransfer.PackSize::preparedBytes).sum() > 100_000_000);
    }
    try (var transfer = new GitTransfer(clone, target.toUri().toString(), secret)) {
      transfer.fetchStages("refs/worldgit/incoming/origin/");
      transfer.fetch("refs/heads/main", "refs/remotes/origin/main");
      assertTrue(
          transfer.sizes().stream().allMatch(p -> p.preparedBytes() <= JGitStore.PACK_LIMIT));
      System.out.println("sent=" + sent + " received=" + transfer.sizes());
    }
    try (var store = new JGitStore(clone, false)) {
      assertEquals(commit, store.resolve("refs/remotes/origin/main"));
      for (String id : blobs) assertEquals(22_000_000, store.readBlob(id).length);
    }
  }
}
