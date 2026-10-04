package org.worldgit.core.store;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.internal.storage.pack.PackWriter;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.storage.pack.PackConfig;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.transport.http.*;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.worldgit.core.model.*;
import org.worldgit.core.remote.Credentials;

/** 標準 smart HTTP/file 傳輸。中繼 commit 依物件相依順序發布，最後才發布使用者 refs。 */
public final class GitTransfer implements AutoCloseable {
  public static final String STAGES = "refs/worldgit/transfers/";

  public record PackSize(long preparedBytes, Long wireBytes) {}

  private final Repository repo;
  private final String url;
  private final Credentials.Secret secret;
  private final Set<Path> spools = new HashSet<>();
  private final long limit;
  private final int timeoutSeconds;
  private final List<PackSize> sizes = new ArrayList<>();

  public GitTransfer(Path path, String url, Credentials.Secret secret) throws IOException {
    this(path, url, secret, JGitStore.PACK_LIMIT);
  }

  public GitTransfer(Path path, String url, Credentials.Secret secret, long limit)
      throws IOException {
    this(path,url,secret,limit,60);
  }

  public GitTransfer(Path path,String url,Credentials.Secret secret,long limit,int timeoutSeconds) throws IOException {
    if(timeoutSeconds<1 || timeoutSeconds>3600) throw new IllegalArgumentException("transport timeout 無效");
    this.timeoutSeconds=timeoutSeconds;
    this.repo = new FileRepository(path.toFile());
    this.url = url;
    this.secret = secret;
    this.limit = limit;
    var c = repo.getConfig();
    c.setInt("transfer", null, "unpackLimit", 0);
    c.setInt("fetch", null, "unpackLimit", 0);
    c.setString("http", null, "followRedirects", "false");
    c.save();
  }

  public List<PackSize> sizes() {
    return List.copyOf(sizes);
  }

  public SortedMap<String, String> advertised(boolean push) throws IOException {
    try (var t = open();
        var connection = push ? t.openPush() : t.openFetch()) {
      var result = new TreeMap<String, String>();
      for (var r : connection.getRefs())
        if (r.getObjectId() != null && !r.getName().endsWith("^{}"))
          result.put(r.getName(), r.getObjectId().name());
      return result;
    } catch (Exception e) {
      throw safe(e);
    }
  }

  private Transport open() throws IOException {
    try {
      var t = Transport.open(repo, new URIish(url));
      t.setTimeout(timeoutSeconds);
      t.setTagOpt(TagOpt.NO_TAGS);
      t.setPushThin(false);
      t.setFetchThin(false);
      t.setPushUseBitmaps(false);
      t.setPackConfig(packConfig());
      if (t instanceof TransportHttp http) {
        http.setAdditionalHeaders(Map.of("Authorization", secret.authorization()));
        // 不把 Authorization 轉送到重導向端點。
        repo.getConfig().setString("http", null, "followRedirects", "false");
        http.setHttpConnectionFactory(new MeasuredHttp(http.getHttpConnectionFactory()));
      }
      return t;
    } catch (Exception e) {
      throw safe(e);
    }
  }

  private PackConfig packConfig() {
    var c = new PackConfig(repo);
    c.setDeltaCompress(false);
    c.setReuseDeltas(false);
    c.setReuseObjects(false);
    c.setBuildBitmaps(false);
    c.setThreads(1);
    return c;
  }

  private IOException safe(Exception e) {
    return new IOException(secret.redact(e.getMessage()));
  }

  private Set<ObjectId> known(Collection<String> ids) throws IOException {
    var result = new HashSet<ObjectId>();
    for (String id : ids)
      if (repo.getObjectDatabase().has(ObjectId.fromString(id)))
        result.add(ObjectId.fromString(id));
    return result;
  }

  private long measure(Collection<String> wants, Collection<String> haves) throws IOException {
    try (var reader = repo.newObjectReader();
        var writer = new PackWriter(packConfig(), reader)) {
      writer.preparePack(NullProgressMonitor.INSTANCE, known(wants), known(haves));
      var counter =
          new OutputStream() {
            long n;

            @Override
            public void write(int b) {
              n++;
            }

            @Override
            public void write(byte[] b, int o, int l) {
              n += l;
            }
          };
      writer.writePack(NullProgressMonitor.INSTANCE, NullProgressMonitor.INSTANCE, counter);
      return counter.n;
    }
  }

  private Map<String, String> stagingAdvertisement;
  private boolean localStage;

  /** 伺服器端新合併也建立相同分批 refs，避免首次下載新的大型合併一次收到超限 pack。 */
  public void stageLocal(
      Map<String, String> targets, Collection<String> before, CommitMetadata metadata)
      throws IOException {
    localStage = true;
    stagingAdvertisement = new TreeMap<>();
    int n = 0;
    for (String id : before) stagingAdvertisement.put("have" + (n++), id);
    try {
      stage(targets, UUID.randomUUID(), metadata);
    } finally {
      localStage = false;
      stagingAdvertisement = null;
    }
  }

  private SortedMap<String, String> localRefs() throws IOException {
    var result = new TreeMap<String, String>();
    for (var ref : repo.getRefDatabase().getRefs())
      if (ref.getObjectId() != null) result.put(ref.getName(), ref.getObjectId().name());
    return result;
  }

  /** 永遠保留分批 refs，讓日後空 repo clone 也能逐批 negotiation。 */
  public void stage(Map<String, String> targets, UUID operation, CommitMetadata metadata)
      throws IOException {
    var advertised = localStage ? stagingAdvertisement : advertised(true);
    var needed = new LinkedHashMap<String, Integer>();
    try (var walk = new ObjectWalk(repo)) {
      for (String id : targets.values()) walk.markStart(walk.parseAny(ObjectId.fromString(id)));
      for (var id : known(advertised.values())) walk.markUninteresting(walk.parseAny(id));
      RevObject obj;
      while ((obj = walk.next()) != null) needed.put(obj.name(), obj.getType());
      while ((obj = walk.nextObject()) != null) needed.put(obj.name(), obj.getType());
    }
    if (needed.isEmpty()) return;
    var ordered = new ArrayList<String>();
    var seen = new HashSet<String>();
    try (var reader = repo.newObjectReader();
        var walk = new RevWalk(repo)) {
      record Visit(String id, boolean after) {}
      for (String root : needed.keySet()) {
        var stack = new ArrayDeque<Visit>();
        stack.push(new Visit(root, false));
        while (!stack.isEmpty()) {
          var v = stack.pop();
          if (v.after()) {
            ordered.add(v.id());
            continue;
          }
          if (!seen.add(v.id())) continue;
          stack.push(new Visit(v.id(), true));
          var deps = new ArrayList<String>();
          int type = needed.get(v.id());
          if (type == Constants.OBJ_TREE) {
            var tree = new CanonicalTreeParser();
            tree.reset(reader, ObjectId.fromString(v.id()));
            while (!tree.eof()) {
              deps.add(tree.getEntryObjectId().name());
              tree.next();
            }
          } else if (type == Constants.OBJ_COMMIT) {
            var c = walk.parseCommit(ObjectId.fromString(v.id()));
            deps.add(c.getTree().name());
            for (var p : c.getParents()) deps.add(p.name());
          } else if (type == Constants.OBJ_TAG)
            deps.add(walk.parseTag(ObjectId.fromString(v.id())).getObject().name());
          for (String d : deps)
            if (needed.containsKey(d) && !seen.contains(d)) stack.push(new Visit(d, false));
        }
      }
      var batch = new ArrayList<String>();
      long bound = 32;
      int part = 0;
      String previous = null;
      var stages = localStage ? localRefs() : advertised;
      var knownStages = known(advertised.values());
      for (var entry : stages.entrySet())
        if (entry.getKey().startsWith(STAGES)
            && knownStages.contains(ObjectId.fromString(entry.getValue()))
            && reader.open(ObjectId.fromString(entry.getValue())).getType() == Constants.OBJ_COMMIT)
          previous = entry.getValue();
      for (String id : ordered) {
        int type = needed.get(id);
        if (type == Constants.OBJ_TAG) continue;
        long size = reader.getObjectSize(ObjectId.fromString(id), type);
        long n = size + (size >> 12) + (size >> 14) + (size >> 25) + 256;
        if (n + 8192 > limit) throw new IOException("單一物件無法裝入有界傳輸 pack");
        if (bound + n + 8192 > limit || batch.size() >= 10000) {
          previous = stageBatch(batch, needed, previous, operation, part++, metadata);
          batch.clear();
          bound = 32;
        }
        batch.add(id);
        bound += n;
      }
      if (!batch.isEmpty()) stageBatch(batch, needed, previous, operation, part, metadata);
      // tag 物件不能放進 tree；其 target 已依相依順序送完，再以獨立的 staging ref 分批。
      for (String id : ordered)
        if (needed.get(id) == Constants.OBJ_TAG) {
          String ref = nextStageRef();
          if (localStage) installStage(ref, id);
          else publish(Map.of(ref, id), Map.of(ref, ""), true);
        }
    }
  }

  private String stageBatch(
      List<String> batch,
      Map<String, Integer> types,
      String previous,
      UUID operation,
      int part,
      CommitMetadata m)
      throws IOException {
    try (var inserter = repo.newObjectInserter()) {
      var entries = new TreeFormatter();
      var parents = new ArrayList<ObjectId>();
      if (previous != null) parents.add(ObjectId.fromString(previous));
      for (String id : batch) {
        int type = types.get(id);
        if (type == Constants.OBJ_COMMIT) parents.add(ObjectId.fromString(id));
      }
      // TreeFormatter 要求 git 的排序規則，hash 文字在 tree/file suffix 前已唯一。
      var sorted = new TreeMap<String, String>();
      for (String id : batch) if (types.get(id) != Constants.OBJ_COMMIT) sorted.put(id, id);
      entries = new TreeFormatter();
      for (String id : sorted.keySet())
        entries.append(
            id,
            types.get(id) == Constants.OBJ_TREE ? FileMode.TREE : FileMode.REGULAR_FILE,
            ObjectId.fromString(id));
      ObjectId batchTree = inserter.insert(entries);
      // 最新 boundary tree 必須保留所有已送批次。只有 parent chain 時，
      // JGit 不會排除更早各 parent 的樹，發布真正世界 root 時便重送整棵樹。
      String ref = nextStageRef();
      record Root(FileMode mode, ObjectId id) {}
      var roots = new TreeMap<String, Root>();
      if (previous != null)
        try (var walk = new RevWalk(repo);
            var reader = repo.newObjectReader()) {
          var tree = new CanonicalTreeParser();
          tree.reset(reader, walk.parseCommit(ObjectId.fromString(previous)).getTree());
          while (!tree.eof()) {
            roots.put(
                tree.getEntryPathString(),
                new Root(tree.getEntryFileMode(), tree.getEntryObjectId()));
            tree.next();
          }
        }
      roots.put("batch." + ref.substring(STAGES.length()), new Root(FileMode.TREE, batchTree));
      var root = new TreeFormatter();
      for (var entry : roots.entrySet())
        root.append(entry.getKey(), entry.getValue().mode(), entry.getValue().id());
      var c = new CommitBuilder();
      c.setTreeId(inserter.insert(root));
      c.setParentIds(parents);
      // 合成提交保持 parent 時間順序，避免額外的歷史排序成本。
      long epoch = java.time.Instant.now().getEpochSecond();
      try (var walk = new RevWalk(repo)) {
        for (var parent : parents)
          epoch = Math.max(epoch, (long) walk.parseCommit(parent).getCommitTime() + 1);
      }
      var person =
          new PersonIdent(
              m.author().name(),
              m.author().email(),
              java.time.Instant.ofEpochSecond(epoch),
              java.time.ZoneOffset.UTC);
      c.setAuthor(person);
      c.setCommitter(person);
      var stageMetadata =
          new CommitMetadata(
              m.author(),
              m.author(),
              m.message(),
              java.time.Instant.ofEpochSecond(epoch),
              m.mcDataVersion(),
              m.dimension(),
              m.source(),
              m.auto(),
              m.snapshot(),
              m.contributions());
      c.setMessage(CommitTrailers.message(stageMetadata));
      String id = inserter.insert(c).name();
      inserter.flush();
      var haves = localStage ? stagingAdvertisement : advertised(true);
      if (measure(List.of(id), haves.values()) > limit && batch.size() > 1) {
        int middle = batch.size() / 2;
        String next = stageBatch(batch.subList(0, middle), types, previous, operation, part, m);
        return stageBatch(batch.subList(middle, batch.size()), types, next, operation, part, m);
      }
      if (localStage) {
        installStage(ref, id);
      } else publish(Map.of(ref, id), Map.of(ref, ""), true);
      return id;
    }
  }

  private String nextStageRef() throws IOException {
    long sequence =
        (localStage ? localRefs() : advertised(true))
                .keySet().stream()
                    .filter(r -> r.startsWith(STAGES))
                    .map(r -> r.substring(STAGES.length()))
                    .filter(r -> r.matches("[0-9]{20}"))
                    .mapToLong(Long::parseLong)
                    .max()
                    .orElse(0)
            + 1;
    return STAGES + String.format(Locale.ROOT, "%020d", sequence);
  }

  private void installStage(String ref, String id) throws IOException {
    long size = measure(List.of(id), stagingAdvertisement.values());
    if (size > limit) throw new IOException("伺服器合併 pack 超過上限");
    var update = repo.updateRef(ref);
    update.setExpectedOldObjectId(ObjectId.zeroId());
    update.setNewObjectId(ObjectId.fromString(id));
    var result = update.update();
    if (result != RefUpdate.Result.NEW) throw new IOException("合併傳輸 ref 發布失敗");
    stagingAdvertisement.put(ref, id);
    sizes.add(new PackSize(size, null));
  }

  public void publish(Map<String, String> targets, Map<String, String> expected, boolean force)
      throws IOException {
    try (var t = open()) {
      var advertised = advertised(true);
      long bytes = measure(targets.values(), advertised.values());
      if (bytes > limit) throw new IOException("傳輸 pack 超過上限：" + bytes + "；請重新分批");
      var updates = new ArrayList<RemoteRefUpdate>();
      for (var e : targets.entrySet()) {
        String old = expected.get(e.getKey());
        ObjectId oldId =
            old == null ? null : old.isEmpty() ? ObjectId.zeroId() : ObjectId.fromString(old);
        updates.add(new RemoteRefUpdate(repo, e.getValue(), e.getKey(), force, null, oldId));
      }
      activeWire = null;
      var result = t.push(NullProgressMonitor.INSTANCE, updates);
      sizes.add(new PackSize(bytes, activeWire));
      for (var u : result.getRemoteUpdates())
        if (!Set.of(RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE)
            .contains(u.getStatus()))
          throw new IOException(
              "remote ref 更新失敗："
                  + u.getRemoteName()
                  + " "
                  + u.getStatus()
                  + " "
                  + secret.redact(u.getMessage()));
    } catch (Exception e) {
      throw safe(e);
    }
  }

  public void fetch(String source, String destination) throws IOException {
    var before = packFiles();
    var old = repo.exactRef(destination);
    try (var t = open()) {
      t.setCheckFetchedObjects(true);
      var r = t.fetch(NullProgressMonitor.INSTANCE, List.of(new RefSpec("+" + source)));
      for (var u : r.getTrackingRefUpdates())
        if (!Set.of(
                RefUpdate.Result.NEW,
                RefUpdate.Result.FAST_FORWARD,
                RefUpdate.Result.FORCED,
                RefUpdate.Result.NO_CHANGE)
            .contains(u.getResult())) throw new IOException("fetch ref 更新失敗");
      for (var p : packFiles())
        if (!before.contains(p)) {
          long size = Files.size(p);
          sizes.add(new PackSize(size, null));
          if (size > limit) {
            // 尚未發布任何 ref；丟棄超限檔案，既有分批 refs 仍指向完整的舊物件。
            String stem = p.getFileName().toString().replaceFirst("\\.pack$", "");
            for (String suffix : List.of(".pack", ".idx", ".keep", ".bitmap", ".rev"))
              Files.deleteIfExists(p.resolveSibling(stem + suffix));
            throw new IOException("遠端未分批，接收 pack 超過上限：" + size);
          }
        }
      var advertised = r.getAdvertisedRef(source);
      if (advertised == null || advertised.getObjectId() == null)
        throw new IOException("遠端 ref 不存在");
      var update = repo.updateRef(destination);
      update.setExpectedOldObjectId(old == null ? ObjectId.zeroId() : old.getObjectId());
      update.setNewObjectId(advertised.getObjectId());
      update.setForceUpdate(true);
      if (!Set.of(
              RefUpdate.Result.NEW,
              RefUpdate.Result.FAST_FORWARD,
              RefUpdate.Result.FORCED,
              RefUpdate.Result.NO_CHANGE)
          .contains(update.update())) throw new IOException("fetch ref lease 已改變");
    } catch (Exception e) {
      throw safe(e);
    }
  }

  public void fetchStages(String prefix) throws IOException {
    for (var e : advertised(false).entrySet())
      if (e.getKey().startsWith(STAGES)) {
        String dest = prefix + e.getKey().substring(5);
        var existing = repo.exactRef(dest);
        if (existing == null || !existing.getObjectId().name().equals(e.getValue()))
          fetch(e.getKey(), dest);
      }
  }

  private Set<Path> packFiles() throws IOException {
    Path dir = repo.getDirectory().toPath().resolve("objects/pack");
    var out = new HashSet<Path>();
    if (Files.isDirectory(dir))
      try (var stream = Files.list(dir)) {
        stream.filter(p -> p.toString().endsWith(".pack")).forEach(out::add);
      }
    return out;
  }

  private Long activeWire;

  /** 每個 HTTP POST tee 至短命檔案；解析 gzip request，PACK 到 request 結尾即實際 pack bytes。 */
  private final class MeasuredHttp implements HttpConnectionFactory {
    private final HttpConnectionFactory delegate;

    MeasuredHttp(HttpConnectionFactory delegate) {
      this.delegate = delegate;
    }

    @Override
    public HttpConnection create(URL u) throws IOException {
      return wrap(delegate.create(u));
    }

    @Override
    public HttpConnection create(URL u, java.net.Proxy p) throws IOException {
      return wrap(delegate.create(u, p));
    }

    private HttpConnection wrap(HttpConnection c) {
      var state =
          new Object() {
            Path spool;
            OutputStream out;
            String encoding;
            boolean measured;
          };
      return (HttpConnection)
          java.lang.reflect.Proxy.newProxyInstance(
              HttpConnection.class.getClassLoader(),
              new Class<?>[] {HttpConnection.class},
              (p, m, args) -> {
                try {
                  if (m.getName().equals("setRequestProperty")
                      && args[0].toString().equalsIgnoreCase("Content-Encoding"))
                    state.encoding = args[1].toString();
                  if (m.getName().equals("getOutputStream")) {
                    var original = (OutputStream) m.invoke(c, args);
                    state.spool =
                        Files.createTempFile(repo.getDirectory().toPath(), "wire-", ".tmp");
                    spools.add(state.spool);
                    state.out = new BufferedOutputStream(Files.newOutputStream(state.spool));
                    return new FilterOutputStream(original) {
                      @Override
                      public void write(int b) throws IOException {
                        out.write(b);
                        state.out.write(b);
                      }

                      @Override
                      public void write(byte[] b, int o, int l) throws IOException {
                        out.write(b, o, l);
                        state.out.write(b, o, l);
                      }

                      @Override
                      public void close() throws IOException {
                        try {
                          super.close();
                        } finally {
                          state.out.close();
                        }
                      }
                    };
                  }
                  if (m.getName().equals("getResponseCode")
                      && state.spool != null
                      && !state.measured) {
                    state.measured = true;
                    state.out.close();
                    try (var raw = new BufferedInputStream(Files.newInputStream(state.spool));
                        var in = "gzip".equals(state.encoding) ? new GZIPInputStream(raw) : raw) {
                      long n = 0;
                      boolean pack = false;
                      while (true) {
                        byte[] header = in.readNBytes(4);
                        if (header.length < 4) break;
                        int length;
                        try {
                          length =
                              Integer.parseInt(
                                  new String(header, java.nio.charset.StandardCharsets.US_ASCII),
                                  16);
                        } catch (NumberFormatException invalid) {
                          break;
                        }
                        if (length == 0) {
                          pack = Arrays.equals(in.readNBytes(4), new byte[] {'P', 'A', 'C', 'K'});
                          if (pack) n = 4;
                          break;
                        }
                        if (length < 4 || length > 65520) break;
                        in.skipNBytes(length - 4);
                      }
                      if (pack) {
                        byte[] buffer = new byte[65536];
                        int count;
                        while ((count = in.read(buffer)) != -1) n += count;
                      }
                      if (pack) {
                        activeWire = n;
                        if (n > limit) throw new IOException("實際傳輸 pack 超過上限：" + n);
                      }
                    } finally {
                      Files.deleteIfExists(state.spool);
                    }
                  }
                  return m.invoke(c, args);
                } catch (InvocationTargetException e) {
                  throw e.getCause();
                }
              });
    }
  }

  @Override
  public void close() {
    repo.close();
    for (Path spool : spools)
      try {
        Files.deleteIfExists(spool);
      } catch (IOException ignored) {
      }
  }
}
