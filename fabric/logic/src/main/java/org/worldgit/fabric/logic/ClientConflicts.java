package org.worldgit.fabric.logic;

import java.io.IOException;
import java.util.*;
import org.worldgit.core.merge.MergeReport.Choice;
import org.worldgit.core.model.DimensionId;
import org.worldgit.protocol.MergeProtocol;

/** 每連線／channel／維度分包，clear floor 跨維度；選擇改變時不發布晚到的另一候選。 */
public final class ClientConflicts {
  public record Key(DimensionId dimension, int region) {}
  public record Region(Key key, MergeProtocol.RegionInfo info, List<org.worldgit.core.merge.MergeReport.Cell> hints) {
    public Region { hints = List.copyOf(hints); }
  }
  private record Stream(DimensionId dimension, MergeProtocol.Type type) {}
  private final Map<Stream, MergeProtocol.Assembler> streams = new HashMap<>();
  private final Map<DimensionId, MergeProtocol.Completed> lists = new TreeMap<>();
  private long floor = -1, seen = -1, generation;
  private Key selected;
  private Choice requested;
  private MergeProtocol.Completed preview;

  public Optional<MergeProtocol.Completed> accept(String channel, byte[] bytes, long now) throws IOException {
    var part = MergeProtocol.decode(bytes);
    var type = channel.equals(MergeProtocol.REGIONS) ? MergeProtocol.Type.REGIONS
        : channel.equals(MergeProtocol.PREVIEW) ? MergeProtocol.Type.PREVIEW : null;
    if (part.type() != type) throw new IOException("合併 channel/type 不符");
    if (part.preview() <= floor) return Optional.empty();
    seen = Math.max(seen, part.preview());
    var assembler = streams.computeIfAbsent(new Stream(part.dimension(), type), k -> new MergeProtocol.Assembler());
    assembler.clear(floor);
    Optional<MergeProtocol.Completed> done;
    try { done = assembler.accept(part, now); }
    catch (IOException ex) { assembler.clear(part.preview()); throw ex; }
    if (done.isEmpty()) return done;
    var value = done.get();
    if (type == MergeProtocol.Type.REGIONS) {
      lists.put(value.dimension(), value);
      if (selected != null && region(selected) == null) { selected = null; requested = null; preview = null; }
      generation++;
    } else {
      if (selected == null || !selected.dimension().equals(value.dimension())
          || selected.region() != value.region() || requested != value.choice()) return Optional.empty();
      preview = value;
    }
    return done;
  }
  public List<Region> regions() {
    var out = new ArrayList<Region>();
    for (var list : lists.values()) for (var r : list.regions()) {
      var b = r.bounds();
      var hints = b == null ? List.<org.worldgit.core.merge.MergeReport.Cell>of() : list.updateShapes().stream()
          .filter(c -> c.x() >= (long)b.minX()-1 && c.x() <= (long)b.maxX()+1
              && c.y() >= (long)b.minY()-1 && c.y() <= (long)b.maxY()+1
              && c.z() >= (long)b.minZ()-1 && c.z() <= (long)b.maxZ()+1).toList();
      out.add(new Region(new Key(list.dimension(), r.id()), r, hints));
    }
    return List.copyOf(out);
  }
  public Region region(Key key) { return regions().stream().filter(r -> r.key().equals(key)).findFirst().orElse(null); }
  public void select(Key key) { selected = key; requested = null; preview = null; generation++; }
  public void request(Choice choice) { requested = choice; preview = null; }
  public Key selected() { return selected; }
  public Choice requested() { return requested; }
  public MergeProtocol.Completed preview() { return preview; }
  public long generation() { return generation; }
  public void clear(long id) {
    floor = Math.max(floor, id); streams.values().forEach(a -> a.clear(floor));
    lists.values().removeIf(c -> c.preview() <= floor);
    if (preview != null && preview.preview() <= floor) preview = null;
    generation++;
  }
  public void clear() { clear(seen); requested = null; selected = null; }
  public void reset() { streams.clear(); lists.clear(); preview = null; selected = null; requested = null; floor = seen = -1; generation++; }
}
