package org.worldgit.hub.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import org.worldgit.core.normalize.DecodeBudget;

/** 在 JSON 輸出串流配置更多空間前限制回應；compare／branches 共用 4 MiB 上限。 */
final class BoundedJson {
  static final int MAX_BYTES = 4 << 20;
  private BoundedJson() {}

  static byte[] encode(ObjectMapper json, Object value) throws IOException {
    var bytes = new ByteArrayOutputStream();
    OutputStream bounded = new OutputStream() {
      private void check(int n) throws IOException {
        if ((long) bytes.size() + n > MAX_BYTES) throw new DecodeBudget.Exceeded("JSON 回應上限 4 MiB");
      }
      @Override public void write(int b) throws IOException { check(1); bytes.write(b); }
      @Override public void write(byte[] b, int off, int len) throws IOException { check(len); bytes.write(b, off, len); }
    };
    try { json.writeValue(bounded, value); }
    catch (IOException e) {
      for (Throwable cause = e; cause != null; cause = cause.getCause())
        if (cause instanceof DecodeBudget.Exceeded exceeded) throw exceeded;
      throw e;
    }
    return bytes.toByteArray();
  }
}
