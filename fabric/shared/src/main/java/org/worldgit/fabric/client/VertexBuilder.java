package org.worldgit.fabric.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

/** 可成長的 off-heap 頂點緩衝（上傳到 GPU 後即釋放）。 */
final class VertexBuilder implements AutoCloseable {
  private ByteBuffer buffer;
  private int vertices;

  VertexBuilder(int initialBytes) {
    buffer = MemoryUtil.memAlloc(Math.max(initialBytes, 256)).order(ByteOrder.nativeOrder());
  }

  private void ensure(int more) {
    if (buffer.remaining() >= more) return;
    int size = Math.max(buffer.capacity() * 2, buffer.position() + more);
    buffer = MemoryUtil.memRealloc(buffer, size).order(ByteOrder.nativeOrder());
  }

  /** 線段頂點：position(3f) + color(4 byte)。 */
  void line(float x, float y, float z, int rgb) {
    ensure(16);
    buffer.putFloat(x).putFloat(y).putFloat(z);
    color(rgb, 255);
    vertices++;
  }

  /** 貼圖頂點：position(3f) + uv(2f) + color(4 byte)。 */
  void textured(float x, float y, float z, float u, float v, int rgb, int alpha) {
    ensure(24);
    buffer.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
    color(rgb, alpha);
    vertices++;
  }

  private void color(int rgb, int alpha) {
    buffer.put((byte) (rgb >> 16)).put((byte) (rgb >> 8)).put((byte) rgb).put((byte) alpha);
  }

  int vertices() {
    return vertices;
  }

  /** 供上傳；呼叫後不可再寫入。 */
  ByteBuffer flip() {
    buffer.flip();
    return buffer;
  }

  @Override
  public void close() {
    if (buffer != null) MemoryUtil.memFree(buffer);
    buffer = null;
  }
}
