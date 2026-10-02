package org.worldgit.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.apply.BlockBox;
import org.worldgit.core.merge.MergeReport.*;
import org.worldgit.core.model.DimensionId;

class MergeProtocolTest {
  @Test
  void fragmentedPreviewAtomicBeRoundTripAndClear() throws Exception {
    byte[] be =
        Nbt.write(
            new Nbt.Compound().with("id", "minecraft:chest").with("payload", new byte[50_000]));
    var cells = List.of(new MergeProtocol.PreviewCell(new Cell(0, 64, 0), "minecraft:chest", be));
    var packets = MergeProtocol.preview(1, DimensionId.OVERWORLD, 7, Choice.THEIRS, cells);
    assertTrue(packets.size() > 1);
    var assembler = new MergeProtocol.Assembler();
    MergeProtocol.Completed completed = null;
    for (int i = packets.size() - 1; i >= 0; i--) {
      assertTrue(packets.get(i).length <= Protocol.MAX_PAYLOAD);
      var part = MergeProtocol.decode(packets.get(i));
      var result = assembler.accept(part, 0);
      if (i != 0) assertTrue(result.isEmpty());
      else completed = result.orElseThrow();
    }
    assertEquals(7, completed.region());
    assertEquals(Choice.THEIRS, completed.choice());
    assertArrayEquals(be, completed.cells().getFirst().blockEntity());
    assembler.clear(3);
    assertTrue(assembler.accept(MergeProtocol.decode(packets.getFirst()), 1).isEmpty());
  }

  @Test
  void regionListBoundsStatusAndOptionalChannel() throws Exception {
    var r =
        new Region(
            1,
            DimensionId.OVERWORLD,
            new BlockBox(1, 2, 3, 4, 5, 6),
            9,
            List.of("A"),
            List.of("B"),
            true,
            Choice.BASE,
            true,
            List.of());
    var packets = MergeProtocol.regions(1, DimensionId.OVERWORLD, List.of(r));
    var result =
        new MergeProtocol.Assembler()
            .accept(MergeProtocol.decode(packets.getFirst()), 0)
            .orElseThrow();
    assertEquals(1, result.regions().size());
    assertTrue(result.regions().getFirst().resolved());
    assertEquals(Choice.BASE, result.regions().getFirst().choice());
    assertFalse(Protocol.CAPABILITIES.contains(MergeProtocol.CAPABILITY));
    assertNotEquals(Protocol.DIFF, MergeProtocol.PREVIEW);
  }

  @Test
  void rejectsBadVersionLengthDuplicateAndMixedHeader() throws Exception {
    var p = MergeProtocol.regions(2, DimensionId.OVERWORLD, List.of()).getFirst();
    p[0] = 42;
    assertThrows(IOException.class, () -> MergeProtocol.decode(p));
    assertThrows(IOException.class, () -> MergeProtocol.decode(new byte[28_001]));
    var a = new MergeProtocol.Assembler();
    var first =
        new MergeProtocol.Part(
            1, MergeProtocol.Type.REGIONS, 4, 0, 2, 2, DimensionId.OVERWORLD, new byte[] {1});
    a.accept(first, 0);
    assertThrows(
        IOException.class,
        () ->
            a.accept(
                new MergeProtocol.Part(
                    1,
                    MergeProtocol.Type.PREVIEW,
                    4,
                    1,
                    2,
                    2,
                    DimensionId.OVERWORLD,
                    new byte[] {2}),
                0));
    assertThrows(
        IOException.class,
        () ->
            a.accept(
                new MergeProtocol.Part(
                    1,
                    MergeProtocol.Type.REGIONS,
                    4,
                    0,
                    2,
                    2,
                    DimensionId.OVERWORLD,
                    new byte[] {2}),
                0));
  }
}
