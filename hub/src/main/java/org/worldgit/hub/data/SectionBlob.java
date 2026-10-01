package org.worldgit.hub.data;

import java.io.*;
import java.util.*;
import org.worldgit.core.normalize.SnapshotCodec;

/** 重用 core 完整的 section 結構驗證；wire 格式維持緊密位元流。 */
record SectionBlob(String[] palette, int bits, byte[] packed, SortedMap<Integer, byte[]> blockEntities) {
  static SectionBlob parse(byte[] blob) throws IOException {
    var section = SnapshotCodec.section(blob);
    var ids = new LinkedHashMap<String, Integer>();
    for (var state : section.blocks()) ids.computeIfAbsent(state.canonical(), k -> ids.size());
    int bits = ids.size() <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(ids.size() - 1);
    byte[] packed = new byte[(4096 * bits + 7) / 8];
    for (int i = 0; i < 4096; i++) {
      int index = ids.get(section.block(i).canonical());
      for (int b = 0; b < bits; b++) if ((index & (1 << b)) != 0) {
        int off = i * bits + b;
        packed[off >>> 3] |= (byte) (1 << (off & 7));
      }
    }
    return new SectionBlob(ids.keySet().toArray(String[]::new), bits, packed, section.blockEntities());
  }

  int index(int pos) {
    int value = 0;
    for (int b = 0; b < bits; b++) {
      int off = pos * bits + b;
      if ((packed[off >>> 3] & (1 << (off & 7))) != 0) value |= 1 << b;
    }
    return value;
  }

  static String blockName(String state) {
    int i = state.indexOf('[');
    return i < 0 ? state : state.substring(0, i);
  }
}
