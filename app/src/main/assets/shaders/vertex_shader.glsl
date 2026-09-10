#version 300 es
// vertex_shader.glsl - LiveLight Cam OpenGL ES 3.0 Vertex Shader

layout(location = 0) in vec4 a_Position;
layout(location = 1) in vec4 a_TexCoord;

uniform mat4 u_TextureMatrix;

out vec2 v_TexCoord;

void main() {
    gl_Position = a_Position;
    // Transform external camera texture coordinates via SurfaceTexture matrix
    v_TexCoord = (u_TextureMatrix * a_TexCoord).xy;
}
