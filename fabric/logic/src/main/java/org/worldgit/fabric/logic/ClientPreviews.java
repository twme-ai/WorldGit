package org.worldgit.fabric.logic;

import java.io.IOException;
import java.util.*;
import org.worldgit.core.model.DimensionId;
import org.worldgit.protocol.BatchAssembler;
import org.worldgit.protocol.Protocol;

/**
 * 客戶端預覽接收：每個維度一個 BatchAssembler（preview id 單調、收齊才發布、亂序／重傳／逾時由
 * assembler 處理）。clear 取消所有維度較舊的預覽；重連 reset。不依賴 Minecraft，可單元測試。
 */
public final class ClientPreviews {
  /** 一個完整發布的預覽：diff 模式有 cells，status 模式有 outlines。 */
  public record Published(
      DimensionId dimension,
      long previewId,
      List<Protocol.Cell> cells,
      List<Protocol.Outline> outlines) {
    public Published {
      cells = List.copyOf(cells);
      outlines = List.copyOf(outlines);
    }

    public boolean ghost() {
      return !cells.isEmpty();
    }
  }

  /** 接收結果：發布新預覽，或清除（clearedUpTo ≥ 0）。 */
  public record Result(Published published, long clearedUpTo) {
    static final Result NOTHING = new Result(null, -1);
  }

  private final Map<DimensionId, BatchAssembler> assemblers = new HashMap<>();
  private final Map<DimensionId, Long> current = new HashMap<>();
  private long clearedUpTo = -1;
  private long seenUpTo = -1;

  public synchronized Result accept(Protocol.Message message, long nowMillis) throws IOException {
    if (message instanceof Protocol.Clear c) {
      clearedUpTo = Math.max(clearedUpTo, c.preview());
      assemblers.values().forEach(a -> a.clear(c.preview()));
      // 新的 preview 在 clear 之前就已發布的，一律視為被清除。
      current.values().removeIf(id -> id <= c.preview());
      return new Result(null, c.preview());
    }
    Protocol.Header header =
        switch (message) {
          case Protocol.DiffPart d -> d.header();
          case Protocol.StatusPart s -> s.header();
          default -> throw new IOException("預覽只接受 diff／status／clear");
        };
    if (header.preview() <= clearedUpTo) return Result.NOTHING;
    if (header.preview() <= current.getOrDefault(header.dimension(), -1L)) return Result.NOTHING;
    seenUpTo = Math.max(seenUpTo, header.preview());
    var assembler = assemblers.computeIfAbsent(header.dimension(), k -> new BatchAssembler());
    try {
      var done = assembler.accept(message, nowMillis);
      if (done.isEmpty()) return Result.NOTHING;
      var c = done.get();
      current.put(header.dimension(), c.header().preview());
      return new Result(
          new Published(c.header().dimension(), c.header().preview(), c.cells(), c.outlines()), -1);
    } catch (IOException e) {
      assembler.reset();
      throw e;
    }
  }

  public synchronized void reset() {
    assemblers.values().forEach(BatchAssembler::reset);
    assemblers.clear();
    current.clear();
    clearedUpTo = -1;
    seenUpTo = -1;
  }

  /** 本機 clear 也取消已看到但尚未收齊的批次，保留 floor 到斷線為止。 */
  public synchronized void clear() {
    clearedUpTo = Math.max(clearedUpTo, seenUpTo);
    assemblers.values().forEach(a -> a.clear(clearedUpTo));
    current.clear();
  }

  public synchronized OptionalLong currentPreview(DimensionId dimension) {
    Long id = current.get(dimension);
    return id == null ? OptionalLong.empty() : OptionalLong.of(id);
  }
}
