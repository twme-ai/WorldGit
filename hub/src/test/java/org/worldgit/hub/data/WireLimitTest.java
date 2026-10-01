package org.worldgit.hub.data;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.worldgit.core.normalize.DecodeBudget;

class WireLimitTest {
  @Test void rejectsBeforeWritingPastResponseCap() throws Exception {
    var out = new LimitedBuffer();
    out.write(new byte[LimitedBuffer.MAX_BYTES]);
    assertThrows(DecodeBudget.Exceeded.class, () -> out.write(1));
    assertEquals(LimitedBuffer.MAX_BYTES, out.toByteArray().length);
  }

  @Test void utfLengthCannotWrapItsUnsignedShort() {
    var out = new LimitedBuffer();
    assertThrows(IOException.class, () -> ChunkWire.utf(new DataOutputStream(out), "中".repeat(22000)));
    assertEquals(0, out.toByteArray().length);
  }
}
