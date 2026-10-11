package org.worldgit.core.version;

import java.io.*;
import java.util.*;
import org.worldgit.core.anvil.Nbt;

/** 損失以種類／來源／替代聚合；座標樣本與總數皆保存，順序可重現。 */
public record ConversionReport(int from, int to, String rules, List<Loss> losses) {
  public record Loss(
      String kind, String source, String replacement, long count, List<String> locations) {
    public Loss {
      locations = List.copyOf(locations);
    }
  }

  public ConversionReport {
    losses = List.copyOf(losses);
  }

  public boolean lossy() {
    return !losses.isEmpty();
  }

  public String label() {
    return (from < to ? "升級到 " : "降級到 ") + MinecraftVersion.name(to);
  }

  public byte[] bytes() {
    var n = new Nbt.Compound().with("from", from).with("to", to).with("rules", rules);
    n.put(
        "losses",
        new Nbt.ListTag(
            10,
            losses.stream()
                .map(
                    l ->
                        (Object)
                            new Nbt.Compound()
                                .with("kind", l.kind)
                                .with("source", l.source)
                                .with("replacement", l.replacement)
                                .with("count", l.count)
                                .with(
                                    "locations", new Nbt.ListTag(8, new ArrayList<>(l.locations))))
                .toList()));
    return Nbt.write(n);
  }

  public static ConversionReport read(byte[] bytes) throws IOException {
    var n = Nbt.read(bytes);
    var losses = new ArrayList<Loss>();
    if (n.list("losses").values().size() > 4096) throw new IOException("轉換損失種類超過 4096");
    for (Object x : n.list("losses").values()) {
      var l = (Nbt.Compound) x;
      losses.add(
          new Loss(
              l.string("kind"),
              l.string("source"),
              l.string("replacement"),
              ((Number) l.get("count")).longValue(),
              l.list("locations").values().stream().map(String.class::cast).toList()));
    }
    return new ConversionReport(
        n.integer("from", 0), n.integer("to", 0), n.string("rules"), losses);
  }

  public void requireAccepted(boolean accepted) throws IOException {
    if (lossy() && !accepted) throw new LossRequired(this);
  }

  public static final class LossRequired extends IOException {
    private final ConversionReport report;

    public LossRequired(ConversionReport report) {
      super("降級會損失內容，需確認 --accept-loss：" + report.losses);
      this.report = report;
    }

    public ConversionReport report() {
      return report;
    }
  }

  public static final class Collector {
    private final SortedMap<String, Loss> values = new TreeMap<>();

    public void add(String kind, String source, String replacement, String location)
        throws IOException {
      String key = kind + "\0" + source + "\0" + replacement;
      var old = values.get(key);
      if (old == null && values.size() >= 4096) throw new IOException("轉換損失種類超過 4096；縮小操作範圍");
      var locations = new ArrayList<>(old == null ? List.<String>of() : old.locations);
      if (locations.size() < 16 && !locations.contains(location)) locations.add(location);
      values.put(
          key, new Loss(kind, source, replacement, old == null ? 1 : old.count + 1, locations));
    }

    public ConversionReport report(int from, int to, String rules) {
      return new ConversionReport(from, to, rules, List.copyOf(values.values()));
    }

    public void merge(ConversionReport report) throws IOException {
      for (var loss : report.losses()) {
        String key = loss.kind + "\0" + loss.source + "\0" + loss.replacement;
        var old = values.get(key);
        if (old == null && values.size() >= 4096) throw new IOException("轉換損失種類超過 4096");
        var locations = new ArrayList<>(old == null ? List.<String>of() : old.locations);
        for (String location : loss.locations)
          if (locations.size() < 16 && !locations.contains(location)) locations.add(location);
        values.put(
            key,
            new Loss(
                loss.kind,
                loss.source,
                loss.replacement,
                Math.addExact(old == null ? 0 : old.count, loss.count),
                locations));
      }
    }
  }
}
