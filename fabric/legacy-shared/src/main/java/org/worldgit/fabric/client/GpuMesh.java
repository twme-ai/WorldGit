package org.worldgit.fabric.client;

import com.mojang.blaze3d.vertex.*;

/** 舊版 VertexBuffer；頂點仍使用同一份線框／模型幾何資料。 */
record GpuMesh(VertexBuffer buffer, int vertices, boolean textured, boolean pulse, int ox, int oy, int oz) implements AutoCloseable {
  static GpuMesh upload(VertexBuilder builder, boolean textured, boolean pulse, int ox, int oy, int oz) {
    if (builder.vertices() == 0) return null;
    var data = builder.flip(); var nativeBuilder = new BufferBuilder(data.remaining());
    nativeBuilder.begin(textured ? VertexFormat.Mode.QUADS : VertexFormat.Mode.DEBUG_LINES,
        textured ? DefaultVertexFormat.POSITION_TEX_COLOR : DefaultVertexFormat.POSITION_COLOR);
    while (data.hasRemaining()) {
      float x = data.getFloat(), y = data.getFloat(), z = data.getFloat();
      var vertex = nativeBuilder.vertex(x, y, z);
      if (textured) vertex = vertex.uv(data.getFloat(), data.getFloat());
      vertex.color(Byte.toUnsignedInt(data.get()), Byte.toUnsignedInt(data.get()), Byte.toUnsignedInt(data.get()), Byte.toUnsignedInt(data.get())).endVertex();
    }
    var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC); buffer.bind(); buffer.upload(nativeBuilder.end()); VertexBuffer.unbind();
    return new GpuMesh(buffer, builder.vertices(), textured, pulse, ox, oy, oz);
  }
  long bytes() { return (long) vertices * (textured ? 24 : 16); }
  public void close() { buffer.close(); }
}
