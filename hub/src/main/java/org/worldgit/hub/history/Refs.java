package org.worldgit.hub.history;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.worldgit.core.model.CommitMetadata;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.normalize.DecodeBudget;
import org.worldgit.core.store.CommitTrailers;
import org.worldgit.core.store.RefStore;

/** 分支（refs/heads/*）的讀取輔助：只讀、不改 core。 */
final class Refs {
  private static final Pattern SNAPSHOT = Pattern.compile("^WorldGit-Snapshot:\\s*([0-9a-fA-F-]{36})\\s*$", Pattern.MULTILINE);
  static final int MAX_BRANCHES = 500;

  private Refs() {}

  /** 分支名稱的語法檢查（沿用 git 的 ref 規則；另外限制長度）。 */
  static boolean validBranch(String name) {
    return name != null && !name.isBlank() && name.length() <= 100 && Repository.isValidRefName(Constants.R_HEADS + name);
  }

  /** 分支名稱 → head commit id（最多 MAX_BRANCHES 個，依名稱排序）。 */
  static SortedMap<String, ObjectId> branches(Repository repo) throws IOException {
    var out = new TreeMap<String, ObjectId>();
    for (Ref r : repo.getRefDatabase().getRefsByPrefix(Constants.R_HEADS)) {
      if (r.getObjectId() == null || r.isSymbolic()) continue;
      if (out.size() >= MAX_BRANCHES) throw new DecodeBudget.Exceeded("分支數上限 " + MAX_BRANCHES);
      DecodeBudget.work(1);
      out.put(r.getName().substring(Constants.R_HEADS.length()), r.getObjectId());
    }
    return out;
  }

  static ObjectId tip(Repository repo, String branch) throws IOException {
    if (!validBranch(branch)) return null;
    Ref r = repo.exactRef(Constants.R_HEADS + branch);
    return r == null ? null : r.getObjectId();
  }

  /** HEAD 指向的分支名；repo 還沒有 commit 時也能取得（symbolic ref 的目標）。 */
  static String headBranch(Repository repo) throws IOException {
    Ref head = repo.exactRef(Constants.HEAD);
    if (head != null && head.isSymbolic() && head.getTarget().getName().startsWith(Constants.R_HEADS))
      return head.getTarget().getName().substring(Constants.R_HEADS.length());
    return null;
  }

  /** 各頁共用同一個世界預設分支規則，避免 detached／unborn HEAD 的退回行為不一致。 */
  static String defaultBranch(SortedMap<DimensionId, String> heads, SortedSet<String> names) {
    String head = heads.get(DimensionId.OVERWORLD);
    if (head == null) head = heads.values().stream().filter(Objects::nonNull).findFirst().orElse(null);
    if (head != null && names.contains(head)) return head;
    if (names.contains("main")) return "main";
    return names.isEmpty() ? (head == null ? "main" : head) : names.first();
  }

  static RefStore.Commit commit(RevCommit c) throws IOException {
    if (c.getRawBuffer().length > 1_048_576) throw new IOException("commit 物件超過 1 MiB");
    var a = c.getAuthorIdent();
    var b = c.getCommitterIdent();
    CommitMetadata m = CommitTrailers.parse(
        new CommitMetadata.Identity(a.getName(), a.getEmailAddress()),
        new CommitMetadata.Identity(b.getName(), b.getEmailAddress()),
        b.getWhenAsInstant(),
        c.getFullMessage());
    return new RefStore.Commit(c.name(), c.getTree().name(), Arrays.stream(c.getParents()).map(RevCommit::name).toList(), m);
  }

  /** 自某個 tip 往回的歷史（新到舊），最多 limit 個。 */
  static List<RefStore.Commit> log(Repository repo, ObjectId tip, int limit) throws IOException {
    try (var walk = new RevWalk(repo)) {
      walk.markStart(walk.parseCommit(tip));
      var out = new ArrayList<RefStore.Commit>();
      for (RevCommit c : walk) {
        out.add(commit(c));
        if (out.size() >= limit) break;
      }
      return out;
    }
  }

  /** 只取 WorldGit-Snapshot trailer（比完整解析便宜，ahead／behind 與索引用）。 */
  static String snapshot(RevCommit c) throws IOException {
    if (c.getRawBuffer().length > 1_048_576) throw new IOException("commit 物件超過 1 MiB");
    DecodeBudget.objects(1);
    DecodeBudget.read(c.getRawBuffer().length);
    DecodeBudget.work(1);
    Matcher m = SNAPSHOT.matcher(c.getFullMessage());
    String found = null;
    while (m.find()) {
      try { found = UUID.fromString(m.group(1)).toString(); }
      catch (IllegalArgumentException e) { throw new IOException("snapshot UUID 無效", e); }
    }
    return found;
  }

  /** 從所有分支 tip 出發的歷史：snapshot → 最新的 commit id（最多走 limit 個 commit）。 */
  static Map<String, String> snapshotIndex(Repository repo, int limit) throws IOException {
    var out = new HashMap<String, String>();
    try (var walk = new RevWalk(repo)) {
      for (ObjectId id : branches(repo).values()) walk.markStart(walk.parseCommit(id));
      int n = 0;
      for (RevCommit c : walk) {
        if (++n > limit) throw new DecodeBudget.Exceeded("snapshot 索引歷史上限 " + limit);
        String s = snapshot(c);
        if (s != null) out.putIfAbsent(s, c.name());
      }
    }
    return out;
  }

  record Counts(Set<String> only, boolean truncated) {}

  /** 從分支 tip 可達的 snapshot 集合；恰好 limit 筆時再讀一筆才標示截斷。 */
  static Counts reachable(Repository repo, ObjectId tip, int limit) throws IOException {
    var snaps = new LinkedHashSet<String>();
    try (var walk = new RevWalk(repo)) {
      walk.markStart(walk.parseCommit(tip));
      int n = 0;
      for (RevCommit c : walk) {
        if (n++ >= limit) return new Counts(snaps, true);
        DecodeBudget.work(1);snaps.add(c.name());
      }
    }
    return new Counts(snaps, false);
  }
}
