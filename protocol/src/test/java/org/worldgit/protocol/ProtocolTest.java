package org.worldgit.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.worldgit.core.diff.*;
import org.worldgit.core.model.*;

class ProtocolTest {
  private WorldDiff large() {
    var sections = new ArrayList<WorldDiff.SectionChange>();
    for (int y = 0; y < 25; y++) {
      var cells = new ArrayList<WorldDiff.BlockChange>();
      for (int i = 0; i < 4000; i++)
        cells.add(
            new WorldDiff.BlockChange(
                new WorldDiff.BlockPos((i & 15) - 16, y * 16 + (i >> 8), (i >> 4) & 15),
                ChangeKind.REMOVED,
                new BlockState(
                    "minecraft:oak_stairs",
                    new TreeMap<>(Map.of("facing", "east", "half", "bottom"))),
                BlockState.AIR,
                null,
                null));
      sections.add(
          new WorldDiff.SectionChange(
              new ChunkPos(-1, 0), y, ChangeKind.MODIFIED, cells, WorldDiff.Counts.of(cells)));
    }
    return new WorldDiff(DimensionId.OVERWORLD, sections, List.of(), List.of(), List.of());
  }

  @Test
  void split100kReassembleOutOfOrderAndRetransmit() throws Exception {
    var diff = large();
    var packets = Protocol.diff(10, diff);
    assertTrue(packets.size() > 1);
    assertTrue(packets.stream().allMatch(p -> p.length <= 28000));
    var shuffled = new ArrayList<>(packets);
    Collections.reverse(shuffled);
    var assembler = new BatchAssembler();
    BatchAssembler.Completed complete = null;
    for (byte[] bytes : shuffled) {
      var part = Protocol.decode(bytes);
      var result = assembler.accept(part, 1000);
      if (result.isPresent()) complete = result.get();
      else assertTrue(assembler.accept(part, 1001).isEmpty());
    }
    assertNotNull(complete);
    assertEquals(100000, complete.cells().size());
    assertTrue(
        complete.cells().stream()
            .allMatch(c -> c.before().contains("oak_stairs") && c.after().equals("minecraft:air")));
  }

  @Test
  void helloStatusClearAndInvalidPayload() throws Exception {
    var hello =
        new Protocol.Hello(Protocol.VERSION, 123, Protocol.CAPABILITIES, DiffPalette.COLORBLIND);
    assertEquals(hello, Protocol.decode(Protocol.encode(hello)));
    var packets = Protocol.status(2, large());
    var assembler = new BatchAssembler();
    var complete = assembler.accept(Protocol.decode(packets.getFirst()), 1000).orElseThrow();
    assertEquals(25, complete.outlines().size());
    assertEquals(ChangeKind.REMOVED, complete.outlines().getFirst().kind());
    assembler.clear(3);
    assertTrue(assembler.accept(Protocol.decode(packets.getFirst()), 1001).isEmpty());
    assertThrows(IOException.class, () -> Protocol.decode(new byte[28001]));
    assertThrows(IOException.class, () -> Protocol.decode(new byte[] {1, 2}));
    assertThrows(IOException.class, () -> Protocol.decode(new byte[] {2, 2, -1, -1, -1}));
    byte[] extra = Arrays.copyOf(Protocol.encode(hello), Protocol.encode(hello).length + 1);
    assertThrows(IOException.class, () -> Protocol.decode(extra));
  }

  @Test
  void conflictingBatchAndDuplicatePositionsRejected() throws Exception {
    var h = new Protocol.Header(4, 0, 2, 2, DimensionId.OVERWORLD);
    var cell = new Protocol.Cell(0, 0, 0, ChangeKind.ADDED, "minecraft:air", "minecraft:stone");
    var assembler = new BatchAssembler();
    assembler.accept(new Protocol.DiffPart(h, List.of(cell)), 0);
    assertThrows(
        IOException.class,
        () ->
            assembler.accept(
                new Protocol.StatusPart(
                    new Protocol.Header(4, 1, 2, 2, DimensionId.OVERWORLD),
                    List.of(new Protocol.Outline(0, 0, 0, 1, 1, 1, ChangeKind.ADDED, 1, 0, 0, 0))),
                1));
    assertThrows(
        IOException.class,
        () ->
            assembler.accept(
                new Protocol.DiffPart(
                    new Protocol.Header(4, 1, 2, 2, DimensionId.OVERWORLD), List.of(cell)),
                2));
  }
}
