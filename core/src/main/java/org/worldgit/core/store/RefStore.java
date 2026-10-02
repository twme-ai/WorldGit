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

  record Head(String commit, String branch) {}

  String head() throws IOException;

  default Head headState() throws IOException {
    return new Head(head(), null);
  }

  default SortedMap<String, String> branches() throws IOException {
    throw new IOException("refs 不支援 branch");
  }

  default void updateRef(String ref, String expected, String target) throws IOException {
    throw new IOException("refs 不支援更新");
  }

  default void checkout(Head expected, Head target) throws IOException {
    throw new IOException("refs 不支援 HEAD 切換");
  }

  default String createCommit(String tree, String parent, CommitMetadata metadata)
      throws IOException {
    throw new IOException("refs 不支援獨立 commit");
  }

  default String createCommit(
      String tree, List<String> parents, CommitMetadata metadata, Map<String, String> trailers)
      throws IOException {
    if (parents.size() > 1 || !trailers.isEmpty())
      throw new IOException("refs 不支援多 parent／合併 trailer");
    return createCommit(tree, parents.isEmpty() ? null : parents.getFirst(), metadata);
  }

  default List<Commit> allCommits() throws IOException {
    return log(Integer.MAX_VALUE);
  }

  default boolean isAncestor(String ancestor, String descendant) throws IOException {
    throw new IOException("refs 不支援祖先檢查");
  }

  String resolve(String revision) throws IOException;

  Commit readCommit(String id) throws IOException;

  List<Commit> log(int limit) throws IOException;

  /** 比較 expected HEAD 後更新；並行寫入失敗必須回報，不能強制覆寫。 */
  String commit(String tree, String expectedHead, CommitMetadata metadata) throws IOException;
}
