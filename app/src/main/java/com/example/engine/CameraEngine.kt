package com.example.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.model.PresetData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraEngine coordinates CameraX lifecycle, OpenGL preview streaming, and photo capture.
 */
class CameraEngine(private val context: Context) {

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null

    private var activeSurfaceTexture: SurfaceTexture? = null
    private var currentLifecycleOwner: LifecycleOwner? = null

    var lensFacing: Int = CameraSelector.LENS_FACING_BACK
        private set

    var flashMode: Int = ImageCapture.FLASH_MODE_OFF
        private set

    fun initialize(onReady: () -> Unit = {}) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
                onReady()
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing CameraProvider: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Attaches OpenGL ES SurfaceTexture and binds CameraX use cases.
     */
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceTexture: SurfaceTexture,
        onCameraBound: () -> Unit = {}
    ) {
        this.currentLifecycleOwner = lifecycleOwner
        this.activeSurfaceTexture = surfaceTexture

        val provider = cameraProvider ?: run {
            Log.w(TAG, "CameraProvider not ready yet, deferring start")
            return
        }

        try {
            provider.unbindAll()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            // Preview setup with direct OpenGL SurfaceProvider
            preview = Preview.Builder().build().also { prev ->
                prev.setSurfaceProvider(cameraExecutor) { request ->
                    val resolution = request.resolution
                    surfaceTexture.setDefaultBufferSize(resolution.width, resolution.height)
                    val surface = Surface(surfaceTexture)
                    request.provideSurface(surface, cameraExecutor) {
                        surface.release()
                    }
                }
            }

            // High-res photo capture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(flashMode)
                .build()

            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )

            onCameraBound()
            Log.d(TAG, "Camera started successfully with facing: $lensFacing")
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed: ${e.message}", e)
        }
    }

    /**
     * Toggles between front and back camera lenses.
     */
    fun flipCamera(lifecycleOwner: LifecycleOwner) {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val st = activeSurfaceTexture ?: return
        startCamera(lifecycleOwner, st)
    }

    /**
     * Cycles through Flash Modes: OFF -> AUTO -> ON.
     */
    fun toggleFlash(): Int {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        return flashMode
    }

    /**
     * Captures a high-resolution photo, applies the active preset grade, and saves it to MediaStore.
     */
    fun capturePhoto(
        preset: PresetData,
        presetIntensity: Float,
        saveRaw: Boolean,
        onSuccess: (Uri) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val capture = imageCapture ?: run {
            onError(IllegalStateException("Camera capture not initialized"))
            return
        }

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(imageProxy: ImageProxy) {
                try {
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val buffer = imageProxy.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    imageProxy.close()

                    // Decode raw bitmap
                    val originalBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                    // Fix rotation if needed
                    val matrix = Matrix()
                    if (rotationDegrees != 0) {
                        matrix.postRotate(rotationDegrees.toFloat())
                    }
                    if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                        // Mirror selfie photo
                        matrix.postScale(-1f, 1f)
                    }

                    val rotatedBitmap = Bitmap.createBitmap(
                        originalBitmap,
                        0, 0,
                        originalBitmap.width,
                        originalBitmap.height,
                        matrix,
                        true
                    )

                    // Save unedited original if RAW mode is toggled on
                    if (saveRaw) {
                        saveBitmapToGallery(rotatedBitmap, isRaw = true)
                    }

                    // Apply active preset color grading
                    val gradedBitmap = LutGenerator.applyPresetToBitmap(
                        rotatedBitmap,
                        preset,
                        presetIntensity
                    )

                    // Save final graded photo
                    val savedUri = saveBitmapToGallery(gradedBitmap, isRaw = false)
                    if (savedUri != null) {
                        ContextCompat.getMainExecutor(context).execute {
                            onSuccess(savedUri)
                        }
                    } else {
                        throw IllegalStateException("Failed to write to MediaStore")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Photo processing error: ${e.message}", e)
                    ContextCompat.getMainExecutor(context).execute {
                        onError(e)
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                ContextCompat.getMainExecutor(context).execute {
                    onError(exception)
                }
            }
        })
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, isRaw: Boolean): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val suffix = if (isRaw) "_RAW" else "_LIVE"
        val fileName = "LLCAM_${timeStamp}$suffix.jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LiveLightCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving bitmap to gallery: ${e.message}", e)
            resolver.delete(uri, null, null)
            return null
        }
    }

    fun shutdown() {
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }

    companion object {
        private const val TAG = "CameraEngine"
    }
}
