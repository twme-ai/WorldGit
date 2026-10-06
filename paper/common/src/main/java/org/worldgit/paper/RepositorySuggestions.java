package org.worldgit.paper;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.worldgit.core.anvil.WorldLayout;
import org.worldgit.core.remote.RemoteSpec;
import org.worldgit.core.service.*;
import org.worldgit.core.store.JGitStore;
import static org.worldgit.paper.CommandSuggestions.*;

/** 只用 readOnly store；不取會建立 lock 檔的 DimensionRepository，不保存 config、不復原 journal。 */
final class RepositorySuggestions {
  static List<Entry> read(WorldLayout layout, Kind kind, Map<String, String> defaults) throws IOException {return read(layout,null,kind,defaults);}
  static List<Entry> read(WorldLayout layout, org.worldgit.core.model.DimensionId dimension,Kind kind,Map<String,String> defaults) throws IOException {
    if (kind == Kind.REMOTES) return remotes(dimension==null?layout.repositoryRoot():layout.repository(dimension), defaults);
    if (kind == Kind.STASHES) return stashes(dimension==null?layout.repositoryRoot():layout.repository(dimension));
    var tracked = new WorldRepositories(layout).tracked();
    if (kind == Kind.DIMENSIONS) return layout.dimensions().keySet().stream().limit(256)
        .map(id -> Entry.of(id.value(), "paper.command.tip.dimension", "dimension", id.value())).toList();
    if(dimension!=null)tracked.keySet().removeIf(id->!id.equals(dimension));
    var rows = new LinkedHashMap<String, Entry>();
    for (var path : tracked.values()) {
      try (var repository = new FileRepository(path.toFile()); var store = JGitStore.readOnly(repository)) {
        for (var branch : store.branches().entrySet()) {
          if (rows.size() >= 256) break;
          var commit = store.readCommit(branch.getValue());
          rows.putIfAbsent(branch.getKey(), revision(branch.getKey(), commit.id(), commit.metadata().message()));
        }
        if (kind != Kind.REVISIONS) continue;
        for (var tag : store.refsByPrefix("refs/tags/").entrySet()) {
          if (rows.size() >= 256) break;
          String name = tag.getKey().substring("refs/tags/".length());
          try { var commit = store.readCommit(store.resolve(name)); rows.putIfAbsent(name, revision(name, commit.id(), commit.metadata().message())); }
          catch (IOException ignored) { }
        }
        var log = store.log(20);
        for (int i = 0; i < log.size() && rows.size() < 256; i++) {
          var commit = log.get(i);
          String hash = Messages.shortId(commit.id()); rows.putIfAbsent(hash, revision(hash, commit.id(), commit.metadata().message()));
        }
        for (int i = 0; i < 10 && rows.size() < 256; i++) {
          String head = i == 0 ? "HEAD" : "HEAD~" + i;
          try {
            var commit = store.readCommit(store.resolve(head));
            rows.putIfAbsent(head, revision(head, commit.id(), commit.metadata().message()));
          } catch (IOException error) { break; }
        }
      }
    }
    return List.copyOf(rows.values());
  }
  private static Entry revision(String value, String id, String message) {
    return Entry.of(value, "paper.command.tip.revision", "hash", Messages.shortId(id), "message", plain(message, 160));
  }
  static List<Entry> stashes(Path root) throws IOException {
    var rows = OperationState.read(root.resolve("stash.yml")); var out = new ArrayList<Entry>();
    if (!(rows.get("entries") instanceof List<?> entries)) return List.of();
    for (int i = 0; i < Math.min(256, entries.size()); i++) {
      if (entries.get(i) instanceof Map<?, ?> row) out.add(Entry.of(Integer.toString(i), "paper.command.tip.stash", "message", plain(String.valueOf(row.get("message")), 160)));
    }
    return List.copyOf(out);
  }
  /** 不解析 manifest+ URL（那會額外下載）；只讀已存 sidecar 的文字，完全不開 WorldRemotes。 */
  static Map<String, String> remoteUrls(Path root, Map<String, String> defaults) throws IOException {
    var document = OperationState.read(root.resolve("remotes.yml")); var out = new TreeMap<>(defaults);
    if (document.get("remotes") instanceof Map<?, ?> remotes) for (var item : remotes.entrySet()) {
      if (out.size() >= 256) break;
      if (item.getKey() instanceof String name && item.getValue() instanceof Map<?, ?> row) {
        RemoteSpec.validateName(name);
        if (row.get("url") instanceof String url) out.put(name, safeUrl(url));
        else if (row.containsKey("dimensions")) out.put(name, "manifest");
      }
    }
    return Collections.unmodifiableMap(out);
  }
  static List<Entry> remotes(Path root, Map<String, String> defaults) throws IOException {
    var rows = remoteUrls(root, defaults);
    return rows.entrySet().stream().limit(256).map(row -> Entry.of(row.getKey(), "paper.command.tip.remote", "url", safeUrl(row.getValue()))).toList();
  }
  static String safeUrl(String value) {
    if (value.equals("manifest")) return value;
    try { RemoteSpec.validateUrl(value.replace("manifest+", "").replace("{dimension}", "minecraft.overworld")); return plain(value, 256); }
    catch (IOException | RuntimeException error) { return "[URL]"; }
  }
  private RepositorySuggestions() { }
}
