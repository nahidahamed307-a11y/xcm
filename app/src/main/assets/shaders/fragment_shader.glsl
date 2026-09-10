#version 300 es
// fragment_shader.glsl - LiveLight Cam OpenGL ES 3.0 Fragment Shader
#extension GL_OES_EGL_image_external_essl3 : require

precision mediump float;
precision mediump sampler3D;

uniform samplerExternalOES u_CameraTexture;
uniform sampler3D u_Lut3D;
uniform float u_PresetIntensity; // 0.0 to 1.0 opacity
uniform int u_HasLut;            // 1 if LUT texture is bound, 0 for bypass

in vec2 v_TexCoord;
out vec4 fragColor;

void main() {
    vec4 cameraColor = texture(u_CameraTexture, v_TexCoord);

    if (u_HasLut == 1 && u_PresetIntensity > 0.001) {
        // Clamp input color to valid [0.0, 1.0] domain
        vec3 rawRgb = clamp(cameraColor.rgb, 0.0, 1.0);

        // Half-texel offset for accurate trilinear filtering in 3D LUT coordinate space
        float lutSize = float(textureSize(u_Lut3D, 0).x);
        vec3 lutCoord = rawRgb * ((lutSize - 1.0) / lutSize) + (0.5 / lutSize);

        vec3 gradedColor = texture(u_Lut3D, lutCoord).rgb;

        // Blend between original camera frame and graded preset frame by u_PresetIntensity
        vec3 finalRgb = mix(cameraColor.rgb, gradedColor, u_PresetIntensity);
        fragColor = vec4(finalRgb, cameraColor.a);
    } else {
        fragColor = cameraColor;
    }
}
