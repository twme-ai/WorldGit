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

/** JGit 細節止於此模組；public API 使用十六進位 id，不洩漏 JGit 類別。單一操作需持有 RepoLock。 */
public final class JGitStore implements ObjectStore, RefStore, AutoCloseable {
  public static final long PACK_LIMIT = 95_000_000L;
  private static final String MAIN = "refs/heads/main";
  private final FileRepository repo;
  private final ObjectInserter inserter;
  private final ObjectReader reader;

  public JGitStore(Path directory, boolean create) throws IOException {
    if (!create && !Files.isRegularFile(directory.resolve("HEAD")))
      throw new IOException("尚未 init：" + directory);
    repo = new FileRepository(directory.toFile());
    if (create && !Files.exists(directory.resolve("HEAD"))) {
      repo.create(true);
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
    if (data.length > NbtLimit()) throw new IOException("blob 超過 32 MiB；請分割資料");
    return inserter.insert(Constants.OBJ_BLOB, data).name();
  }

  private static int NbtLimit() {
    return org.worldgit.core.anvil.Nbt.MAX_BYTES;
  }

  @Override
  public byte[] readBlob(String id) throws IOException {
    return reader.open(ObjectId.fromString(id), Constants.OBJ_BLOB).getBytes(NbtLimit());
  }

  @Override
  public String writeTree(Collection<Entry> entries) throws IOException {
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
    var parser = new CanonicalTreeParser();
    parser.reset(reader, ObjectId.fromString(id));
    while (!parser.eof()) {
      String name = parser.getEntryPathString();
      Kind kind = parser.getEntryFileMode().equals(FileMode.TREE) ? Kind.TREE : Kind.BLOB;
      result.put(name, new Entry(name, kind, parser.getEntryObjectId().name()));
      parser.next();
    }
    return result;
  }

  @Override
  public void flush() throws IOException {
    inserter.flush();
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
    CommitBuilder c = new CommitBuilder();
    c.setTreeId(ObjectId.fromString(tree));
    if (expected != null) c.setParentId(ObjectId.fromString(expected));
    c.setAuthor(new PersonIdent(m.author().name(), m.author().email(), m.time(), ZoneOffset.UTC));
    c.setCommitter(
        new PersonIdent(m.committer().name(), m.committer().email(), m.time(), ZoneOffset.UTC));
    c.setMessage(CommitTrailers.message(m));
    ObjectId id = inserter.insert(c);
    flush();
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

  public List<Long> repack() throws IOException {
    flush();
    return BoundedRepack.run(repo, PACK_LIMIT);
  }

  public List<Long> gc() throws IOException {
    return repack();
  }

  @Override
  public void close() {
    inserter.close();
    reader.close();
    repo.close();
  }
}
