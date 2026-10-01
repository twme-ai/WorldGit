package org.worldgit.fabric.client;

import org.joml.Matrix4f;

/** 版本轉接層在世界渲染的「主要繪製」階段呼叫：view 為視角旋轉矩陣，(cx,cy,cz) 為相機世界座標。 */
@FunctionalInterface
interface RenderCallback {
  void render(Matrix4f view, double cx, double cy, double cz);
}
