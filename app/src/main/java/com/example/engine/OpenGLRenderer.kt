package com.example.engine

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.example.model.PresetData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 3.0 Renderer for zero-lag camera preview and 3D LUT color grading.
 */
class OpenGLRenderer(
    private val context: Context,
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private var surfaceTexture: SurfaceTexture? = null
    private var glSurfaceView: GLSurfaceView? = null

    private var cameraTexId = 0
    private var lutTexId = 0
    private var programId = 0

    // Shader uniform and attribute locations
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTextureMatrixLoc = 0
    private var uCameraTextureLoc = 0
    private var uLut3dLoc = 0
    private var uPresetIntensityLoc = 0
    private var uHasLutLoc = 0

    private val transformMatrix = FloatArray(16)

    @Volatile
    var presetIntensity: Float = 1.0f

    @Volatile
    private var pendingPreset: PresetData? = null
    private var currentPreset: PresetData = PresetData.ORIGINAL
    private var hasLut = false

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texCoordBuffer: FloatBuffer

    fun setGlSurfaceView(view: GLSurfaceView) {
        this.glSurfaceView = view
    }

    /**
     * Set active preset to be color-graded on the GPU.
     */
    fun updatePreset(preset: PresetData) {
        this.pendingPreset = preset
        glSurfaceView?.requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        initGeometry()
        initShaders()
        initCameraTexture()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        // Check if preset needs updating on GL thread
        val preset = pendingPreset
        if (preset != null) {
            pendingPreset = null
            applyPresetOnGlThread(preset)
        }

        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(transformMatrix)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating texture image: ${e.message}")
            return
        }

        if (programId == 0) return

        GLES30.glUseProgram(programId)

        // Set vertices and tex coords
        GLES30.glEnableVertexAttribArray(aPositionLoc)
        GLES30.glVertexAttribPointer(aPositionLoc, 2, GLES30.GL_FLOAT, false, 0, vertexBuffer)

        GLES30.glEnableVertexAttribArray(aTexCoordLoc)
        GLES30.glVertexAttribPointer(aTexCoordLoc, 2, GLES30.GL_FLOAT, false, 0, texCoordBuffer)

        // Transform matrix
        GLES30.glUniformMatrix4fv(uTextureMatrixLoc, 1, false, transformMatrix, 0)

        // Bind Camera External OES texture
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES30.glUniform1i(uCameraTextureLoc, 0)

        // Bind 3D LUT texture if active
        if (hasLut && lutTexId > 0 && currentPreset != PresetData.ORIGINAL) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexId)
            GLES30.glUniform1i(uLut3dLoc, 1)
            GLES30.glUniform1i(uHasLutLoc, 1)
        } else {
            GLES30.glUniform1i(uHasLutLoc, 0)
        }

        // Intensity
        GLES30.glUniform1f(uPresetIntensityLoc, presetIntensity)

        // Draw quad
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(aPositionLoc)
        GLES30.glDisableVertexAttribArray(aTexCoordLoc)
        GLES30.glUseProgram(0)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        glSurfaceView?.requestRender()
    }

    private fun initGeometry() {
        val quadVertices = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
        )
        val quadTexCoords = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
        )

        vertexBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quadVertices)
        vertexBuffer.position(0)

        texCoordBuffer = ByteBuffer.allocateDirect(quadTexCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quadTexCoords)
        texCoordBuffer.position(0)
    }

    private fun initShaders() {
        val vShaderSource = loadShaderFromAssets("shaders/vertex_shader.glsl")
            ?: FALLBACK_VERTEX_SHADER
        val fShaderSource = loadShaderFromAssets("shaders/fragment_shader.glsl")
            ?: FALLBACK_FRAGMENT_SHADER

        val vShader = compileShader(GLES30.GL_VERTEX_SHADER, vShaderSource)
        val fShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fShaderSource)

        if (vShader == 0 || fShader == 0) {
            Log.e(TAG, "Failed compiling shaders")
            return
        }

        programId = GLES30.glCreateProgram()
        GLES30.glAttachShader(programId, vShader)
        GLES30.glAttachShader(programId, fShader)
        GLES30.glLinkProgram(programId)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(programId, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(programId)
            Log.e(TAG, "Shader program linking failed: $log")
            GLES30.glDeleteProgram(programId)
            programId = 0
            return
        }

        aPositionLoc = GLES30.glGetAttribLocation(programId, "a_Position")
        aTexCoordLoc = GLES30.glGetAttribLocation(programId, "a_TexCoord")
        uTextureMatrixLoc = GLES30.glGetUniformLocation(programId, "u_TextureMatrix")
        uCameraTextureLoc = GLES30.glGetUniformLocation(programId, "u_CameraTexture")
        uLut3dLoc = GLES30.glGetUniformLocation(programId, "u_Lut3D")
        uPresetIntensityLoc = GLES30.glGetUniformLocation(programId, "u_PresetIntensity")
        uHasLutLoc = GLES30.glGetUniformLocation(programId, "u_HasLut")
    }

    private fun initCameraTexture() {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        cameraTexId = textures[0]

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(cameraTexId)
        st.setOnFrameAvailableListener(this)
        this.surfaceTexture = st

        onSurfaceTextureReady(st)
    }

    private fun applyPresetOnGlThread(preset: PresetData) {
        currentPreset = preset
        if (preset == PresetData.ORIGINAL) {
            hasLut = false
            return
        }

        try {
            val lutBuffer = LutGenerator.generate3dLutBuffer(preset)
            lutTexId = LutGenerator.upload3dLutTexture(lutBuffer, lutTexId)
            hasLut = true
            Log.d(TAG, "Uploaded 3D LUT for preset: ${preset.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error generating 3D LUT for ${preset.name}: ${e.message}")
            hasLut = false
        }
    }

    private fun loadShaderFromAssets(filename: String): String? {
        return try {
            context.assets.open(filename).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load $filename from assets: ${e.message}")
            null
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            Log.e(TAG, "Error compiling shader (type $type): $log")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private const val TAG = "OpenGLRenderer"

        private const val FALLBACK_VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec4 a_Position;
layout(location = 1) in vec4 a_TexCoord;
uniform mat4 u_TextureMatrix;
out vec2 v_TexCoord;
void main() {
    gl_Position = a_Position;
    v_TexCoord = (u_TextureMatrix * a_TexCoord).xy;
}
"""

        private const val FALLBACK_FRAGMENT_SHADER = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
precision mediump sampler3D;

uniform samplerExternalOES u_CameraTexture;
uniform sampler3D u_Lut3D;
uniform float u_PresetIntensity;
uniform int u_HasLut;

in vec2 v_TexCoord;
out vec4 fragColor;

void main() {
    vec4 cameraColor = texture(u_CameraTexture, v_TexCoord);
    if (u_HasLut == 1 && u_PresetIntensity > 0.001) {
        vec3 rawRgb = clamp(cameraColor.rgb, 0.0, 1.0);
        float lutSize = float(textureSize(u_Lut3D, 0).x);
        vec3 lutCoord = rawRgb * ((lutSize - 1.0) / lutSize) + (0.5 / lutSize);
        vec3 graded = texture(u_Lut3D, lutCoord).rgb;
        fragColor = vec4(mix(cameraColor.rgb, graded, u_PresetIntensity), cameraColor.a);
    } else {
        fragColor = cameraColor;
    }
}
"""
    }
}
