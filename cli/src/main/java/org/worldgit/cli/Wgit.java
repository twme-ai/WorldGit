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
      Wgit.Diff.class,
      Wgit.Restore.class,
      Wgit.Switch.class,
      Wgit.Branch.class,
      Wgit.Reset.class,
      Wgit.StashCommand.class,
      Wgit.Verify.class,
      Wgit.Merge.class,
      Wgit.Conflicts.class,
      Wgit.Resolve.class,
      Wgit.Revert.class,
      Wgit.CherryPick.class
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
        org.worldgit.core.merge.MergeReport.Choice.class,
        new JsonSerializer<>() {
          @Override
          public void serialize(
              org.worldgit.core.merge.MergeReport.Choice value,
              JsonGenerator gen,
              SerializerProvider provider)
              throws IOException {
            gen.writeString(value.name().toLowerCase(Locale.ROOT));
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
        var merging =
            org.worldgit.core.merge.MergeState.read(repos.root().resolve("merge-state.bin"));
        if (root.format == Format.json) {
          if (merging == null) root.json(batch);
          else
            root.json(
                Map.of(
                    "status",
                    batch,
                    "state",
                    "MERGING",
                    "mode",
                    merging.mode(),
                    "source",
                    merging.source(),
                    "remaining",
                    merging.remaining(),
                    "regions",
                    merging.regions()));
        } else {
          if (merging != null)
            root.spec
                .commandLine()
                .getOut()
                .println(
                    "MERGING："
                        + merging.mode()
                        + " "
                        + merging.source()
                        + "，剩 "
                        + merging.remaining()
                        + " 個衝突");
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
      if (Files.exists(repos.root().resolve("merge-state.bin"))) {
        if (root.dimension != null) throw new IOException("合併提交必須包含所有維度");
        try (var ops = new WorldOperations(layout)) {
          return root.printMerge(
              ops.commitMerge(root.identity(), CommitMetadata.Source.CLI, message, false));
        }
      }
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

  private int printApply(WorldOperations.Result result) throws IOException {
    if (format == Format.json) json(result);
    else {
      var config = WorldGitConfig.readLocal(layout().repositoryRoot().resolve("worldgit.yml"));
      spec.commandLine().getOut().println(result.state());
      for (var entry : result.dimensions().entrySet()) {
        var stats = entry.getValue();
        spec.commandLine()
            .getOut()
            .printf(
                "%s：%s，biome %d，%s，%s，untracked 保留 %d，metadata %d%n",
                entry.getKey(),
                colored(
                    ChangeKind.MODIFIED,
                    "~ chunks=" + stats.chunks() + " sections=" + stats.sections(),
                    config),
                stats.biomeSections(),
                colored(ChangeKind.ADDED, "+ entities=" + stats.entityPuts(), config),
                colored(
                    ChangeKind.REMOVED,
                    "- entities=" + stats.entityRemoves() + " chunks=" + stats.chunkDeletes(),
                    config),
                stats.untrackedKept(),
                stats.metaFiles());
      }
      if (result.error() != null) spec.commandLine().getErr().println("PARTIAL：" + result.error());
    }
    return result.success() ? 0 : 1;
  }

  private void requireWholeGroup() throws IOException {
    if (dimension != null)
      throw new IOException(
          "branch、switch、reset、stash 必須對所有維度同步；--dimension 僅適用 restore／verify 與讀取指令。");
  }

  abstract static class ApplyCommand extends Subcommand {
    @Option(names = "--dry-run", description = "只列出預計改動的 section／chunk 數")
    boolean dryRun;
  }

  abstract static class RangeCommand extends ApplyCommand {
    @Option(names = "--chunks", description = "chunk 中心 x,z,半徑（正方形，含端點）")
    String chunks;

    @Option(names = "--box", description = "方塊 x1,y1,z1,x2,y2,z2（含端點）")
    String box;

    org.worldgit.core.apply.Scope range() throws IOException {
      if (chunks != null && box != null) throw new IOException("--chunks 與 --box 不可同時指定");
      try {
        if (chunks != null) {
          int[] c = coordinates(chunks, 3);
          return org.worldgit.core.apply.Scope.chunkRadius(c[0], c[1], c[2]);
        }
        if (box != null) {
          int[] b = coordinates(box, 6);
          return org.worldgit.core.apply.Scope.box(b[0], b[1], b[2], b[3], b[4], b[5]);
        }
        return org.worldgit.core.apply.Scope.all();
      } catch (IllegalArgumentException ex) {
        throw new IOException("範圍座標無效：" + ex.getMessage(), ex);
      }
    }

    private static int[] coordinates(String text, int count) {
      String[] parts = text.split(",");
      if (parts.length != count) throw new IllegalArgumentException("需要 " + count + " 個逗號分隔整數");
      return Arrays.stream(parts).map(String::trim).mapToInt(Integer::parseInt).toArray();
    }
  }

  @Command(name = "restore", mixinStandardHelpOptions = true, description = "還原指定快照／範圍，不移動 HEAD")
  static final class Restore extends RangeCommand {
    @Parameters(index = "0")
    String revision;

    @Option(names = "--delete-untracked", description = "刪除目標沒有的整個 chunk（預設保留）")
    boolean delete;

    @Override
    public Integer call() throws Exception {
      try (var operations = new WorldOperations(root.layout())) {
        return root.printApply(
            operations.restore(revision, root.selected(), range(), dryRun, delete));
      }
    }
  }

  @Command(
      name = "switch",
      mixinStandardHelpOptions = true,
      description = "所有維度原地切換；commit 會進入 detached HEAD")
  static final class Switch extends ApplyCommand {
    @Parameters(index = "0")
    String revision;

    @Option(names = "--stash")
    boolean stash;

    @Option(names = "--force")
    boolean force;

    @Option(names = "--delete-untracked")
    boolean delete;

    @Override
    public Integer call() throws Exception {
      root.requireWholeGroup();
      try (var operations = new WorldOperations(root.layout())) {
        return root.printApply(operations.switchTo(revision, stash, force, dryRun, delete));
      }
    }
  }

  @Command(name = "branch", mixinStandardHelpOptions = true, description = "同步列出／建立／刪除各維度同名分支")
  static final class Branch extends Subcommand {
    @Parameters(index = "0", arity = "0..1")
    String name;

    @Parameters(index = "1", arity = "0..1")
    String start;

    @Option(names = "-d")
    boolean delete;

    @Override
    public Integer call() throws Exception {
      root.requireWholeGroup();
      try (var operations = new WorldOperations(root.layout())) {
        if (name != null) {
          if (delete) {
            if (start != null) throw new IOException("刪除分支不接受 start");
            operations.deleteBranch(name);
          } else operations.createBranch(name, start);
        } else if (delete) throw new IOException("-d 需要分支名稱");
        var branches = operations.branches();
        if (root.format == Format.json) root.json(branches);
        else
          for (var branch : branches)
            root.spec
                .commandLine()
                .getOut()
                .println(
                    (branch.current() ? "* " : "  ")
                        + branch.name()
                        + " "
                        + branch.commits()
                        + (branch.consistent() ? "" : " [PARTIAL]"));
        return 0;
      }
    }
  }

  @Command(
      name = "reset",
      mixinStandardHelpOptions = true,
      description = "全範圍還原；有 commit 參數時改寫歷史（禁止對已 push 歷史使用）")
  static final class Reset extends ApplyCommand {
    @Option(names = "--hard", required = true)
    boolean hard;

    @Option(names = "--force")
    boolean force;

    @Parameters(arity = "0..1")
    String revision;

    @Override
    public Integer call() throws Exception {
      root.requireWholeGroup();
      try (var operations = new WorldOperations(root.layout())) {
        return root.printApply(operations.resetHard(revision, force, dryRun));
      }
    }
  }

  @Command(
      name = "stash",
      mixinStandardHelpOptions = true,
      description = "多維度 stash push/pop/list/drop")
  static final class StashCommand extends ApplyCommand {
    enum Action {
      push,
      pop,
      list,
      drop
    }

    @Parameters(index = "0", defaultValue = "push", arity = "0..1")
    Action action;

    @Parameters(index = "1", defaultValue = "0", arity = "0..1", description = "stash 索引，0 為最新")
    int index;

    @Option(names = {"-m", "--message"})
    String message;

    @Override
    public Integer call() throws Exception {
      root.requireWholeGroup();
      try (var operations = new WorldOperations(root.layout())) {
        if (action == Action.push) return root.printApply(operations.stashPush(message, dryRun));
        if (action == Action.pop) return root.printApply(operations.stashPop(index, dryRun));
        if (action == Action.drop) {
          if (dryRun) throw new IOException("stash drop 不支援 --dry-run；請用 stash list");
          operations.stashDrop(index);
        }
        var stashes = operations.stashes();
        if (root.format == Format.json) root.json(stashes);
        else
          for (int i = 0; i < stashes.size(); i++)
            root.spec
                .commandLine()
                .getOut()
                .println(
                    "stash@{"
                        + i
                        + "} "
                        + stashes.get(i).id()
                        + " "
                        + stashes.get(i).time()
                        + " "
                        + stashes.get(i).message());
        return 0;
      }
    }
  }

  @Command(
      name = "verify",
      mixinStandardHelpOptions = true,
      description = "全量離線掃描世界與目標 tree；0 差異回傳 0")
  static final class Verify extends RangeCommand {
    @Parameters(defaultValue = "HEAD", arity = "0..1")
    String revision;

    @Override
    public Integer call() throws Exception {
      try (var operations = new WorldOperations(root.layout())) {
        return root.printApply(
            operations.verify(
                revision,
                root.selected(),
                range(),
                range().kind() == org.worldgit.core.apply.Scope.Kind.ALL));
      }
    }
  }

  private int printMerge(WorldOperations.MergeResult result) throws IOException {
    if (format == Format.json) {
      var value = new LinkedHashMap<String, Object>();
      value.put("state", result.state());
      value.put("reports", result.reports());
      value.put("remaining", result.merging() == null ? 0 : result.merging().remaining());
      value.put("commits", result.commits());
      value.put("error", result.error());
      var plans = new TreeMap<DimensionId, org.worldgit.core.apply.ApplyPlan.Stats>();
      result.plans().forEach((id, p) -> plans.put(id, p.stats()));
      value.put("plans", plans);
      json(value);
    } else {
      var out = spec.commandLine().getOut();
      out.println(
          result.state()
              + "：剩餘衝突 "
              + (result.merging() == null ? 0 : result.merging().remaining()));
      var local = local(new WorldRepositories(layout()));
      for (var e : result.reports().entrySet()) {
        var report = e.getValue();
        out.println(
            e.getKey()
                + "：自動合併 "
                + report.automaticallyMergedSections()
                + " sections，交界提示（維持儲存的方塊狀態，可自行檢查） "
                + report.updateShapes().size()
                + " 格");
        printRegions(report.regions(), local);
        for (var d : report.ruleDifferences()) {
          out.println("  .wgignore ours → theirs：");
          for (var line : org.worldgit.core.merge.IgnoreRuleMerge.difference(d.ours(), d.theirs()))
            out.println(
                colored(
                    line.kind(),
                    "    " + DiffPalette.symbol(line.kind()) + " " + line.text(),
                    local));
          out.println("  .wgignore ours → 合併結果：");
          for (var line : org.worldgit.core.merge.IgnoreRuleMerge.difference(d.ours(), d.merged()))
            out.println(
                colored(
                    line.kind(),
                    "    " + DiffPalette.symbol(line.kind()) + " " + line.text(),
                    local));
        }
        warnings(report.warnings());
      }
      result.commits().forEach((id, c) -> out.println(id + " " + c));
      if (result.error() != null) spec.commandLine().getErr().println(result.error());
    }
    return result.success() ? 0 : 1;
  }

  private void printRegions(
      List<org.worldgit.core.merge.MergeReport.Region> regions, WorldGitConfig.Local local) {
    for (var r : regions)
      spec.commandLine()
          .getOut()
          .println(
              colored(
                  ChangeKind.CONFLICT,
                  "! #"
                      + r.id()
                      + " "
                      + r.dimension()
                      + " "
                      + r.bounds()
                      + " "
                      + r.blockCount()
                      + " 格 "
                      + r.choice().name().toLowerCase(Locale.ROOT)
                      + " "
                      + (r.resolved() ? "已解決" : "未解決")
                      + " ours: "
                      + r.oursAuthors()
                      + " theirs: "
                      + r.theirsAuthors()
                      + (r.redstone() ? "；含紅石，建議測試" : ""),
                  local));
  }

  abstract static class MergeCommand extends Subcommand {
    @Option(names = "--dry-run", description = "只計畫，不改世界／refs／MERGING")
    boolean dryRun;

    @Option(names = "--no-commit", description = "乾淨合併也停在 MERGING")
    boolean noCommit;

    @Option(names = "--strategy-option", description = "衝突自動選擇 ours|theirs")
    String strategy;

    @Option(names = "--distance", defaultValue = "1", description = "3D 分群曼哈頓距離 k（0..16）")
    int distance;

    WorldOperations.MergeOptions options() throws IOException {
      if (root.dimension != null) throw new IOException("合併操作要求全維度一致；不可指定 --dimension");
      org.worldgit.core.merge.MergeReport.Choice choice = null;
      if (strategy != null) {
        if (!Set.of("ours", "theirs").contains(strategy))
          throw new IOException("--strategy-option 只能 ours/theirs");
        choice =
            org.worldgit.core.merge.MergeReport.Choice.valueOf(strategy.toUpperCase(Locale.ROOT));
      }
      return new WorldOperations.MergeOptions(
          noCommit, choice, distance, dryRun, root.identity(), CommitMetadata.Source.CLI);
    }
  }

  @Command(name = "merge", mixinStandardHelpOptions = true, description = "三方合併／繼續／完整回復")
  static final class Merge extends MergeCommand {
    @Parameters(arity = "0..1")
    String revision;

    @Option(names = "--abort")
    boolean abort;

    @Option(names = "--continue")
    boolean resume;

    @Override
    public Integer call() throws Exception {
      var opts = options();
      if ((abort && resume)
          || ((abort || resume) && revision != null)
          || (!abort && !resume && revision == null))
        throw new IOException("使用 merge <branch|commit>、merge --abort 或 merge --continue");
      if ((abort || resume) && (noCommit || strategy != null))
        throw new IOException("--abort／--continue 不接受 --no-commit／--strategy-option");
      try (var ops = new WorldOperations(root.layout())) {
        return root.printMerge(
            abort
                ? ops.abortMerge(dryRun)
                : resume
                    ? ops.continueMerge(root.identity(), CommitMetadata.Source.CLI, dryRun)
                    : ops.merge(revision, opts));
      }
    }
  }

  @Command(name = "conflicts", mixinStandardHelpOptions = true, description = "列出 MERGING 的衝突區域")
  static final class Conflicts extends Subcommand {
    @Override
    public Integer call() throws Exception {
      try (var ops = new WorldOperations(root.layout())) {
        var state = ops.merging();
        var regions =
            state == null ? List.<org.worldgit.core.merge.MergeReport.Region>of() : state.regions();
        if (root.format == Format.json) root.json(regions);
        else root.printRegions(regions, root.local(new WorldRepositories(root.layout())));
        return 0;
      }
    }
  }

  @Command(
      name = "resolve",
      mixinStandardHelpOptions = true,
      description = "原地切換衝突區域並標記解決（manual 以目前世界為準）")
  static final class Resolve extends Subcommand {
    @Parameters String region;

    @Option(names = "--ours")
    boolean ours;

    @Option(names = "--theirs")
    boolean theirs;

    @Option(names = "--base")
    boolean base;

    @Option(names = "--manual")
    boolean manual;

    @Option(names = "--dry-run")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
      if (root.dimension != null) throw new IOException("resolve 不接受 --dimension；區域 id 已含維度");
      if ((ours ? 1 : 0) + (theirs ? 1 : 0) + (base ? 1 : 0) + (manual ? 1 : 0) != 1)
        throw new IOException("請指定一個 --ours|--theirs|--base|--manual");
      int id =
          region.equals("all")
              ? 0
              : Integer.parseInt(region.startsWith("#") ? region.substring(1) : region);
      if (id < 0 || (!region.equals("all") && id == 0)) throw new IOException("區域 id 必須為正整數或 all");
      var choice =
          ours
              ? org.worldgit.core.merge.MergeReport.Choice.OURS
              : theirs
                  ? org.worldgit.core.merge.MergeReport.Choice.THEIRS
                  : base
                      ? org.worldgit.core.merge.MergeReport.Choice.BASE
                      : org.worldgit.core.merge.MergeReport.Choice.MANUAL;
      try (var ops = new WorldOperations(root.layout())) {
        return root.printMerge(ops.selectRegion(id, choice, true, dryRun));
      }
    }
  }

  @Command(name = "revert", mixinStandardHelpOptions = true, description = "反向三方套用 commit 並產生新提交")
  static final class Revert extends MergeCommand {
    @Parameters String revision;

    @Override
    public Integer call() throws Exception {
      var opts = options();
      try (var ops = new WorldOperations(root.layout())) {
        return root.printMerge(ops.revert(revision, opts));
      }
    }
  }

  @Command(
      name = "cherry-pick",
      mixinStandardHelpOptions = true,
      description = "以 parent 為 base 三方套用 commit")
  static final class CherryPick extends MergeCommand {
    @Parameters String revision;

    @Override
    public Integer call() throws Exception {
      var opts = options();
      try (var ops = new WorldOperations(root.layout())) {
        return root.printMerge(ops.cherryPick(revision, opts));
      }
    }
  }
}
