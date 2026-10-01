package org.worldgit.core.store;

import java.io.*;
import java.util.*;
import org.worldgit.core.model.CommitMetadata;

public interface RefStore {
  record Commit(String id, String tree, List<String> parents, CommitMetadata metadata) {
    public Commit {
      parents = List.copyOf(parents);
    }
  }

  String head() throws IOException;

  String resolve(String revision) throws IOException;

  Commit readCommit(String id) throws IOException;

  List<Commit> log(int limit) throws IOException;

  /** 比較 expected HEAD 後更新；並行寫入失敗必須回報，不能強制覆寫。 */
  String commit(String tree, String expectedHead, CommitMetadata metadata) throws IOException;
}
