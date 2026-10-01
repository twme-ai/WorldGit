package org.worldgit.cli;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import org.worldgit.core.anvil.*;
import org.worldgit.core.capture.ScanIndex;
import org.worldgit.core.config.*;
import org.worldgit.core.diff.*;
import org.worldgit.core.model.*;
import org.worldgit.core.service.*;
import org.worldgit.core.store.*;
import org.worldgit.platform.*;
import org.worldgit.protocol.*;
import picocli.CommandLine;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

@Command(
    name = "wgit",
    mixinStandardHelpOptions = true,
    version = "WorldGit 0.1.0",
    description = "Minecraft 世界的離線版本控制",
    subcommands = {
      Wgit.Init.class,
      Wgit.Status.class,
      Wgit.Commit.class,
      Wgit.Log.class,
      Wgit.Diff.class
    })
public final class Wgit implements Runnable {
  enum Color {
    auto,
    always,
    never
  }

  enum Format {
    text,
    json
  }

  @Option(
      names = {"--world", "-w"},
      scope = ScopeType.INHERIT,
      description = "世界資料夾，或包含 world 的伺服器資料夾")
  Path world = Path.of(".");

  @Option(
      names = "--dimension",
      scope = ScopeType.INHERIT,
      description = "僅操作指定維度（例如 minecraft:the_nether）")
  String dimension;

  @Option(
      names = "--color",
      scope = ScopeType.INHERIT,
      defaultValue = "auto",
      description = "auto|always|never")
  Color color;

  @Option(
      names = "--format",
      scope = ScopeType.INHERIT,
      defaultValue = "text",
      description = "text|json")
  Format format;

  @Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  public static void main(String[] args) {
    System.exit(
        execute(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true)));
  }

  public static int execute(String[] args, PrintWriter out, PrintWriter err) {
    var cmd = new CommandLine(new Wgit());
    cmd.setOut(out);
    cmd.setErr(err);
    cmd.setExecutionExceptionHandler(
        (ex, line, result) -> {
          line.getErr().println("wgit：" + error(ex));
          return 1;
        });
    return cmd.execute(args);
  }

  private DimensionId selected() {
    return dimension == null ? null : new DimensionId(dimension);
  }

  private static String error(Exception ex) {
    return ex.getMessage() == null || ex.getMessage().isBlank()
        ? ex.getClass().getSimpleName()
        : ex.getMessage();
  }

  private WorldLayout layout() throws IOException {
    return WorldLayout.discover(world);
  }

  private WorldRepositories repositories(WorldLayout layout) {
    return new WorldRepositories(
        layout, dim -> new OfflineWorld(layout, dim, s -> spec.commandLine().getErr().println(s)));
  }

  private WorldGitConfig.Local local(WorldRepositories repos) throws IOException {
    return WorldGitConfig.readLocal(repos.root().resolve("worldgit.yml"));
  }

  private SortedMap<DimensionId, Path> selectedRepos(WorldRepositories repos) throws IOException {
    var tracked = repos.tracked();
    DimensionId selected = selected();
    if (selected != null) {
      Path path = tracked.get(selected);
      if (path == null) throw new IOException("維度尚未 init：" + selected);
      tracked.clear();
      tracked.put(selected, path);
    }
    if (tracked.isEmpty()) throw new IOException("世界尚未 init");
    return tracked;
  }

  private CommitMetadata.Identity identity() {
    String name = System.getenv("GIT_AUTHOR_NAME"), email = System.getenv("GIT_AUTHOR_EMAIL");
    if (name == null || name.isBlank()) name = System.getProperty("user.name", "WorldGit");
    if (email == null || email.isBlank()) email = "worldgit@localhost";
    return new CommitMetadata.Identity(name, email);
  }

  private boolean colors() {
    return color != Color.never
        && System.getenv("NO_COLOR") == null
        && (color == Color.always || System.console() != null);
  }

  private String colored(ChangeKind kind, String text, WorldGitConfig.Local config) {
    if (!colors()) return text;
    int rgb =
        (config.palette().equals("colorblind") ? DiffPalette.COLORBLIND : DiffPalette.DEFAULT)
            .rgb(kind);
    return "\033[38;2;"
        + ((rgb >> 16) & 255)
        + ";"
        + ((rgb >> 8) & 255)
        + ";"
        + (rgb & 255)
        + "m"
        + text
        + "\033[0m";
  }

  private void json(Object value) throws IOException {
    var module = new SimpleModule();
    module.addSerializer(
        ChangeKind.class,
        new JsonSerializer<>() {
          @Override
          public void serialize(ChangeKind k, JsonGenerator gen, SerializerProvider provider)
              throws IOException {
            gen.writeString(k.wireName());
          }
        });
    module.addSerializer(
        DimensionId.class,
        new JsonSerializer<>() {
          @Override
          public void serialize(DimensionId id, JsonGenerator gen, SerializerProvider provider)
              throws IOException {
            gen.writeString(id.value());
          }
        });
    module.addSerializer(
        EntitySnapshot.class,
        new JsonSerializer<>() {
          @Override
          public void serialize(EntitySnapshot e, JsonGenerator gen, SerializerProvider provider)
              throws IOException {
            gen.writeStartObject();
            gen.writeStringField("uuid", e.uuid().toString());
            gen.writeStringField("type", e.data().string("id"));
            gen.writeObjectField("position", e.position());
            gen.writeBinaryField("nbt", e.bytes());
            gen.writeEndObject();
          }
        });
    spec.commandLine()
        .getOut()
        .println(
            new ObjectMapper()
                .registerModule(module)
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(value));
  }

  private void printDiff(WorldDiff diff, WorldGitConfig.Local local, boolean blocks) {
    PrintWriter out = spec.commandLine().getOut();
    var counts = diff.counts();
    out.printf(
        "%s：%d chunks，%d sections，%s %s %s %s；實體 %d，biome %d，metadata %d%n",
        diff.dimension(),
        diff.chunks().size(),
        diff.sections().size(),
        colored(ChangeKind.ADDED, "+" + counts.added(), local),
        colored(ChangeKind.REMOVED, "-" + counts.removed(), local),
        colored(ChangeKind.MODIFIED, "~" + counts.modified(), local),
        colored(ChangeKind.CONFLICT, "!" + counts.conflict(), local),
        diff.entities().size(),
        diff.biomes().size(),
        diff.metadata().size());
    for (var s : diff.sections()) {
      var c = s.counts();
      out.printf(
          "  c.%d.%d s.%d  %s %s %s %s%n",
          s.chunk().x(),
          s.chunk().z(),
          s.sectionY(),
          colored(ChangeKind.ADDED, "+" + c.added(), local),
          colored(ChangeKind.REMOVED, "-" + c.removed(), local),
          colored(ChangeKind.MODIFIED, "~" + c.modified(), local),
          colored(ChangeKind.CONFLICT, "!" + c.conflict(), local));
      if (blocks)
        for (var b : s.blocks())
          out.println(
              colored(
                  b.kind(),
                  "    "
                      + DiffPalette.symbol(b.kind())
                      + " ("
                      + b.pos().x()
                      + ","
                      + b.pos().y()
                      + ","
                      + b.pos().z()
                      + ") "
                      + b.before().canonical()
                      + " → "
                      + b.after().canonical()
                      + (Objects.equals(b.blockEntityBefore(), b.blockEntityAfter())
                          ? ""
                          : " [block entity]"),
                  local));
    }
    for (var e : diff.entities())
      out.println(
          colored(
              e.kind(),
              "  "
                  + DiffPalette.symbol(e.kind())
                  + " entity "
                  + e.uuid()
                  + " "
                  + (e.after() != null ? e.after() : e.before()).data().string("id"),
              local));
    for (var m : diff.metadata())
      out.println(colored(m.kind(), "  " + DiffPalette.symbol(m.kind()) + " " + m.path(), local));
    if (blocks)
      for (var b : diff.biomes())
        out.println(
            colored(
                b.kind(),
                "  "
                    + DiffPalette.symbol(b.kind())
                    + " biome "
                    + b.chunk()
                    + " s."
                    + b.sectionY()
                    + " sample="
                    + b.sampleIndex()
                    + " "
                    + b.before()
                    + " → "
                    + b.after(),
                local));
  }

  private void warnings(List<String> messages) {
    messages.forEach(s -> spec.commandLine().getErr().println("提示：" + s));
  }

  private int printBatch(WorldRepositories.Batch<DimensionRepository.CommitResult> batch)
      throws IOException {
    if (format == Format.json) json(batch);
    else {
      spec.commandLine().getOut().println("snapshot " + batch.snapshot());
      for (var e : batch.dimensions().entrySet()) {
        var outcome = e.getValue();
        if (outcome.success()) {
          var value = outcome.value();
          spec.commandLine()
              .getOut()
              .println(e.getKey() + " " + (value.changed() ? value.commit() : "沒有變動"));
          warnings(value.status().warnings());
        } else spec.commandLine().getErr().println(e.getKey() + " 失敗：" + outcome.error());
      }
    }
    return batch.success() ? 0 : 1;
  }

  abstract static class Subcommand implements Callable<Integer> {
    @ParentCommand Wgit root;
  }

  @Command(name = "init", mixinStandardHelpOptions = true, description = "初始化維度 repo 並建立第一次完整快照")
  static final class Init extends Subcommand {
    enum Template {
      creative,
      survival
    }

    enum Track {
      all,
      modified_only
    }

    @Option(names = "--template", defaultValue = "creative")
    Template template;

    @Option(names = "--track", defaultValue = "all", description = "all|modified-only（後者目前只記錄設定）")
    String track;

    @Override
    public Integer call() throws Exception {
      WorldGitConfig.Repo config = WorldGitConfig.readRepo("track: " + track, "--track");
      var layout = root.layout();
      var repos = root.repositories(layout);
      try (var guard = SessionGuard.acquire(layout)) {
        return root.printBatch(
            repos.init(root.selected(), template.name(), config.track(), root.identity()));
      }
    }
  }

  @Command(name = "status", mixinStandardHelpOptions = true, description = "顯示未提交變動；使用離線 index 快取")
  static final class Status extends Subcommand {
    @Option(names = "--full", description = "重新雜湊所有 chunk")
    boolean full;

    @Override
    public Integer call() throws Exception {
      var layout = root.layout();
      var repos = root.repositories(layout);
      var local = root.local(repos);
      try (var guard = SessionGuard.acquire(layout)) {
        var batch = repos.status(root.selected(), local.entityTolerance(), full);
        if (root.format == Format.json) root.json(batch);
        else
          for (var e : batch.dimensions().entrySet()) {
            if (e.getValue().success()) {
              var s = e.getValue().value();
              root.printDiff(s.diff(), local, false);
              root.spec
                  .commandLine()
                  .getOut()
                  .printf("  candidates=%d payloads-read=%d%n", s.candidates(), s.payloadsRead());
              root.warnings(s.warnings());
            } else
              root.spec.commandLine().getErr().println(e.getKey() + " 失敗：" + e.getValue().error());
          }
        return batch.success() ? 0 : 1;
      }
    }
  }

  @Command(
      name = "commit",
      mixinStandardHelpOptions = true,
      description = "提交世界變動；沒有變動的維度不產生 commit")
  static final class Commit extends Subcommand {
    @Option(
        names = {"-m", "--message"},
        required = true)
    String message;

    @Override
    public Integer call() throws Exception {
      var layout = root.layout();
      var repos = root.repositories(layout);
      var local = root.local(repos);
      try (var guard = SessionGuard.acquire(layout)) {
        return root.printBatch(
            repos.commit(root.selected(), message, root.identity(), local.entityTolerance()));
      }
    }
  }

  @Command(name = "log", mixinStandardHelpOptions = true, description = "按 snapshot 分組的歷史")
  static final class Log extends Subcommand {
    @Option(
        names = {"-n", "--max-count"},
        defaultValue = "20")
    int limit;

    @Override
    public Integer call() throws Exception {
      if (limit < 1 || limit > 10000) throw new IOException("max-count 必須介於 1–10000");
      var layout = root.layout();
      var repos = root.repositories(layout);
      var history = new ArrayList<Map<String, Object>>();
      try (var guard = SessionGuard.acquire(layout)) {
        var groups = new HashMap<UUID, List<RefStore.Commit>>();
        for (var e : root.selectedRepos(repos).entrySet())
          try (var repo = new DimensionRepository(e.getValue(), e.getKey(), false)) {
            for (var c : repo.log(limit))
              groups.computeIfAbsent(c.metadata().snapshot(), k -> new ArrayList<>()).add(c);
          }
        var sorted = new ArrayList<>(groups.entrySet());
        sorted.sort(
            Comparator.comparing(
                    (Map.Entry<UUID, List<RefStore.Commit>> e) ->
                        e.getValue().stream()
                            .map(c -> c.metadata().time())
                            .max(Comparator.naturalOrder())
                            .orElseThrow())
                .reversed());
        for (var e : sorted.subList(0, Math.min(limit, sorted.size()))) {
          var commits = e.getValue();
          commits.sort(Comparator.comparing(c -> c.metadata().dimension()));
          var first = commits.getFirst();
          var m = first.metadata();
          var dimensions = new TreeMap<String, String>();
          commits.forEach(c -> dimensions.put(c.metadata().dimension().value(), c.id()));
          history.add(
              Map.of(
                  "snapshot",
                  e.getKey().toString(),
                  "time",
                  m.time().toString(),
                  "author",
                  m.author().git(),
                  "message",
                  m.message(),
                  "auto",
                  m.auto(),
                  "dimensions",
                  dimensions));
        }
        if (root.format == Format.json) root.json(history);
        else
          for (var row : history) {
            root.spec
                .commandLine()
                .getOut()
                .println(
                    row.get("snapshot")
                        + " "
                        + row.get("time")
                        + " "
                        + row.get("author")
                        + " "
                        + row.get("message"));
            root.spec.commandLine().getOut().println("  " + row.get("dimensions"));
          }
      }
      return 0;
    }
  }

  @Command(
      name = "diff",
      mixinStandardHelpOptions = true,
      description = "無參數：HEAD → 世界；一參數：該 commit → 世界；兩參數：commit → commit")
  static final class Diff extends Subcommand {
    @Parameters(arity = "0..2", paramLabel = "<revision>")
    List<String> revisions = new ArrayList<>();

    @Option(names = "--blocks", description = "列出逐格方塊與 biome 差異")
    boolean blocks;

    @Override
    public Integer call() throws Exception {
      var layout = root.layout();
      var repos = root.repositories(layout);
      var local = root.local(repos);
      var output = new TreeMap<String, Object>();
      boolean ok = true;
      try (var guard = SessionGuard.acquire(layout)) {
        for (var e : root.selectedRepos(repos).entrySet())
          try (var repo = new DimensionRepository(e.getValue(), e.getKey(), false)) {
            WorldDiff diff;
            if (revisions.size() == 2)
              diff =
                  repo.diff(
                      revisions.get(0),
                      revisions.get(1),
                      local.entityTolerance(),
                      blocks ? DiffEngine.Detail.BLOCKS : DiffEngine.Detail.SUMMARY);
            else {
              try (var source =
                  new OfflineWorld(layout, layout.dimensions().get(e.getKey()), s -> {})) {
                var status = repo.status(source, repos.manifest(), local.entityTolerance(), false);
                root.warnings(status.warnings());
                String tree = ScanIndex.read(e.getValue().resolve("worldgit.index")).tree();
                String before =
                    revisions.isEmpty()
                        ? repo.refs().head()
                        : repo.refs().resolve(revisions.getFirst());
                String beforeTree = before == null ? null : repo.refs().readCommit(before).tree();
                diff =
                    new DiffEngine(repo.objects())
                        .compare(
                            e.getKey(),
                            beforeTree,
                            tree,
                            local.entityTolerance(),
                            blocks ? DiffEngine.Detail.BLOCKS : DiffEngine.Detail.SUMMARY);
              }
            }
            output.put(e.getKey().value(), diff);
            if (root.format == Format.text) root.printDiff(diff, local, blocks);
          } catch (Exception ex) {
            ok = false;
            output.put(e.getKey().value(), Map.of("error", error(ex)));
            if (root.format == Format.text)
              root.spec.commandLine().getErr().println(e.getKey() + " 失敗：" + error(ex));
          }
      }
      if (root.format == Format.json) root.json(output);
      return ok ? 0 : 1;
    }
  }
}
