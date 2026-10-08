package org.worldgit.cli;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import org.worldgit.core.operation.*;
import org.worldgit.core.graph.CommitGraph;
import org.worldgit.core.anvil.*;
import org.worldgit.core.capture.ScanIndex;
import org.worldgit.core.config.*;
import org.worldgit.core.diff.*;
import org.worldgit.core.model.*;
import org.worldgit.core.remote.*;
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
      Wgit.CherryPick.class,
      Wgit.Remote.class,
      Wgit.Fetch.class,
      Wgit.Push.class,
      Wgit.Pull.class,
      Wgit.Clone.class,
      Wgit.Tag.class,
      Wgit.Export.class,
      Wgit.Ignore.class,
      Wgit.Migrate.class
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

  @Option(names = "--mod-pack", scope = ScopeType.INHERIT, paramLabel = "id=jar",
      description = "離線讀取 Fabric mod jar 的 entity tags（可重複；不執行模組）")
  Map<String, Path> modPacks = new LinkedHashMap<>();
  private EntityTagRegistry.PackResolver packResolver;

  private EntityTagRegistry.PackResolver packs() throws IOException {
    if (packResolver == null && !modPacks.isEmpty()) packResolver = EntityTagRegistry.jars(modPacks);
    return packResolver;
  }

  @Option(
      names = "--dimension",
      scope = ScopeType.INHERIT,
      description = "僅操作指定維度；clone 可用逗號列出多個（必須含主世界）")
  String dimension;

  @Option(names = "--all", scope = ScopeType.INHERIT, description = "各已 init 維度獨立執行；log 時含所有分支")
  boolean all;
  private DimensionId activeDimension;
  private Object jsonData;
  private OperationProgress progress;
  private OperationResult.Status outcome = OperationResult.Status.SUCCESS;
  private OperationResult.ErrorReport failure;
  private Map<String,Object> summary = new LinkedHashMap<>();
  private List<String> nextSteps = new ArrayList<>();

  @Option(
      names = "--color",
      scope = ScopeType.INHERIT,
      defaultValue = "auto",
      description = "auto|always|never")
  Color color = Color.auto;

  @Option(
      names = "--format",
      scope = ScopeType.INHERIT,
      defaultValue = "text",
      description = "text|json")
  Format format = Format.text;

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
    var root = new Wgit();
    var cmd = new CommandLine(root);
    cmd.setOut(out); cmd.setErr(err);
    cmd.setParameterExceptionHandler((ex, arguments) -> {
      root.progressOperation = ex.getCommandLine().getCommandName();
      if (Arrays.stream(args).anyMatch(a -> a.equals("--format=json")) || Arrays.asList(args).contains("json") && Arrays.asList(args).contains("--format")) root.format = Format.json;
      var id = UUID.randomUUID();
      root.failure = OperationResult.ErrorReport.create("WG_INVALID_ARGUMENT", id, root.progressOperation, null, "0.1.0-SNAPSHOT", root.minecraftVersion(), "CLI Java " + Runtime.version(), ex.getMessage(), knownSecrets());
      var result = new OperationResult(id, root.progressOperation, OperationResult.Status.FAILED, null, Map.of(), 0, List.of("wgit " + root.progressOperation + " --help"), root.failure);
      try { if (root.format == Format.json) root.writeJson(Map.of("result",result,"data",Map.of())); else { err.println(root.failure.text()); out.println(root.completion(result)); } } catch(IOException ignored) {}
      return result.exitCode();
    });
    cmd.setExecutionExceptionHandler((ex, line, result) -> {
      root.outcome = ex instanceof InterruptedIOException || OperationProgress.cancelled() ? OperationResult.Status.CANCELLED : OperationResult.Status.FAILED;
      root.failure = OperationResult.ErrorReport.create("WG_OPERATION_FAILED", root.progress.id(),
          root.progressOperation, root.activeDimension, "0.1.0-SNAPSHOT", root.minecraftVersion(), "CLI Java " + Runtime.version(), error(ex), knownSecrets());
      if (root.format != Format.json) err.println(root.failure.text());
      return root.outcome == OperationResult.Status.CANCELLED ? 130 : 1;
    });
    cmd.setExecutionStrategy(parsed -> {
      var leaf = parsed; while (leaf.subcommand() != null) leaf = leaf.subcommand();
      String operation = leaf.commandSpec().name(); root.progressOperation = operation;
      if (parsed.isUsageHelpRequested() || parsed.isVersionHelpRequested() || leaf.isUsageHelpRequested())
        return new CommandLine.RunLast().execute(parsed);
      root.jsonData = null; root.outcome = OperationResult.Status.SUCCESS; root.summary = new LinkedHashMap<>(); root.nextSteps = new ArrayList<>();
      try (var context = new OperationProgress(operation, event -> root.renderProgress(event)); var signal = CancelSignal.install(context)) {
        root.progress = context;
        int code;
        var dimensions = new LinkedHashMap<String, Object>();
        try {
        root.validateSelection();
        boolean multi = root.all && !Set.of("init", "commit", "status", "log", "diff", "clone", "export", "migrate").contains(operation);
        boolean readMany = !root.all && root.dimension == null && Set.of("branch", "stash", "tag", "remote", "conflicts").contains(operation)
            && (operation.equals("branch") && ((Branch)leaf.commandSpec().userObject()).name == null
                || operation.equals("tag") && ((Tag)leaf.commandSpec().userObject()).name == null
                || operation.equals("remote") && ((Remote)leaf.commandSpec().userObject()).action.equals("list")
                || operation.equals("stash") && ((StashCommand)leaf.commandSpec().userObject()).action == StashCommand.Action.list
                || operation.equals("conflicts"));
        if (multi || readMany) {
          code = 0; int successes = 0, noops = 0; var errors = new ArrayList<String>();
          for (var id : new WorldRepositories(root.layout()).tracked().keySet()) {
            root.activeDimension = id; root.outcome = OperationResult.Status.SUCCESS; root.failure = null; root.jsonData = null; root.summary = new LinkedHashMap<>(); root.nextSteps = new ArrayList<>();
            int item = root.runCommand(parsed);
            var state = root.outcome;
            if (item != 0 && state == OperationResult.Status.SUCCESS) state = OperationResult.Status.FAILED;
            var result = new OperationResult(context.id(), operation, state, id, root.summary, context.elapsedMillis(), root.nextSteps, root.failure);
            dimensions.put(id.value(), Map.of("result", result, "data", root.jsonData == null ? Map.of() : root.jsonData));
            if (root.format != Format.json) out.println(root.completion(result));
            if (item != 0) { code = 2; if(root.failure != null) errors.add(id + ": " + root.failure.message()); } else { successes++; if(state == OperationResult.Status.NO_OP) noops++; }
            if (state == OperationResult.Status.CANCELLED) break;
          }
          root.activeDimension = null; root.failure = null; root.jsonData = dimensions;
          root.summary = new LinkedHashMap<>(Map.of("dimensions", dimensions.size()));
          root.outcome = code == 0 ? noops == dimensions.size() ? OperationResult.Status.NO_OP : OperationResult.Status.SUCCESS : successes == 0 ? OperationResult.Status.FAILED : OperationResult.Status.PARTIAL;
          if(!errors.isEmpty()) root.reportProblem(String.join("; ", errors));
        } else code = root.runCommand(parsed);
        } catch (Exception ex) { code = root.recordFailure(ex); }
        if (code != 0 && root.outcome == OperationResult.Status.SUCCESS) root.outcome = OperationResult.Status.FAILED;
        if (OperationProgress.cancelled()) root.outcome = OperationResult.Status.CANCELLED;
        var result = new OperationResult(context.id(), operation, root.outcome, root.resultDimension(operation),
            root.summary, context.elapsedMillis(), root.nextSteps, root.failure);
        context.close();
        if (root.animation()) err.print("\r\033[2K");
        if (root.format == Format.json) root.writeJson(Map.of("result", result, "data", root.jsonData == null ? Map.of() : root.jsonData));
        else out.println(root.completion(result));
        out.flush(); err.flush(); return result.exitCode();
      } catch (IOException ex) { err.println(OperationResult.redact(error(ex))); return 1; }
    });
    return cmd.execute(args);
  }

  private String minecraftVersion() {
    try {
      var layout=layout();Path level=layout.world().resolve("level.dat");
      if(Files.isRegularFile(level)) {
        String name=WorldLayout.readGzip(level).compound("Data").compound("Version").string("Name");
        if(!name.isBlank()) return name;
      }
      return "DataVersion="+layout.dataVersion();
    } catch(Exception unavailable) { return "unknown"; }
  }

  private static List<String> knownSecrets() {
    var pattern = java.util.regex.Pattern.compile("(?i)(?:^|_)(?:TOKEN|SECRET|PASSWORD|AUTHORIZATION|PAT)(?:_|$)");
    return System.getenv().entrySet().stream().filter(e -> pattern.matcher(e.getKey()).find())
        .map(Map.Entry::getValue).filter(value -> !value.isEmpty()).toList();
  }

  private int runCommand(CommandLine.ParseResult parsed) {
    try { return new CommandLine.RunLast().execute(parsed); }
    catch (Exception exception) { return recordFailure(exception); }
  }
  private int recordFailure(Exception exception) {
    Throwable cause = exception instanceof CommandLine.ExecutionException && exception.getCause() != null ? exception.getCause() : exception;
    outcome = cause instanceof InterruptedIOException || OperationProgress.cancelled() ? OperationResult.Status.CANCELLED : OperationResult.Status.FAILED;
    DimensionId id = null; try { id = selected(); if (id == null) id = layout().currentDimension(); } catch (Exception ignored) {}
    failure = OperationResult.ErrorReport.create("WG_OPERATION_FAILED", progress.id(), progressOperation,
        id, "0.1.0-SNAPSHOT", minecraftVersion(), "CLI Java " + Runtime.version(), cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), knownSecrets());
    if (format != Format.json) spec.commandLine().getErr().println(failure.text());
    return outcome == OperationResult.Status.CANCELLED ? 130 : 1;
  }

  private DimensionId resultDimension(String operation) {
    if (activeDimension != null) return activeDimension;
    if (all || Set.of("commit", "init", "status", "log", "diff", "clone", "export", "migrate").contains(operation) && dimension == null) return null;
    try { return selected() == null ? layout().currentDimension() : selected(); } catch (Exception ignored) { return null; }
  }
  private String progressOperation;
  private boolean animation() { return System.console() != null && format != Format.json && color != Color.never && System.getenv("NO_COLOR") == null; }
  private void renderProgress(OperationProgress.Event event) {
    if (!animation()) return;
    String count = event.total() == null ? event.completed() + " / ?" : event.completed() + " / " + event.total();
    String percent = event.total() == null || event.total() == 0 ? "…" : (100 * event.completed() / event.total()) + "%";
    int cells = event.total() == null || event.total() == 0 ? 0 : (int)(20 * event.completed() / event.total());
    String bar = event.total() == null ? "[ … ]" : "[" + "=".repeat(cells) + " ".repeat(20 - cells) + "]";
    spec.commandLine().getErr().printf("\r\033[2K%s %s %s %s %s %s %.1f/s ETA=%s", event.dimension() == null ? "" : event.dimension(), event.phase(), bar, percent, count, event.unit(), event.ratePerSecond() == null ? 0 : event.ratePerSecond(), event.remainingMillis() == null ? "?" : event.remainingMillis() / 1000 + "s");
    spec.commandLine().getErr().flush();
  }
  private String completion(OperationResult result) {
    String label = switch (result.status()) {
      case SUCCESS -> CliMessages.text("success"); case NO_OP -> CliMessages.text("no-op");
      case PARTIAL -> CliMessages.text("partial"); case FAILED -> CliMessages.text("failed"); case CANCELLED -> CliMessages.text("cancelled");
    };
    String text = label + "：" + result.operation() + (result.dimension() == null ? "" : " " + result.dimension()) + (result.summary().isEmpty() ? "" : " " + result.summary()) + "（" + result.elapsedMillis() + " ms）";
    if (result.status() == OperationResult.Status.CANCELLED) text += CliMessages.text("cancel-next");
    if (System.getenv("NO_COLOR") == null && (color == Color.always || color == Color.auto && System.console() != null)) {
      String ansi = switch(result.status()) { case SUCCESS -> "32"; case NO_OP -> "90"; case PARTIAL -> "33"; case FAILED, CANCELLED -> "31"; };
      return "\033[" + ansi + "m" + text + "\033[0m";
    }
    return text;
  }

  private String decorate(List<CommitGraph.Label> labels) {
    if(labels.isEmpty()) return "";
    return "(" + labels.stream().map(label -> {
      String name = label.kind().equals("tag") ? "tag: " + label.name() : label.name();
      if(System.getenv("NO_COLOR") == null && (color == Color.always || color == Color.auto && System.console() != null)) {
        String ansi = switch(label.kind()) {case "HEAD" -> "36"; case "branch" -> "32"; case "tag" -> "33"; default -> "31";};
        name = "\033["+ansi+"m"+name+"\033[0m";
      }
      return name;
    }).collect(java.util.stream.Collectors.joining(", ")) + ")";
  }

  private WorldOperations operations() throws IOException {
    var layout = layout(); return WorldOperations.inDimension(layout, selected() == null ? layout.currentDimension() : selected(), packs());
  }
  private WorldRemotes remoteOperations(boolean every) throws IOException {
    var layout = layout(); var paths = new WorldRepositories(layout).tracked();
    if (!every || selected() != null) {
      var id = selected() == null ? layout.currentDimension() : selected();
      Path path = paths.get(id); if (path == null) throw new IOException("維度尚未 init：" + id);
      paths = new TreeMap<>(Map.of(id, path));
    }
    return new WorldRemotes(layout.repositoryRoot(), paths, Credentials.system());
  }

  private DimensionId selected() {
    return activeDimension != null ? activeDimension : dimension == null ? null : new DimensionId(dimension);
  }

  private static String error(Exception ex) {
    return OperationResult.redact(ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage());
  }

  private WorldLayout layout() throws IOException {
    return WorldLayout.discover(world);
  }

  private WorldRepositories repositories(WorldLayout layout) throws IOException {
    var packs = packs();
    return new WorldRepositories(
        layout, dim -> new OfflineWorld(layout, dim, s -> spec.commandLine().getErr().println(s), packs));
  }

  private WorldGitConfig.Local local(WorldRepositories repos) throws IOException {
    var layout = layout();
    return WorldGitConfig.readLocal(repos.tracked().getOrDefault(selected() == null ? layout.currentDimension() : selected(), layout.repository(selected() == null ? layout.currentDimension() : selected())).resolve("worldgit.yml"));
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

  private void json(Object value) throws IOException { jsonData = value; }

  private void writeJson(Object value) throws IOException {
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

  /** CLI 沒有平台實體事件；提示由呼叫端依 repo 政策與 CLI 語系呈現。 */
  private void warnings(List<String> messages, Path repository) throws IOException {
    warnings(messages);
    if (WorldGitConfig.readRepo(repository.resolve("worldgit-repo.yml")).entities()
        == WorldGitConfig.Entities.PLAYER_TOUCHED)
      spec.commandLine().getErr().println(CliMessages.text("offline-player-touched"));
  }

  private int printBatch(WorldRepositories.Batch<DimensionRepository.CommitResult> batch, WorldRepositories repos)
      throws IOException {
    long failures = batch.dimensions().values().stream().filter(o -> !o.success()).count();
    outcome = failures == batch.dimensions().size() ? OperationResult.Status.FAILED : failures > 0 ? OperationResult.Status.PARTIAL : batch.dimensions().values().stream().allMatch(o -> !o.value().changed()) ? OperationResult.Status.NO_OP : OperationResult.Status.SUCCESS;
    var commits = new TreeMap<String,Object>();
    batch.dimensions().forEach((d,o) -> commits.put(d.value(), o.success() ? o.value().changed() ? o.value().commit().substring(0,8) : "NO_OP" : "FAILED"));
    summary.put("commits", commits);
    var changes = new TreeMap<String,Object>();
    batch.dimensions().forEach((d,o) -> { if(o.success()) changes.put(d.value(),o.value().status().diff().counts()); });
    summary.put("changes",changes);
    if (failures > 0) reportProblem(batch.dimensions().entrySet().stream().filter(e -> !e.getValue().success()).map(e -> e.getKey() + ": " + e.getValue().error()).collect(java.util.stream.Collectors.joining("; ")));
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
          warnings(value.status().warnings(), repos.tracked().get(e.getKey()));
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

    @Option(names = "--with-dimensions", description = "all|nether,end 或維度 id（逗號分隔）") String withDimensions;
    @Option(names = "--only", description = "只 init 所在維度、不詢問") boolean only;
    @Override
    public Integer call() throws Exception {
      if (only && withDimensions != null) throw new IOException("--only 與 --with-dimensions 不可並用");
      WorldGitConfig.Repo config = WorldGitConfig.readRepo("track: " + track, "--track");
      var layout = root.layout();
      var repos = root.repositories(layout);
      try (var guard = SessionGuard.acquire(layout)) {
        var ids = new TreeSet<DimensionId>();
        var selected = root.selected() == null ? layout.currentDimension() : root.selected(); ids.add(selected);
        if (withDimensions != null) {
          if (withDimensions.equals("all")) ids.addAll(layout.dimensions().keySet());
          else for (String value : withDimensions.split(",")) ids.add(new DimensionId(switch(value) {
            case "nether" -> "minecraft:the_nether"; case "end" -> "minecraft:the_end"; default -> value;
          }));
        } else if (selected.equals(DimensionId.OVERWORLD) && !only) {
          var extras = repos.initializable().stream().filter(d -> !d.initialized() && Set.of("minecraft:the_nether", "minecraft:the_end").contains(d.dimension().value())).map(WorldRepositories.Initializable::dimension).toList();
          if (!extras.isEmpty()) {
            if (System.console() != null && root.format == Format.text && System.getenv("CI") == null) {
              String answer = System.console().readLine("%s",CliMessages.text("init-prompt"));
              if (answer != null && Set.of("y", "yes").contains(answer.trim().toLowerCase(Locale.ROOT))) ids.addAll(extras);
            } else root.spec.commandLine().getErr().println(CliMessages.text("init-hint"));
          }
        }
        return root.printBatch(repos.initDimensions(ids, template.name(), config.track(), root.identity()), repos);
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
        var states = new TreeMap<String, Object>();
        List<WorldRemotes.Tracking> tracking;
        try (var remote = root.remoteOperations(true)) { tracking = remote.tracking(); }
        for (var e : batch.dimensions().entrySet()) {
          Path path = repos.tracked().get(e.getKey());
          try (var repo = new DimensionRepository(path, e.getKey(), false)) {
            var merging = org.worldgit.core.merge.MergeState.read(path.resolve("merge-state.bin"));
            var state = new LinkedHashMap<String,Object>();
            state.put("head", repo.refs().headState());
            state.put("status", e.getValue());
            state.put("state", merging == null ? OperationState.partial(path) ? "PARTIAL" : "COMPLETE" : "MERGING");
            if (merging != null) { state.put("merging", merging); state.put("remaining", merging.remaining()); }
            state.put("tracking", tracking.stream().filter(t -> t.dimension().equals(e.getKey())).toList());
            states.put(e.getKey().value(), state);
            if (root.format != Format.json) {
              root.spec.commandLine().getOut().printf("%s %s @ %s %s%n", e.getKey(), repo.refs().headState().branch(), repo.refs().head().substring(0,8), state.get("state"));
              if (merging != null) root.spec.commandLine().getOut().printf("  %s %s 剩餘衝突=%d%n", merging.mode(), merging.source(), merging.remaining());
              if (e.getValue().success()) {
                var status = e.getValue().value(); root.printDiff(status.diff(), WorldGitConfig.readLocal(path.resolve("worldgit.yml")), false);
                root.spec.commandLine().getOut().printf("  candidates=%d payloads-read=%d%n", status.candidates(),status.payloadsRead()); root.warnings(status.warnings(), path);
              } else root.reportProblem(e.getKey() + "：" + e.getValue().error());
              for (var t : tracking) if(t.dimension().equals(e.getKey())) root.spec.commandLine().getOut().printf("  %s/%s ahead=%d behind=%d%s%n",t.remote(),t.branch(),t.ahead(),t.behind(),t.estimated()?"（估算）":"");
            }
          }
        }
        if(root.format == Format.json) root.json(Map.of("dimensions",batch.dimensions(),"repositories",states,"tracking",tracking));
        if(!batch.success()) {
          root.outcome = batch.dimensions().values().stream().anyMatch(WorldRepositories.Outcome::success) ? OperationResult.Status.PARTIAL : OperationResult.Status.FAILED;
          root.reportProblem(batch.dimensions().entrySet().stream().filter(e -> !e.getValue().success()).map(e -> e.getKey()+": "+e.getValue().error()).collect(java.util.stream.Collectors.joining("; ")));
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
      var target = root.selected() == null ? layout.currentDimension() : root.selected();
      if (repos.tracked().containsKey(target) && Files.exists(repos.tracked().get(target).resolve("merge-state.bin"))) {
        try (var ops = root.operations()) {
          return root.printMerge(
              ops.commitMerge(root.identity(), CommitMetadata.Source.CLI, message, false));
        }
      }
      try (var guard = SessionGuard.acquire(layout)) {
        return root.printBatch(
            repos.commit(root.selected(), message, root.identity(), local.entityTolerance()), repos);
      }
    }
  }

  @Command(name = "log", mixinStandardHelpOptions = true, description = "每維度獨立歷史與分支圖")
  static final class Log extends Subcommand {
    @Option(names = {"-n", "--max-count"}, defaultValue = "20") int limit;
    @Option(names = "--graph") boolean graph;
    @Override public Integer call() throws Exception {
      var layout = root.layout(); var repos = root.repositories(layout); var data = new TreeMap<String, Object>();
      for (var entry : root.selectedRepos(repos).entrySet()) try (var repo = new DimensionRepository(entry.getValue(), entry.getKey(), false)) {
        var history = CommitGraph.read(repo.refs(), limit, root.all); data.put(entry.getKey().value(), history);
        if (root.format == Format.text) {
          root.spec.commandLine().getOut().println(entry.getKey() + " " + repo.refs().headState().branch());
          for (var node : history.nodes()) {
            StringBuilder lanes = new StringBuilder();
            if (graph) lanes.append(org.worldgit.core.graph.GraphText.node(node));
            root.spec.commandLine().getOut().println(lanes + node.id().substring(0, 8) + " " + root.decorate(node.labels()) + " " + node.message().lines().findFirst().orElse(""));
            if (graph) org.worldgit.core.graph.GraphText.transition(node).ifPresent(root.spec.commandLine().getOut()::println);
          }
          if (history.truncated()) root.spec.commandLine().getOut().println("…歷史已截斷");
        }
      }
      if (root.format == Format.json) root.json(data); return 0;
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
      int successes = 0; var errors = new ArrayList<String>();
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
                  new OfflineWorld(layout, layout.dimensions().get(e.getKey()), s -> {}, root.packs())) {
                var status = repo.status(source, repos.manifest(), local.entityTolerance(), false);
                root.warnings(status.warnings(), e.getValue());
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
            successes++;
            if (root.format == Format.text) root.printDiff(diff, local, blocks);
          } catch (Exception ex) {
            ok = false;
            errors.add(e.getKey()+": "+error(ex));
            output.put(e.getKey().value(), Map.of("error", error(ex)));
            if (root.format == Format.text)
              root.spec.commandLine().getErr().println(e.getKey() + " 失敗：" + error(ex));
          }
      }
      if (root.format == Format.json) root.json(output);
      if (!ok) {
        root.outcome = successes > 0 ? OperationResult.Status.PARTIAL : OperationResult.Status.FAILED;
        root.reportProblem(String.join("; ",errors));
      }
      return ok ? 0 : 1;
    }
  }

  private void reportProblem(String message) {
    failure = OperationResult.ErrorReport.create("WG_" + outcome, progress.id(), progressOperation, resultDimension(progressOperation), "0.1.0-SNAPSHOT", minecraftVersion(), "CLI Java " + Runtime.version(), message, knownSecrets());
    nextSteps.add(progressOperation.equals("verify") ? "檢查 diff --blocks" : "查看 status 與操作 journal，再重試或恢復");
  }

  private int printApply(WorldOperations.Result result) throws IOException {
    summary.put("state", result.state()); summary.put("dimensions", result.dimensions());
    if (result.state() == WorldOperations.State.COMPLETE) {
      var heads = new TreeMap<String,String>();var paths=new WorldRepositories(layout()).tracked();
      for(var id:result.dimensions().keySet()) try(var repo=new JGitStore(paths.get(id),false)) {
        var head=repo.headState();heads.put(id.value(),(head.branch()==null?"detached":head.branch())+" @ "+head.commit().substring(0,8));
      }
      summary.put("heads",heads);
    }
    if (!result.success()) outcome = progressOperation.equals("verify") ? OperationResult.Status.FAILED : OperationResult.Status.PARTIAL;
    if (!result.success()) reportProblem(result.error());
    if (format == Format.json) json(result);
    else {
      var config = local(repositories(layout()));
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

  private void validateSelection() throws IOException {
    if (dimension != null && all && !progressOperation.equals("log")) throw new IOException("--dimension 與 --all 不可同時指定");
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
      try (var operations = root.operations()) {
        return root.printApply(
            operations.restore(revision, root.selected(), range(), dryRun, delete));
      }
    }
  }

  @Command(
      name = "switch",
      mixinStandardHelpOptions = true,
      description = "所選維度原地切換；commit 會進入 detached HEAD")
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
      root.validateSelection();
      try (var operations = root.operations()) {
        root.summary.put("branchOrRevision", revision);
        return root.printApply(operations.switchTo(revision, stash, force, dryRun, delete));
      }
    }
  }

  @Command(name = "branch", mixinStandardHelpOptions = true, description = "列出各維度分支；建立／刪除只作用所選維度")
  static final class Branch extends Subcommand {
    @Parameters(index = "0", arity = "0..1")
    String name;

    @Parameters(index = "1", arity = "0..1")
    String start;

    @Option(names = "-d")
    boolean delete;

    @Override
    public Integer call() throws Exception {
      root.validateSelection();
      try (var operations = root.operations()) {
        if (name != null) {
          root.summary.put("branch",name);root.summary.put("action",delete ? "delete" : "create");
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
      root.validateSelection();
      try (var operations = root.operations()) {
        return root.printApply(operations.resetHard(revision, force, dryRun));
      }
    }
  }

  @Command(
      name = "stash",
      mixinStandardHelpOptions = true,
      description = "所選維度 stash push/pop/list/drop")
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
      root.validateSelection();
      try (var operations = root.operations()) {
        root.summary.put("action",action);root.summary.put("index",index);
        if (action == Action.push) {
          int before=operations.stashes().size();var result=operations.stashPush(message,dryRun);
          if(result.success() && !dryRun && operations.stashes().size()==before) root.outcome=OperationResult.Status.NO_OP;
          return root.printApply(result);
        }
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
      try (var operations = root.operations()) {
        return root.printApply(
            operations.verify(
                revision,
                root.selected(),
                range(),
                range().kind() == org.worldgit.core.apply.Scope.Kind.ALL));
      }
    }
  }

  private void recordMergeStatus(WorldOperations.MergeResult result) {
    summary.put("state", result.state()); summary.put("commits", result.commits()); summary.put("remaining", result.merging() == null ? 0 : result.merging().remaining());
    if (!result.success() || result.state().equals("MERGING") && result.merging() != null && result.merging().remaining() > 0) outcome = OperationResult.Status.PARTIAL;
    if (outcome == OperationResult.Status.PARTIAL) reportProblem(result.error() == null ? "合併尚有 " + summary.get("remaining") + " 個衝突" : result.error());
  }

  private int printMerge(WorldOperations.MergeResult result) throws IOException {
    recordMergeStatus(result);
    if (format == Format.json) {
      json(mergeData(result));
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

  private Map<String, Object> mergeData(WorldOperations.MergeResult result) {
    var value = new LinkedHashMap<String, Object>();
    value.put("state", result.state());
    value.put("reports", result.reports());
    value.put("remaining", result.merging() == null ? 0 : result.merging().remaining());
    value.put("commits", result.commits());
    value.put("error", result.error());
    var plans = new TreeMap<DimensionId, org.worldgit.core.apply.ApplyPlan.Stats>();
    result.plans().forEach((id, p) -> plans.put(id, p.stats()));
    value.put("plans", plans);
    return value;
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
      try (var ops = root.operations()) {
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
      try (var ops = root.operations()) {
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
      try (var ops = root.operations()) {
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
      try (var ops = root.operations()) {
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
      try (var ops = root.operations()) {
        return root.printMerge(ops.cherryPick(revision, opts));
      }
    }
  }

  private int transfer(WorldRemotes.TransferResult result) throws IOException {
    if (!result.success()) { outcome = result.commits().isEmpty() ? OperationResult.Status.FAILED : OperationResult.Status.PARTIAL; reportProblem(result.error()); }
    summary.put("commits", result.commits());
    summary.put("bytes",result.packs().values().stream().flatMap(Collection::stream).mapToLong(GitTransfer.PackSize::preparedBytes).sum());
    if (format == Format.json) json(result);
    else {
      spec.commandLine().getOut().println(result.state() + " operation=" + result.operation());
      for (var e : result.commits().entrySet())
        spec.commandLine().getOut().println(e.getKey() + " " + e.getValue());
      for (var e : result.packs().entrySet()) {
        long bytes = e.getValue().stream().mapToLong(GitTransfer.PackSize::preparedBytes).sum();
        spec.commandLine()
            .getOut()
            .println(e.getKey() + " packs=" + e.getValue().size() + " bytes=" + bytes);
      }
      if (result.error() != null) spec.commandLine().getErr().println(result.error());
    }
    return result.success() ? 0 : 1;
  }

  @Command(name = "remote", mixinStandardHelpOptions = true, description = "世界遠端設定（YAML）")
  static final class Remote extends Subcommand {
    @Parameters(index = "0", defaultValue = "list")
    String action;

    @Parameters(index = "1", arity = "0..1")
    String name;

    @Parameters(index = "2", arity = "0..1")
    String url;

    @Option(names = "--dry-run")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
      try (var remotes = root.remoteOperations(action.equals("add") && root.selected() == null)) {
        if (action.equals("list")) {
          if (name != null || url != null) throw new IOException("remote list 不接受參數");
          if (root.format == Format.json) root.json(remotes.remotes());
          else
            remotes
                .remotes()
                .forEach(
                    (n, r) ->
                        root.spec
                            .commandLine()
                            .getOut()
                            .println(
                                n + " " + (r.dimensions().isEmpty() ? r.url() : r.dimensions())));
        } else {
          if (name == null || (!action.equals("remove") && url == null))
            throw new IOException("remote add/set-url 需要 name/url；remove 需要 name");
          remotes.configure(action, name, url, dryRun);
          if (root.format == Format.json)
            root.json(Map.of("state", dryRun ? "DRY_RUN" : "COMPLETE", "remote", name));
          else
            root.spec
                .commandLine()
                .getOut()
                .println((dryRun ? "DRY_RUN " : "") + action + " " + name);
        }
        return 0;
      }
    }
  }

  @Command(name = "fetch", mixinStandardHelpOptions = true, description = "取得目前維度更新；不套用世界")
  static final class Fetch extends Subcommand {
    @Parameters(index = "0", defaultValue = "origin")
    String remote;

    @Option(names = "--dry-run")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
      root.validateSelection();
      try (var r = root.remoteOperations(false)) {
        return root.transfer(r.fetch(remote, dryRun));
      }
    }
  }

  @Command(name = "push", mixinStandardHelpOptions = true, description = "推送目前維度分支；--all 逐維度獨立執行")
  static final class Push extends Subcommand {
    @Parameters(index = "0", defaultValue = "origin")
    String remote;

    @Parameters(index = "1", arity = "0..1")
    String branch;

    @Option(names = "--tags")
    boolean tags;

    @Option(names = "--force-with-lease")
    boolean forceLease;

    @Option(names = "--dry-run")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
      root.validateSelection();
      try (var r = root.remoteOperations(false)) {
        return root.transfer(r.push(remote, branch, tags, forceLease, dryRun, root.identity()));
      }
    }
  }

  @Command(name = "pull", mixinStandardHelpOptions = true, description = "fetch 後以 FF 或三方合併寫回離線世界")
  static final class Pull extends Subcommand {
    @Parameters(index = "0", defaultValue = "origin")
    String remote;

    @Parameters(index = "1", arity = "0..1")
    String branch;

    @Option(names = "--ff-only")
    boolean ffOnly;

    @Option(names = "--dry-run")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
      root.validateSelection();
      var layout = root.layout();
      SortedMap<DimensionId, String> targets;
      try (var r = root.remoteOperations(false)) {
        var f = r.fetch(remote, false);
        if (!f.success()) return root.transfer(f);
        if (branch == null) branch = r.branch();
        targets = r.trackingHeads(remote, branch);
      }
      try (var ops = root.operations()) {
        var result =
            ops.pull(
                targets,
                null,
                ffOnly,
                new WorldOperations.MergeOptions(
                    false, null, 1, dryRun, root.identity(), CommitMetadata.Source.CLI));
        if (root.format == Format.json) {
          root.recordMergeStatus(result.result());
          root.json(
              Map.of(
                  "expectedHeads",
                  result.expectedHeads(),
                  "targets",
                  result.targets(),
                  "fastForward",
                  result.fastForward(),
                  "result",
                  root.mergeData(result.result())));
        } else {
          root.spec
              .commandLine()
              .getOut()
              .println(
                  result.result().state()
                      + " "
                      + (result.fastForward() ? "fast-forward" : "merge"));
          root.printMerge(result.result());
        }
        if (!dryRun && result.result().state().equals("COMPLETE") && result.expectedHeads().equals(result.targets())) root.outcome=OperationResult.Status.NO_OP;
        return result.result().success() ? 0 : 1;
      }
    }
  }

  private static Map<DimensionId, String> revisions(List<String> values) throws IOException {
    var result = new TreeMap<DimensionId, String>();
    for (String value : values) {
      int equals = value.indexOf('=');
      if (equals < 1 || equals == value.length()-1) throw new IOException("需要 dimension=revision：" + value);
      var dimension = new DimensionId(value.substring(0, equals));
      if (result.put(dimension, value.substring(equals+1)) != null) throw new IOException("重複維度：" + dimension);
    }
    return result;
  }

  @Command(name = "clone", mixinStandardHelpOptions = true, description = "下載並組裝可直接開啟的世界")
  static final class Clone extends Subcommand {
    @Parameters(index = "0")
    String url;

    @Parameters(index = "1", arity = "0..1")
    Path directory;

    @Option(names = "--branch", description = "可重複 dimension=branch")
    List<String> branches = new ArrayList<>();

    @Override
    public Integer call() throws Exception {
      var remote = RemoteSpec.parse(url);
      if (directory == null) {
        String value = url.replaceAll("/+$", "");
        String name = value.substring(value.lastIndexOf('/') + 1).replaceAll("\\.git$", "");
        if (name.isBlank()
            || name.contains("{")
            || name.contains(":")
            || name.equals(".")
            || name.equals("..")) name = "world";
        directory = Path.of(name);
      }
      var result =
          WorldClone.cloneWorld(
              remote,
              directory,
              revisions(branches),
              root.dimension == null ? null : Arrays.stream(root.dimension.split(",", -1)).map(DimensionId::new).collect(java.util.stream.Collectors.toSet()),
              Credentials.system(),
              WorldAssembler.Budget.defaults());
      if (root.format == Format.json)
        root.json(
            Map.of(
                "world",
                result.world().toString(),
                "dimensions",
                result.dimensions(),
                "assembly",
                result.assembly(),
                "packs",
                result.packs()));
      else root.spec.commandLine().getOut().println("clone 完成：" + result.world() + "（可直接開啟）");
      return 0;
    }
  }

  @Command(name = "tag", mixinStandardHelpOptions = true, description = "目前維度輕量或附註 tag；--all 逐維度執行")
  static final class Tag extends Subcommand {
    @Parameters(index = "0", arity = "0..1")
    String name;

    @Parameters(index = "1", arity = "0..1")
    String revision;

    @Option(names = {"-m", "--message"})
    String message;

    @Option(names = {"-l", "--list"})
    boolean list;

    @Option(names = {"-d", "--delete"})
    boolean delete;

    @Option(names = "--dry-run")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
      root.validateSelection();
      var layout = root.layout();
      var paths = root.selectedRepos(new WorldRepositories(layout));
      if (!list && name != null && root.selected() == null) {
        var id = layout.currentDimension(); paths = new TreeMap<>(Map.of(id, paths.get(id)));
      }
      try (var group = new RepositoryGroup(layout.repositoryRoot(), paths)) {
        if (list || name == null) {
          if (delete || message != null || revision != null) throw new IOException("tag list 選項無效");
          if (root.format == Format.json) root.json(group.tags());
          else group.tags().forEach(n -> root.spec.commandLine().getOut().println(n));
        } else {
          if (delete && (message != null || revision != null))
            throw new IOException("tag -d 只接受名稱");
          group.tag(name, revision, message, root.identity(), delete, dryRun);
          if (root.format == Format.json)
            root.json(Map.of("state", dryRun ? "DRY_RUN" : "COMPLETE", "tag", name));
          else root.spec.commandLine().getOut().println((dryRun ? "DRY_RUN " : "") + "tag " + name);
        }
      }
      return 0;
    }
  }

  @Command(
      name = "export",
      mixinStandardHelpOptions = true,
      description = "從 commit/tag 串流輸出世界 ZIP")
  static final class Export extends Subcommand {
    @Parameters(arity = "1..2") List<String> positional;
    @Option(names = "--rev", description = "可重複 dimension=revision；其他維度用 HEAD") List<String> revisions = new ArrayList<>();

    @Option(names = "--max-bytes", defaultValue = "2147483648")
    long maxBytes;

    @Option(names = "--max-seconds", defaultValue = "900")
    long maxSeconds;

    @Option(names = "--dry-run")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
      String revision = positional.size() == 2 ? positional.getFirst() : "HEAD";
      Path output = Path.of(positional.getLast());
      var layout = root.layout();
      try (var group =
          new RepositoryGroup(layout.repositoryRoot(), root.selectedRepos(new WorldRepositories(layout)))) {
        var selected = Wgit.revisions(revisions);
        var commits = new TreeMap<DimensionId, RefStore.Commit>();
        for (var entry : group.repos().entrySet()) {
          var refs = entry.getValue().refs(); commits.put(entry.getKey(), refs.readCommit(refs.resolve(selected.getOrDefault(entry.getKey(), revision))));
        }
        if (!commits.keySet().containsAll(selected.keySet())) throw new IOException("--rev 指定未 init 維度");
        if (Files.exists(output)) throw new IOException("export 目的地已存在");
        if (dryRun) {
          group.validate(commits);
          if (root.format == Format.json)
            root.json(Map.of("state", "DRY_RUN", "output", output.toString()));
          else root.spec.commandLine().getOut().println("DRY_RUN export " + output);
          return 0;
        }
        Path dest = output.toAbsolutePath();
        Files.createDirectories(dest.getParent());
        Path temp = Files.createTempFile(dest.getParent(), ".wgit-export-", ".zip");
        try {
          WorldAssembler.Result result;
          try (var out = Files.newOutputStream(temp)) {
            result =
                new WorldAssembler(
                        new WorldAssembler.Budget(
                            maxBytes, java.time.Duration.ofSeconds(maxSeconds)))
                    .zip(group, commits, out, dest.getParent());
          }
          Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE);
          if (root.format == Format.json) root.json(result);
          else
            root.spec
                .commandLine()
                .getOut()
                .println("export 完成：" + output + " bytes=" + result.bytes());
        } finally {
          Files.deleteIfExists(temp);
        }
      }
      return 0;
    }
  }
  @Command(name = "ignore", mixinStandardHelpOptions = true, description = "結構化編輯 .wgignore；編號為檔案行號")
  static final class Ignore extends Subcommand {
    @Parameters(index = "0", defaultValue = "list") String action;
    @Parameters(index = "1..*", arity = "0..*") List<String> arguments = new ArrayList<>();
    @Option(names = "--dry-run") boolean dryRun;
    @Override public Integer call() throws Exception {
      var layout = root.layout(); var id = root.selected() == null ? layout.currentDimension() : root.selected();
      Path path = new WorldRepositories(layout).tracked().get(id); if (path == null) throw new IOException("維度尚未 init：" + id);
      try (var repo = new DimensionRepository(path, id, false)) {
        var old = IgnoreEditor.read(repo.ignorePath()); var proposed = old; Object data;
        switch (action) {
          case "list" -> data = old.entries();
          case "check" -> data = Map.of("valid", true, "lines", old.lines().size());
          case "test" -> data = IgnoreEditor.test(old, String.join(" ", arguments), EntityTagRegistry.load(layout.world(), layout.dataVersion(), root.packs()));
          case "add", "remove", "move", "disable", "enable" -> {
            if (Files.exists(path.resolve("merge-state.bin"))) throw new IOException("MERGING 期間禁止修改規則");
            proposed = switch (action) {
              case "add" -> old.add(String.join(" ", arguments));
              case "remove" -> old.remove(Integer.parseInt(arguments.getFirst()));
              case "move" -> old.move(Integer.parseInt(arguments.getFirst()), Integer.parseInt(arguments.get(1)));
              default -> old.enabled(Integer.parseInt(arguments.getFirst()), action.equals("enable"));
            };
            data = IgnoreEditor.preview(repo, proposed, EntityTagRegistry.load(layout.world(), layout.dataVersion(), root.packs()));
            root.summary.put("rules", proposed.entries().stream().filter(IgnoreEditor.Line::rule).count());
            root.summary.put("dryRun", dryRun);
            if (proposed.equals(old)) root.outcome = OperationResult.Status.NO_OP;
            if (!dryRun) IgnoreEditor.write(repo, proposed);
          }
          default -> throw new IOException("ignore 動作需為 list|add|remove|move|test|check|disable|enable");
        }
        if (root.format == Format.json) root.json(Map.of("dimension", id, "preview", data, "rules", proposed.entries(), "dryRun", dryRun));
        else {
          var out = root.spec.commandLine().getOut();
          if (action.equals("list")) for (var line : old.entries())
            out.printf("%4d %s%s%n", line.number(), line.rule() ? CliMessages.text(line.enabled() ? "enabled" : "disabled") : "", line.text());
          else if (data instanceof IgnoreEditor.TestResult tested)
            out.println(CliMessages.text(tested.excluded() ? "excluded" : "retained") + (tested.line() == null ? CliMessages.text("no-rule") : CliMessages.text("matched-rule","line",tested.line(),"rule",tested.rule())));
          else if (data instanceof IgnoreEditor.Preview preview)
            out.printf("%s解除追蹤：方塊 %d、方塊實體 %d、實體 %d、生態域樣本 %d、metadata 欄位 %d%n%s%n",
                dryRun ? "預覽（未寫入）— " : "規則已寫入，下次 commit 生效 — ", preview.blocks(), preview.blockEntities(), preview.entities(), preview.biomeSamples(), preview.metadataFields(), String.join("\n",preview.examples()));
          else out.println("規則語法有效，共 " + old.lines().size() + " 行");
        }
        return 0;
      }
    }
  }
  @Command(name = "migrate", mixinStandardHelpOptions = true, description = "世界停止後安全搬移舊 repo")
  static final class Migrate extends Subcommand {
    @Option(names = "--dry-run") boolean dryRun;
    @Override public Integer call() throws Exception {
      var result = RepositoryMigration.migrate(root.layout(), root.selected(), dryRun);
      if(result.stream().allMatch(move -> move.state().equals("NO_OP"))) root.outcome = OperationResult.Status.NO_OP;
      root.summary.put("dimensions",result.size());
      long failures=result.stream().filter(move -> move.state().equals("FAILED")).count();
      if(failures>0) {
        root.outcome=failures==result.size() ? OperationResult.Status.FAILED : OperationResult.Status.PARTIAL;
        root.reportProblem(result.stream().filter(move -> move.error()!=null).map(move -> move.dimension()+": "+move.error()).collect(java.util.stream.Collectors.joining("; ")));
      }
      if (root.format == Format.json) root.json(result); else root.spec.commandLine().getOut().println(result);
      return 0;
    }
  }

}
