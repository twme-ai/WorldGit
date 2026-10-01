#version 330
uniform sampler2D Sampler0;
in vec2 texCoord0;
in vec4 vertexColor;
out vec4 fragColor;
void main() { vec4 c = texture(Sampler0, texCoord0) * vertexColor; if(c.a < 0.01) discard; fragColor = c; }
