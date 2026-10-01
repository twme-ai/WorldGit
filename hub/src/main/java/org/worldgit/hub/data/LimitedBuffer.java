package org.worldgit.hub.data;

import java.io.*;
import org.worldgit.core.normalize.DecodeBudget;

/** chunk／diff wire 在寫入前限制配置；與視窗、DecodeBudget 同時生效。 */
final class LimitedBuffer extends OutputStream {
  static final int MAX_BYTES = 16 << 20;
  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private void check(int n) throws IOException {
    if ((long) bytes.size() + n > MAX_BYTES) throw new DecodeBudget.Exceeded("3D 回應上限 16 MiB");
  }
  @Override public void write(int b) throws IOException { check(1); bytes.write(b); }
  @Override public void write(byte[] b, int off, int len) throws IOException { check(len); bytes.write(b, off, len); }
  void writeTo(OutputStream out) throws IOException { bytes.writeTo(out); }
  byte[] toByteArray() { return bytes.toByteArray(); }
}
