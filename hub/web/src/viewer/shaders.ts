// WebGL2 shader：16 B 頂點（見 vertex.ts）、rect／direct 兩種貼圖模式、diff 上色（doc 06 §1.1）。
export const VERT = `#version 300 es
precision highp float; precision highp int;
layout(location=0) in vec3 aPos;
layout(location=1) in vec2 aUv;
layout(location=2) in uint aRect;
layout(location=3) in uvec4 aMisc;
uniform mat4 uVP;
uniform vec3 uOffset;
out vec3 vLocal;
out vec2 vUv;
flat out uint vRect;
out vec3 vColor;
flat out uint vFlags;
out float vDist;
void main() {
  vec3 p = aPos / (((aMisc.a & 16u) != 0u) ? 16.0 : 256.0);
  vLocal = p;
  vec3 rel = p + uOffset;
  gl_Position = uVP * vec4(rel, 1.0);
  vUv = aUv;
  vRect = aRect;
  vColor = vec3(aMisc.rgb) / 255.0;
  vFlags = aMisc.a;
  vDist = length(rel);
}`

export const FRAG = `#version 300 es
precision highp float; precision highp int;
in vec3 vLocal;
in vec2 vUv;
flat in uint vRect;
in vec3 vColor;
flat in uint vFlags;
in float vDist;
uniform sampler2D uAtlas;
uniform sampler2D uRects;
uniform vec3 uPal[5];
uniform int uMode;      // 0 上色  1 只看變動  2 淡化未變動
uniform int uPass;      // 0 opaque  1 trans  2 ghost
uniform float uTime;
uniform vec3 uFog;
uniform vec2 uFogRange;
out vec4 outColor;

// 面內兩軸到最近整數（方塊邊界）的距離：e = 較近那條邊的像素距離；otherG = 沿該邊另一軸到最近角落的距離（格）
void edge(vec3 n, out float e, out float otherG) {
  vec3 g = abs(fract(vLocal + 0.5) - 0.5);
  vec3 w = max(fwidth(vLocal), vec3(1e-4));
  float e0 = 1e9, e1 = 1e9, g0 = 0.0, g1 = 0.0;
  int k = 0;
  for (int i = 0; i < 3; i++) {
    if (n[i] < 0.5) {
      float d = g[i] / w[i];
      if (k == 0) { e0 = d; g0 = g[i]; } else { e1 = d; g1 = g[i]; }
      k++;
    }
  }
  if (e0 <= e1) { e = e0; otherG = g1; } else { e = e1; otherG = g0; }
}

void main() {
  uint kind = vFlags & 7u;
  if (uMode == 1 && kind == 0u && (vFlags & 32u) == 0u) discard;
  vec4 tex;
  if ((vFlags & 16u) != 0u) tex = vec4(1.0);
  else if (vRect == 65535u) tex = texture(uAtlas, vUv / 65535.0);
  else {
    int ri = int(vRect);
    vec4 r = texelFetch(uRects, ivec2(ri & 2047, ri >> 11), 0);
    tex = texture(uAtlas, mix(r.xy, r.zw, fract(vUv / 256.0)));
  }
  if (tex.a < 0.1) discard;
  vec3 col = tex.rgb * vColor;
  float alpha = 1.0;
  if (uPass == 1) alpha = ((vFlags & 8u) != 0u ? 0.72 : 0.9) * tex.a;
  if (uPass == 2) alpha = 0.42;
  if (kind > 0u) {
    vec3 pc = uPal[int(kind)];
    vec3 n = abs(normalize(cross(dFdx(vLocal), dFdy(vLocal))));
    float e, otherG;
    edge(n, e, otherG);
    float line = 1.0 - smoothstep(0.9, 1.9, e);
    float tint = 0.32;
    if (kind == 1u) {
      // 新增：實心外框
    } else if (kind == 2u) {
      // 移除：半透明鬼影，外框略弱
      tint = 0.6;
      line *= 0.9;
    } else if (kind == 3u) {
      // 修改：角標（只在方塊角落附近畫外框）
      line *= 1.0 - smoothstep(0.26, 0.34, otherG);
    } else {
      // 衝突：閃爍
      float pulse = 0.5 + 0.5 * sin(uTime * 4.0);
      tint = 0.25 + 0.4 * pulse;
      line *= 0.5 + 0.5 * pulse;
    }
    col = mix(col, pc, tint);
    col = mix(col, pc, clamp(line, 0.0, 1.0));
    if (uPass == 2) alpha = mix(0.42, 0.95, clamp(line, 0.0, 1.0));
  } else if (uMode == 2) {
    float l = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(vec3(l), col, 0.25) * 0.55;
  }
  float f = smoothstep(uFogRange.x, uFogRange.y, vDist);
  col = mix(col, uFog, f);
  outColor = vec4(col, alpha);
}`

export const LINE_VERT = `#version 300 es
precision highp float;
layout(location=0) in vec3 aPos;
layout(location=1) in vec4 aColor;
uniform mat4 uVP;
uniform vec3 uOffset;
out vec4 vColor;
void main() { vColor = aColor; gl_Position = uVP * vec4(aPos + uOffset, 1.0); }`

export const LINE_FRAG = `#version 300 es
precision highp float;
in vec4 vColor;
out vec4 outColor;
void main() { outColor = vColor; }`
