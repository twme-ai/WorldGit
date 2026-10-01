package org.worldgit.protocol;

import java.io.*;
import java.util.*;

/** 每個連線一個 assembler；reset 用於重連。重複分包須相同，允許安全重傳。 */
public final class BatchAssembler {
  private long floor = -1, started;
  private Protocol.Header header;
  private final Map<Integer, Protocol.Message> pending = new HashMap<>();
  private int received, receivedBytes;
  private Class<?> type;

  public void reset() {
    floor = -1;
    discard();
  }

  private void discard() {
    pending.clear();
    header = null;
    received = 0;
    receivedBytes = 0;
    type = null;
  }

  public void clear(long preview) {
    floor = Math.max(floor, preview);
    if (header != null && header.preview() <= floor) discard();
  }

  public record Completed(
      Protocol.Header header, List<Protocol.Cell> cells, List<Protocol.Outline> outlines) {
    public Completed {
      cells = List.copyOf(cells);
      outlines = List.copyOf(outlines);
    }
  }

  public Optional<Completed> accept(Protocol.Message message, long nowMillis) throws IOException {
    if (message instanceof Protocol.Clear c) {
      clear(c.preview());
      return Optional.empty();
    }
    Protocol.Header h;
    int count;
    if (message instanceof Protocol.DiffPart d) {
      h = d.header();
      count = d.cells().size();
    } else if (message instanceof Protocol.StatusPart s) {
      h = s.header();
      count = s.outlines().size();
    } else throw new IOException("assembler 只接受分包或 clear");
    if (h.preview() <= floor) return Optional.empty();
    if (header != null && nowMillis - started > 30_000) discard();
    if (header == null || h.preview() > header.preview()) {
      discard();
      header = h;
      type = message.getClass();
      started = nowMillis;
    } else if (h.preview() < header.preview()) return Optional.empty();
    if (type != message.getClass()
        || h.parts() != header.parts()
        || h.totalEntries() != header.totalEntries()
        || !h.dimension().equals(header.dimension())) throw new IOException("分包 header 不一致");
    var prior = pending.get(h.sequence());
    if (prior != null) {
      if (!prior.equals(message)) throw new IOException("重傳內容不一致");
      return Optional.empty();
    }
    int bytes = Protocol.encode(message).length;
    if (receivedBytes + bytes > 8 * 1024 * 1024) throw new IOException("批次超過 8 MiB，請縮小顯示區域");
    receivedBytes += bytes;
    if (received + count > h.totalEntries()) throw new IOException("批次 entry 數超過限制");
    pending.put(h.sequence(), message);
    received += count;
    if (pending.size() != h.parts()) return Optional.empty();
    if (received != h.totalEntries()) throw new IOException("批次 entry 數不符");
    var cells = new ArrayList<Protocol.Cell>();
    var outlines = new ArrayList<Protocol.Outline>();
    var positions = new HashSet<String>();
    for (int i = 0; i < h.parts(); i++) {
      var part = pending.get(i);
      if (part instanceof Protocol.DiffPart d)
        for (var cell : d.cells()) {
          if (!positions.add(cell.x() + "," + cell.y() + "," + cell.z()))
            throw new IOException("重複 cell 座標");
          cells.add(cell);
        }
      else if (part instanceof Protocol.StatusPart s) outlines.addAll(s.outlines());
    }
    var complete = new Completed(header, cells, outlines);
    floor = h.preview();
    discard();
    return Optional.of(complete);
  }
}
