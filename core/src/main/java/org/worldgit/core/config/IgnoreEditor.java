package org.worldgit.core.config;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.diff.*;
import org.worldgit.core.merge.TreeFilter;
import org.worldgit.core.service.DimensionRepository;
import org.worldgit.core.normalize.SnapshotCodec;
import org.worldgit.core.store.TreeEditor;

/** 以原始文字行保存註解、空行與順序；編號為 1-based 檔案行號。 */
public final class IgnoreEditor {
  public static final int MAX_BYTES = 256 * 1024, MAX_LINES = 4096;
  private static final String DISABLED = "# worldgit-disabled: ";
  public record Line(int number, String text, boolean rule, boolean enabled) {}
  public record Document(List<String> lines, boolean trailingNewline) {
    public Document { lines = List.copyOf(lines); }
    public String text() { return String.join("\n", lines) + (trailingNewline ? "\n" : ""); }
    public List<Line> entries() {
      var entries = new ArrayList<Line>();
      for (int i = 0; i < lines.size(); i++) {
        String text = lines.get(i), trimmed = text.trim();
        boolean disabled = trimmed.startsWith(DISABLED);
        entries.add(new Line(i + 1, text, disabled || !trimmed.isEmpty() && !trimmed.startsWith("#"), !disabled));
      }
      return List.copyOf(entries);
    }
    public Document add(String rule) throws IOException {
      if (rule.contains("\n") || rule.contains("\r") || rule.trim().isEmpty() || rule.trim().startsWith("#"))
        throw new IOException("新增規則必須是單行純文字規則");
      IgnoreRules.parse(rule); var copy = new ArrayList<>(lines); copy.add(rule);
      return checked(new Document(copy, true));
    }
    public Document remove(int number) throws IOException {
      var copy = new ArrayList<>(lines); copy.remove(index(number)); return checked(new Document(copy, trailingNewline));
    }
    public Document move(int number, int destination) throws IOException {
      int from = index(number), to = index(destination);
      var copy = new ArrayList<>(lines); copy.add(to, copy.remove(from));
      return checked(new Document(copy, trailingNewline));
    }
    public Document enabled(int number, boolean enabled) throws IOException {
      int index = index(number); var copy = new ArrayList<>(lines); String line = copy.get(index);
      int indent = line.length() - line.stripLeading().length();
      if (enabled && line.stripLeading().startsWith(DISABLED)) line = line.substring(0, indent) + line.substring(indent + DISABLED.length());
      else if (!enabled && !line.stripLeading().startsWith(DISABLED)) line = DISABLED + line;
      copy.set(index, line); return checked(new Document(copy, trailingNewline));
    }
    private int index(int number) throws IOException {
      if (number < 1 || number > lines.size()) throw new IOException("規則行號超出範圍：" + number);
      return number - 1;
    }
  }
  public record TestResult(boolean excluded, Integer line, String rule) {}
  public record Preview(long blocks, long blockEntities, long entities, long biomeSamples,
      long metadataFields, List<String> examples) {}
  private IgnoreEditor() {}
  public static Document read(Path path) throws IOException {
    if (Files.exists(path) && Files.size(path) > MAX_BYTES) throw new IOException(".wgignore 超過 256 KiB");
    return parse(Files.exists(path) ? Files.readString(path) : "");
  }
  public static Document parse(String text) throws IOException {
    boolean trailing = text.endsWith("\n");
    var lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
    if (trailing || text.isEmpty()) lines.removeLast();
    return checked(new Document(lines, trailing));
  }
  private static Document checked(Document document) throws IOException {
    if (document.lines().size() > MAX_LINES || document.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new IOException(".wgignore 超過 4096 行／256 KiB");
    IgnoreRules.parse(document.text()); return document;
  }
  public static void write(DimensionRepository repo, Document document) throws IOException {
    repo.requireLegacyComplete();
    if (Files.exists(repo.directory().resolve("merge-state.bin")) || Files.exists(repo.directory().resolve("MERGE_HEAD")))
      throw new IOException("MERGING 期間禁止修改 .wgignore");
    WorldGitConfig.write(repo.ignorePath(), checked(document).text());
  }
  private static TestResult result(IgnoreRules.Decision decision) { return new TestResult(decision.excluded(), decision.line(), decision.rule()); }
  public static TestResult testEntity(Document document, Nbt.Compound entity, EntitySemantics semantics) throws IOException {
    return result(IgnoreRules.parse(document.text()).testEntity(entity, semantics));
  }
  public static TestResult testBlock(Document document, int x, int y, int z) throws IOException {
    return result(IgnoreRules.parse(document.text()).testBlock(x, y, z));
  }
  public static TestResult testField(Document document, String type, String field, boolean builtin) throws IOException {
    return result(IgnoreRules.parse(document.text()).testField(type, field, builtin));
  }
  public static TestResult test(Document document, String selector, EntitySemantics semantics) throws IOException {
    String[] words = selector.trim().split("\\s+");
    if (!Set.of("block", "be", "entity", "field").contains(words[0])) words = new String[]{"block", selector};
    try {
      if (words[0].equals("field")) {
        if (words.length != 3) throw new IllegalArgumentException("field 需要型別與欄位");
        type(words[1]); return testField(document, words[1], words[2], false);
      }
      boolean block = words[0].equals("block"), be = words[0].equals("be");
      if (words.length != (block ? 2 : be ? 4 : 3)) throw new IllegalArgumentException("需要座標 x,y,z；be 另需型別與欄位");
      double[] pos = Arrays.stream(words[block ? 1 : 2].split(",")).mapToDouble(Double::parseDouble).toArray();
      if (pos.length != 3 || Arrays.stream(pos).anyMatch(v -> !Double.isFinite(v) || v < Integer.MIN_VALUE || v > Integer.MAX_VALUE))
        throw new IllegalArgumentException("座標必須是範圍內的有限數字");
      if (block || be) {
        if (Arrays.stream(pos).anyMatch(v -> v != Math.floor(v))) throw new IllegalArgumentException("方塊座標必須是整數");
        var area = testBlock(document, (int)pos[0], (int)pos[1], (int)pos[2]);
        if (block || area.excluded()) return area;
        type(words[1]); return testField(document, words[1], words[3], false);
      }
      type(words[1]);
      return testEntity(document, new Nbt.Compound().with("id", words[1]).with("Pos", new Nbt.ListTag(6, List.of(pos[0],pos[1],pos[2]))), semantics);
    } catch (IllegalArgumentException | IndexOutOfBoundsException ex) { throw new IOException("selector 無效：" + ex.getMessage(), ex); }
  }
  private static void type(String type) {
    if (!type.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("型別必須是 namespaced id");
  }
  public static Preview preview(DimensionRepository repo, Document proposed, EntitySemantics semantics) throws IOException {
    checked(proposed);
    String head = repo.refs().head(); if (head == null) return new Preview(0, 0, 0, 0, 0, List.of());
    String tree = repo.refs().readCommit(head).tree();
    String filtered = TreeFilter.filter(repo.objects(), tree, proposed.text(), semantics);
    repo.objects().flush();
    var diff = new DiffEngine(repo.objects()).compare(repo.dimension(), tree, filtered, 0, DiffEngine.Detail.SUMMARY);
    long bes = 0; var rules = IgnoreRules.parse(proposed.text());
    for (var r : repo.objects().readTree(tree).values()) if (r.name().startsWith("r.") && r.kind() == org.worldgit.core.store.ObjectStore.Kind.TREE)
      for (var c : repo.objects().readTree(r.id()).values()) {
        String[] coordinates = c.name().split("\\.");
        int x = Integer.parseInt(coordinates[1]) * 16, z = Integer.parseInt(coordinates[2]) * 16;
        for (var e : repo.objects().readTree(c.id()).values()) if (e.name().matches("s\\.-?\\d+\\.bin")) {
          int y = Integer.parseInt(e.name().split("\\.")[1]) * 16;
          for (int index : SnapshotCodec.section(repo.objects().readBlob(e.id())).blockEntities().keySet())
            if (rules.ignoredBlock(x + (index & 15), y + (index >> 8), z + ((index >> 4) & 15))) bes++;
        }
      }
    var examples = new ArrayList<String>();
    diff.sections().stream().limit(5).forEach(s -> examples.add(s.chunk() + " section=" + s.sectionY()));
    diff.entities().stream().limit(5).forEach(e -> examples.add("entity " + e.uuid()));
    long fields = 0;
    for (String root : List.of("world-meta", "dimension-meta")) {
      var before = TreeEditor.find(repo.objects(), tree, root);
      var after = TreeEditor.find(repo.objects(), filtered, root);
      if (before == null) continue;
      var next = after == null ? Map.<String,org.worldgit.core.store.ObjectStore.Entry>of() : repo.objects().readTree(after.id());
      for (var file : repo.objects().readTree(before.id()).values()) {
        if (org.worldgit.core.normalize.MetadataNormalizer.type(file.name()) == null) continue;
        var original = Nbt.read(repo.objects().readBlob(file.id()));
        var retained = next.get(file.name());
        var keys = retained == null ? Set.<String>of() : Nbt.read(repo.objects().readBlob(retained.id())).keySet();
        fields += original.keySet().stream().filter(key -> !keys.contains(key)).count();
      }
    }
    long biomes = diff.biomes().stream().mapToLong(b -> b.count()).sum();
    return new Preview(diff.counts().removed(), bes, diff.entities().stream().filter(e -> e.kind() == ChangeKind.REMOVED).count(), biomes,
        fields, List.copyOf(examples));
  }
}
