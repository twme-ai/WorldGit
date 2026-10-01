package org.worldgit.fabric.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;

/** 上傳到 GPU 的一塊靜態頂點資料；原點 (ox,oy,oz) 讓頂點座標保持小數值（float 精度）。 */
record GpuMesh(GpuBuffer buffer, int vertices, boolean textured, boolean pulse, int ox, int oy, int oz) implements AutoCloseable {
  static GpuMesh upload(VertexBuilder builder, boolean textured, boolean pulse, int ox, int oy, int oz) {
    if (builder.vertices() == 0) return null;
    var buffer =
        RenderSystem.getDevice()
            .createBuffer(() -> "WorldGit preview", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, builder.flip());
    return new GpuMesh(buffer, builder.vertices(), textured, pulse, ox, oy, oz);
  }

  long bytes() {
    return (long) vertices * (textured ? 24 : 16);
  }

  @Override
  public void close() {
    buffer.close();
  }
}
