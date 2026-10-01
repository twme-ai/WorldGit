package org.worldgit.core.store;

import java.io.*;
import java.nio.file.*;
import java.time.ZoneOffset;
import java.util.*;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.treewalk.*;
import org.worldgit.core.model.CommitMetadata;
import org.worldgit.core.normalize.DecodeBudget;

/** JGit 細節止於此模組；public API 使用十六進位 id，不洩漏 JGit 類別。單一操作需持有 RepoLock。 */
public final class JGitStore implements ObjectStore, RefStore, AutoCloseable {
  public static final long PACK_LIMIT = 95_000_000L;
  private static final String MAIN = "refs/heads/main";
  private final Repository repo;
  private final boolean ownsRepo;
  private final ObjectInserter inserter;
  private final ObjectReader reader;

  /**
   * 唯讀存取一個由呼叫端共享管理的 JGit Repository（例如 Hub 的 RepositoryCache）。每個 store 有自己的
   * ObjectReader，因此可在各執行緒各開一個；close() 只釋放 reader，不關閉 repository，寫入方法會失敗。
   */
  public static JGitStore readOnly(Repository shared) {
    return new JGitStore(shared);
  }

  private JGitStore(Repository shared) {
    repo = shared;
    ownsRepo = false;
    inserter = null;
    reader = shared.newObjectReader();
  }

  public JGitStore(Path directory, boolean create) throws IOException {
    if (!create && !Files.isRegularFile(directory.resolve("HEAD")))
      throw new IOException("尚未 init：" + directory);
    var opened = new FileRepository(directory.toFile());
    repo = opened;
    ownsRepo = true;
    if (create && !Files.exists(directory.resolve("HEAD"))) {
      opened.create(true);
      repo.updateRef("HEAD").link(MAIN);
    }
    var config = repo.getConfig();
    config.setLong("pack", null, "packSizeLimit", PACK_LIMIT);
    config.setInt("gc", null, "auto", 0);
    config.setInt("gc", null, "autoPackLimit", 0);
    config.save();
    inserter = repo.newObjectInserter();
    reader = repo.newObjectReader();
  }

  @Override
  public String writeBlob(byte[] data) throws IOException {
    requireWritable();
    if (data.length > NbtLimit()) throw new IOException("blob 超過 32 MiB；請分割資料");
    return inserter.insert(Constants.OBJ_BLOB, data).name();
  }

  private static int NbtLimit() {
    return org.worldgit.core.anvil.Nbt.MAX_BYTES;
  }

  @Override
  public byte[] readBlob(String id) throws IOException {
    var loader = reader.open(ObjectId.fromString(id), Constants.OBJ_BLOB);
    DecodeBudget.read(loader.getSize());
    DecodeBudget.objects(1);
    return loader.getBytes(NbtLimit());
  }

  @Override
  public String writeTree(Collection<Entry> entries) throws IOException {
    requireWritable();
    var formatter = new TreeFormatter();
    var sorted = new ArrayList<>(entries);
    sorted.sort(
        (a, b) ->
            (a.name() + (a.kind() == Kind.TREE ? "/" : "\0"))
                .compareTo(b.name() + (b.kind() == Kind.TREE ? "/" : "\0")));
    var names = new HashSet<String>();
    for (var e : sorted) {
      if (!names.add(e.name())) throw new IOException("tree entry 重複");
      formatter.append(
          e.name(),
          e.kind() == Kind.TREE ? FileMode.TREE : FileMode.REGULAR_FILE,
          ObjectId.fromString(e.id()));
    }
    return inserter.insert(formatter).name();
  }

  @Override
  public SortedMap<String, Entry> readTree(String id) throws IOException {
    var result = new TreeMap<String, Entry>();
    if (id == null) return result;
    DecodeBudget.objects(1);
    DecodeBudget.read(reader.open(ObjectId.fromString(id), Constants.OBJ_TREE).getSize());
    var parser = new CanonicalTreeParser();
    parser.reset(reader, ObjectId.fromString(id));
    while (!parser.eof()) {
      DecodeBudget.work(1);
      String name = parser.getEntryPathString();
      Kind kind = parser.getEntryFileMode().equals(FileMode.TREE) ? Kind.TREE : Kind.BLOB;
      result.put(name, new Entry(name, kind, parser.getEntryObjectId().name()));
      parser.next();
    }
    return result;
  }

  @Override
  public void flush() throws IOException {
    if (inserter != null) inserter.flush();
  }

  @Override
  public String head() throws IOException {
    ObjectId id = repo.resolve("HEAD");
    return id == null ? null : id.name();
  }

  @Override
  public String resolve(String revision) throws IOException {
    ObjectId id = repo.resolve(revision + "^{commit}");
    if (id == null) throw new IOException("無法解析 commit：" + revision);
    return id.name();
  }

  @Override
  public Commit readCommit(String id) throws IOException {
    try (var walk = new RevWalk(repo)) {
      return convert(walk.parseCommit(ObjectId.fromString(id)));
    }
  }

  private Commit convert(RevCommit c) throws IOException {
    if (c.getRawBuffer().length > 1_048_576) throw new IOException("commit 物件超過 1 MiB");
    var a = c.getAuthorIdent();
    var b = c.getCommitterIdent();
    var m =
        CommitTrailers.parse(
            new CommitMetadata.Identity(a.getName(), a.getEmailAddress()),
            new CommitMetadata.Identity(b.getName(), b.getEmailAddress()),
            b.getWhenAsInstant(),
            c.getFullMessage());
    return new Commit(
        c.name(),
        c.getTree().name(),
        Arrays.stream(c.getParents()).map(RevCommit::name).toList(),
        m);
  }

  @Override
  public List<Commit> log(int limit) throws IOException {
    if (limit < 1) throw new IllegalArgumentException("limit");
    String head = head();
    if (head == null) return List.of();
    try (var walk = new RevWalk(repo)) {
      walk.markStart(walk.parseCommit(ObjectId.fromString(head)));
      var result = new ArrayList<Commit>();
      for (var c : walk) {
        result.add(convert(c));
        if (result.size() == limit) break;
      }
      return List.copyOf(result);
    }
  }

  @Override
  public String commit(String tree, String expected, CommitMetadata m) throws IOException {
    String created = createCommit(tree, expected, m);
    ObjectId id = ObjectId.fromString(created);
    RefUpdate update = repo.updateRef("HEAD");
    update.setExpectedOldObjectId(
        expected == null ? ObjectId.zeroId() : ObjectId.fromString(expected));
    update.setNewObjectId(id);
    update.setRefLogMessage("worldgit: " + m.message().lines().findFirst().orElse("commit"), false);
    var result = update.update();
    if (!Set.of(RefUpdate.Result.NEW, RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.NO_CHANGE)
        .contains(result)) throw new IOException("HEAD 更新失敗（可能有並行寫入）：" + result);
    return id.name();
  }

  @Override
  public String createCommit(String tree, String parent, CommitMetadata m) throws IOException {
    requireWritable();
    var builder = new CommitBuilder();
    builder.setTreeId(ObjectId.fromString(tree));
    if (parent != null) builder.setParentId(ObjectId.fromString(parent));
    builder.setAuthor(new PersonIdent(m.author().name(), m.author().email(), m.time(), ZoneOffset.UTC));
    builder.setCommitter(new PersonIdent(m.committer().name(), m.committer().email(), m.time(), ZoneOffset.UTC));
    builder.setMessage(CommitTrailers.message(m));
    String id = inserter.insert(builder).name();
    flush();
    // 讓 detached commit 與不變維度也可由 snapshot 找回；不依賴 reflog 到期。
    updateRef("refs/worldgit/snapshots/" + m.snapshot() + "/" + id, null, id);
    return id;
  }

  @Override
  public Head headState() throws IOException {
    var ref = repo.exactRef("HEAD");
    String branch = ref != null && ref.isSymbolic() && ref.getTarget().getName().startsWith("refs/heads/")
        ? ref.getTarget().getName().substring(11) : null;
    return new Head(head(), branch);
  }

  public static void validateBranch(String name) throws IOException {
    if (name == null || name.equals("HEAD") || name.startsWith("-")
        || !Repository.isValidRefName("refs/heads/" + name)) throw new IOException("分支名稱無效：" + name);
  }

  @Override
  public SortedMap<String,String> branches() throws IOException {
    var result = new TreeMap<String,String>();
    for (var ref : repo.getRefDatabase().getRefsByPrefix("refs/heads/"))
      if (ref.getObjectId() != null) result.put(ref.getName().substring(11), ref.getObjectId().name());
    return result;
  }

  @Override
  public void updateRef(String name, String expected, String target) throws IOException {
    requireWritable();
    if (!Repository.isValidRefName(name) || name.equals("HEAD")) throw new IOException("ref 名稱無效：" + name);
    var update = repo.updateRef(name);
    update.setExpectedOldObjectId(expected == null ? ObjectId.zeroId() : ObjectId.fromString(expected));
    update.setForceUpdate(true);
    if (target != null) update.setNewObjectId(ObjectId.fromString(target));
    update.setRefLogMessage("worldgit: refs", false);
    checkResult(target == null ? update.delete() : update.update());
  }

  @Override
  public void checkout(Head expected, Head target) throws IOException {
    requireWritable();
    if (!headState().equals(expected)) throw new IOException("HEAD 在切換期間改變");
    if (target.branch() != null) {
      validateBranch(target.branch());
      if (!Objects.equals(branches().get(target.branch()), target.commit())) throw new IOException("目標分支指標已改變");
      checkResult(repo.updateRef("HEAD").link("refs/heads/" + target.branch()));
    } else {
      var update = repo.updateRef("HEAD", true);
      update.setExpectedOldObjectId(expected.commit() == null ? ObjectId.zeroId() : ObjectId.fromString(expected.commit()));
      update.setNewObjectId(ObjectId.fromString(target.commit()));
      update.setForceUpdate(true);
      checkResult(update.update());
    }
  }

  private static void checkResult(RefUpdate.Result result) throws IOException {
    if (!Set.of(RefUpdate.Result.NEW, RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.FORCED,
        RefUpdate.Result.NO_CHANGE).contains(result)) throw new IOException("ref 更新失敗：" + result);
  }

  @Override
  public List<Commit> allCommits() throws IOException {
    try (var walk = new RevWalk(repo)) {
      for (var ref : repo.getRefDatabase().getRefs()) {
        if (ref.getObjectId() != null) {
          var object = walk.parseAny(ref.getObjectId());
          if (object instanceof RevCommit c) walk.markStart(c);
        }
      }
      if (head() != null) walk.markStart(walk.parseCommit(ObjectId.fromString(head())));
      var result = new ArrayList<Commit>();
      for (var commit : walk) result.add(convert(commit));
      return result;
    }
  }

  @Override
  public boolean isAncestor(String ancestor, String descendant) throws IOException {
    try (var walk = new RevWalk(repo)) {
      return walk.isMergedInto(walk.parseCommit(ObjectId.fromString(ancestor)), walk.parseCommit(ObjectId.fromString(descendant)));
    }
  }

  public List<Long> repack() throws IOException {
    requireWritable();
    flush();
    return BoundedRepack.run((FileRepository) repo, PACK_LIMIT);
  }

  public List<Long> gc() throws IOException {
    return repack();
  }

  @Override
  public void close() {
    if (inserter != null) inserter.close();
    reader.close();
    if (ownsRepo) repo.close();
  }

  private void requireWritable() throws IOException {
    if (inserter == null) throw new IOException("唯讀 store 不能寫入");
  }
}
