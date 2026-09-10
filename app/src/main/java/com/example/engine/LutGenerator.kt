package com.example.engine

import android.graphics.Bitmap
import android.opengl.GLES30
import com.example.model.CurvePoint
import com.example.model.HslColorChannel
import com.example.model.PresetData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * High-performance 3D LUT Generator and Color Grading Processor.
 * Converts [PresetData] into a 3D LUT texture in OpenGL ES 3.0 and processes high-res photos.
 */
object LutGenerator {
    const val LUT_SIZE = 32 // 32x32x32 = 32,768 nodes for precision color grading

    /**
     * Builds a direct [ByteBuffer] containing RGBA values for a 32x32x32 3D LUT.
     */
    fun generate3dLutBuffer(preset: PresetData, size: Int = LUT_SIZE): ByteBuffer {
        val totalBytes = size * size * size * 4
        val buffer = ByteBuffer.allocateDirect(totalBytes).order(ByteOrder.nativeOrder())

        // 1. Precompute tone curve tables (0..255)
        val lutR = precomputeSplineCurve(preset.toneCurveRed)
        val lutG = precomputeSplineCurve(preset.toneCurveGreen)
        val lutB = precomputeSplineCurve(preset.toneCurveBlue)
        val lutMaster = precomputeSplineCurve(preset.toneCurveRGB)

        // 2. Prepare basic exposure scale
        val exposureMult = 2.0f.pow(preset.exposure)
        val contrastFactor = (1.0f + (preset.contrast / 100.0f) * 0.5f).coerceAtLeast(0.0f)

        val tempFactor = preset.temperature / 100.0f
        val tintFactor = preset.tint / 100.0f
        val vibranceFactor = preset.vibrance / 100.0f
        val saturationFactor = preset.saturation / 100.0f

        val step = 1.0f / (size - 1).toFloat()

        for (bIdx in 0 until size) {
            val bNorm = (bIdx * step).coerceIn(0f, 1f)
            for (gIdx in 0 until size) {
                val gNorm = (gIdx * step).coerceIn(0f, 1f)
                for (rIdx in 0 until size) {
                    val rNorm = (rIdx * step).coerceIn(0f, 1f)

                    // Step A: Exposure
                    var r = rNorm * exposureMult
                    var g = gNorm * exposureMult
                    var b = bNorm * exposureMult

                    // Step B: White Balance (Temp & Tint)
                    r *= (1.0f + tempFactor * 0.28f + tintFactor * 0.12f)
                    g *= (1.0f - tintFactor * 0.22f)
                    b *= (1.0f - tempFactor * 0.28f + tintFactor * 0.12f)

                    // Step C: Contrast, Highlights, Shadows, Whites, Blacks
                    val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b

                    // Contrast S-curve
                    r = (r - 0.5f) * contrastFactor + 0.5f
                    g = (g - 0.5f) * contrastFactor + 0.5f
                    b = (b - 0.5f) * contrastFactor + 0.5f

                    // Highlights (lum > 0.5) & Shadows (lum < 0.5)
                    if (preset.highlights != 0f) {
                        val hlWeight = ((lum - 0.5f) * 2f).coerceIn(0f, 1f)
                        val hlDelta = (preset.highlights / 100.0f) * 0.35f * hlWeight
                        r += hlDelta; g += hlDelta; b += hlDelta
                    }
                    if (preset.shadows != 0f) {
                        val shWeight = ((0.5f - lum) * 2f).coerceIn(0f, 1f)
                        val shDelta = (preset.shadows / 100.0f) * 0.35f * shWeight
                        r += shDelta; g += shDelta; b += shDelta
                    }
                    if (preset.whites != 0f) {
                        val wWeight = (lum * lum).coerceIn(0f, 1f)
                        val wDelta = (preset.whites / 100.0f) * 0.25f * wWeight
                        r += wDelta; g += wDelta; b += wDelta
                    }
                    if (preset.blacks != 0f) {
                        val blWeight = ((1f - lum) * (1f - lum)).coerceIn(0f, 1f)
                        val blDelta = (preset.blacks / 100.0f) * 0.25f * blWeight
                        r += blDelta; g += blDelta; b += blDelta
                    }

                    // Step D: Apply Tone Curves
                    var rByte = (r.coerceIn(0f, 1f) * 255f).toInt()
                    var gByte = (g.coerceIn(0f, 1f) * 255f).toInt()
                    var bByte = (b.coerceIn(0f, 1f) * 255f).toInt()

                    rByte = lutMaster[lutR[rByte]]
                    gByte = lutMaster[lutG[gByte]]
                    bByte = lutMaster[lutB[bByte]]

                    r = rByte / 255.0f
                    g = gByte / 255.0f
                    b = bByte / 255.0f

                    // Step E: HSL Adjustments & Vibrance/Saturation
                    val hsl = rgbToHsl(r, g, b)
                    var h = hsl[0] // 0..360
                    var s = hsl[1] // 0..1
                    var l = hsl[2] // 0..1

                    // Evaluate 8 HSL channels
                    var deltaH = 0f
                    var deltaS = 0f
                    var deltaL = 0f
                    var totalWeight = 0f

                    for (ch in HslColorChannel.entries) {
                        val weight = calculateHueWeight(h, ch.centerHue)
                        if (weight > 0.001f) {
                            deltaH += preset.hueAdjustments[ch.index] * weight
                            deltaS += preset.saturationAdjustments[ch.index] * weight
                            deltaL += preset.luminanceAdjustments[ch.index] * weight
                            totalWeight += weight
                        }
                    }

                    if (totalWeight > 0.001f) {
                        h = (h + (deltaH / totalWeight) * 0.35f + 360f) % 360f
                        s = (s * (1.0f + (deltaS / totalWeight) / 100.0f)).coerceIn(0f, 1f)
                        l = (l * (1.0f + (deltaL / totalWeight) / 100.0f)).coerceIn(0f, 1f)
                    }

                    // Vibrance & Global Saturation
                    val vibBoost = vibranceFactor * (1.0f - s)
                    val totalSatScale = 1.0f + saturationFactor + vibBoost
                    s = (s * totalSatScale).coerceIn(0f, 1f)

                    // Split Toning
                    var gradedRgb = hslToRgb(h, s, l)
                    if (preset.splitShadowSat > 0f || preset.splitHighlightSat > 0f) {
                        gradedRgb = applySplitToning(gradedRgb, preset)
                    }

                    // Write to buffer
                    val finalR = (gradedRgb[0].coerceIn(0f, 1f) * 255f).toInt().toByte()
                    val finalG = (gradedRgb[1].coerceIn(0f, 1f) * 255f).toInt().toByte()
                    val finalB = (gradedRgb[2].coerceIn(0f, 1f) * 255f).toInt().toByte()
                    val finalA = 255.toByte()

                    buffer.put(finalR)
                    buffer.put(finalG)
                    buffer.put(finalB)
                    buffer.put(finalA)
                }
            }
        }
        buffer.position(0)
        return buffer
    }

    /**
     * Uploads the generated 3D LUT buffer to a standard OpenGL ES 3.0 3D texture.
     */
    fun upload3dLutTexture(buffer: ByteBuffer, existingTexId: Int = 0, size: Int = LUT_SIZE): Int {
        val texId = if (existingTexId > 0) {
            existingTexId
        } else {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            ids[0]
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, texId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D,
            0,
            GLES30.GL_RGBA8,
            size,
            size,
            size,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            buffer
        )

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
        return texId
    }

    /**
     * Applies the preset with specified intensity to a Bitmap (e.g. for high-res photo capture).
     */
    fun applyPresetToBitmap(source: Bitmap, preset: PresetData, intensity: Float): Bitmap {
        if (preset == PresetData.ORIGINAL || intensity <= 0.001f) {
            return source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
        }

        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        // Precompute tables
        val lutR = precomputeSplineCurve(preset.toneCurveRed)
        val lutG = precomputeSplineCurve(preset.toneCurveGreen)
        val lutB = precomputeSplineCurve(preset.toneCurveBlue)
        val lutMaster = precomputeSplineCurve(preset.toneCurveRGB)

        val exposureMult = 2.0f.pow(preset.exposure)
        val contrastFactor = (1.0f + (preset.contrast / 100.0f) * 0.5f).coerceAtLeast(0.0f)
        val tempFactor = preset.temperature / 100.0f
        val tintFactor = preset.tint / 100.0f
        val vibranceFactor = preset.vibrance / 100.0f
        val saturationFactor = preset.saturation / 100.0f

        val inv255 = 1.0f / 255.0f

        for (i in pixels.indices) {
            val color = pixels[i]
            val a = (color ushr 24) and 0xFF
            val origR = (color ushr 16) and 0xFF
            val origG = (color ushr 8) and 0xFF
            val origB = color and 0xFF

            var r = origR * inv255 * exposureMult
            var g = origG * inv255 * exposureMult
            var b = origB * inv255 * exposureMult

            // Temp & Tint
            r *= (1.0f + tempFactor * 0.28f + tintFactor * 0.12f)
            g *= (1.0f - tintFactor * 0.22f)
            b *= (1.0f - tempFactor * 0.28f + tintFactor * 0.12f)

            // Contrast & Tone
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            r = (r - 0.5f) * contrastFactor + 0.5f
            g = (g - 0.5f) * contrastFactor + 0.5f
            b = (b - 0.5f) * contrastFactor + 0.5f

            if (preset.highlights != 0f) {
                val hlWeight = ((lum - 0.5f) * 2f).coerceIn(0f, 1f)
                val hlDelta = (preset.highlights / 100.0f) * 0.35f * hlWeight
                r += hlDelta; g += hlDelta; b += hlDelta
            }
            if (preset.shadows != 0f) {
                val shWeight = ((0.5f - lum) * 2f).coerceIn(0f, 1f)
                val shDelta = (preset.shadows / 100.0f) * 0.35f * shWeight
                r += shDelta; g += shDelta; b += shDelta
            }

            var rByte = (r.coerceIn(0f, 1f) * 255f).toInt()
            var gByte = (g.coerceIn(0f, 1f) * 255f).toInt()
            var bByte = (b.coerceIn(0f, 1f) * 255f).toInt()

            rByte = lutMaster[lutR[rByte]]
            gByte = lutMaster[lutG[gByte]]
            bByte = lutMaster[lutB[bByte]]

            r = rByte * inv255
            g = gByte * inv255
            b = bByte * inv255

            // HSL adjustments
            val hsl = rgbToHsl(r, g, b)
            var h = hsl[0]
            var s = hsl[1]
            var l = hsl[2]

            var deltaH = 0f; var deltaS = 0f; var deltaL = 0f; var totalWeight = 0f
            for (ch in HslColorChannel.entries) {
                val weight = calculateHueWeight(h, ch.centerHue)
                if (weight > 0.001f) {
                    deltaH += preset.hueAdjustments[ch.index] * weight
                    deltaS += preset.saturationAdjustments[ch.index] * weight
                    deltaL += preset.luminanceAdjustments[ch.index] * weight
                    totalWeight += weight
                }
            }

            if (totalWeight > 0.001f) {
                h = (h + (deltaH / totalWeight) * 0.35f + 360f) % 360f
                s = (s * (1.0f + (deltaS / totalWeight) / 100.0f)).coerceIn(0f, 1f)
                l = (l * (1.0f + (deltaL / totalWeight) / 100.0f)).coerceIn(0f, 1f)
            }

            val vibBoost = vibranceFactor * (1.0f - s)
            s = (s * (1.0f + saturationFactor + vibBoost)).coerceIn(0f, 1f)

            var gradedRgb = hslToRgb(h, s, l)
            if (preset.splitShadowSat > 0f || preset.splitHighlightSat > 0f) {
                gradedRgb = applySplitToning(gradedRgb, preset)
            }

            // Blend with intensity
            val finalR = (origR * (1f - intensity) + gradedRgb[0] * 255f * intensity).toInt().coerceIn(0, 255)
            val finalG = (origG * (1f - intensity) + gradedRgb[1] * 255f * intensity).toInt().coerceIn(0, 255)
            val finalB = (origB * (1f - intensity) + gradedRgb[2] * 255f * intensity).toInt().coerceIn(0, 255)

            pixels[i] = (a shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
        }

        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Precomputes a 256-integer lookup table from Tone Curve points using Natural Cubic Spline.
     */
    private fun precomputeSplineCurve(points: List<CurvePoint>): IntArray {
        val lut = IntArray(256)
        if (points.size < 2) {
            for (i in 0..255) lut[i] = i
            return lut
        }

        val sorted = points.sortedBy { it.x }
        val n = sorted.size
        val x = FloatArray(n) { sorted[it].x }
        val y = FloatArray(n) { sorted[it].y }

        // Natural cubic spline second derivatives
        val h = FloatArray(n - 1) { x[it + 1] - x[it] }
        val alpha = FloatArray(n)
        for (i in 1 until n - 1) {
            if (h[i] > 0f && h[i - 1] > 0f) {
                alpha[i] = (3f / h[i]) * (y[i + 1] - y[i]) - (3f / h[i - 1]) * (y[i] - y[i - 1])
            }
        }

        val l = FloatArray(n) { 1f }
        val mu = FloatArray(n)
        val z = FloatArray(n)

        for (i in 1 until n - 1) {
            l[i] = 2f * (x[i + 1] - x[i - 1]) - h[i - 1] * mu[i - 1]
            if (l[i] != 0f) {
                mu[i] = h[i] / l[i]
                z[i] = (alpha[i] - h[i - 1] * z[i - 1]) / l[i]
            }
        }

        val c = FloatArray(n)
        val b = FloatArray(n)
        val d = FloatArray(n)

        for (j in n - 2 downTo 0) {
            c[j] = z[j] - mu[j] * c[j + 1]
            if (h[j] > 0f) {
                b[j] = (y[j + 1] - y[j]) / h[j] - h[j] * (c[j + 1] + 2f * c[j]) / 3f
                d[j] = (c[j + 1] - c[j]) / (3f * h[j])
            }
        }

        for (i in 0..255) {
            val xi = i.toFloat()
            // Find interval
            var k = 0
            while (k < n - 2 && xi > x[k + 1]) {
                k++
            }
            val dx = xi - x[k]
            val interpolated = y[k] + b[k] * dx + c[k] * dx * dx + d[k] * dx * dx * dx
            lut[i] = interpolated.toInt().coerceIn(0, 255)
        }

        return lut
    }

    private fun calculateHueWeight(hue: Float, center: Float): Float {
        var diff = abs(hue - center)
        if (diff > 180f) diff = 360f - diff
        // Smooth bell curve with bandwidth ~35 degrees
        return if (diff < 40f) {
            cos(diff * Math.PI.toFloat() / 80f).coerceAtLeast(0f)
        } else 0f
    }

    private fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        var h = 0f
        var s = 0f
        val l = (maxC + minC) * 0.5f

        if (delta > 0.0001f) {
            s = if (l < 0.5f) delta / (maxC + minC) else delta / (2.0f - maxC - minC)
            when (maxC) {
                r -> h = ((g - b) / delta + (if (g < b) 6f else 0f)) * 60f
                g -> h = ((b - r) / delta + 2f) * 60f
                b -> h = ((r - g) / delta + 4f) * 60f
            }
        }
        return floatArrayOf(h % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
        if (s <= 0.0001f) return floatArrayOf(l, l, l)

        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        val hNorm = h / 360f

        val r = hueToRgb(p, q, hNorm + 1f / 3f)
        val g = hueToRgb(p, q, hNorm)
        val b = hueToRgb(p, q, hNorm - 1f / 3f)
        return floatArrayOf(r, g, b)
    }

    private fun hueToRgb(p: Float, q: Float, tInput: Float): Float {
        var t = tInput
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    private fun applySplitToning(rgb: FloatArray, preset: PresetData): FloatArray {
        val lum = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]
        val balanceFactor = preset.splitBalance / 100.0f
        val splitThreshold = (0.5f + balanceFactor * 0.25f).coerceIn(0.1f, 0.9f)

        var r = rgb[0]
        var g = rgb[1]
        var b = rgb[2]

        // Shadows
        if (preset.splitShadowSat > 0f && lum < splitThreshold) {
            val weight = ((splitThreshold - lum) / splitThreshold).coerceIn(0f, 1f) * (preset.splitShadowSat / 100f) * 0.4f
            val shadowRgb = hslToRgb(preset.splitShadowHue, 1f, lum)
            r = r * (1f - weight) + shadowRgb[0] * weight
            g = g * (1f - weight) + shadowRgb[1] * weight
            b = b * (1f - weight) + shadowRgb[2] * weight
        }

        // Highlights
        if (preset.splitHighlightSat > 0f && lum > splitThreshold) {
            val weight = ((lum - splitThreshold) / (1f - splitThreshold)).coerceIn(0f, 1f) * (preset.splitHighlightSat / 100f) * 0.4f
            val highlightRgb = hslToRgb(preset.splitHighlightHue, 1f, lum)
            r = r * (1f - weight) + highlightRgb[0] * weight
            g = g * (1f - weight) + highlightRgb[1] * weight
            b = b * (1f - weight) + highlightRgb[2] * weight
        }

        return floatArrayOf(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }
}
