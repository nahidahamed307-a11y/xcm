package com.example.model

/**
 * Represents a 2D control point for tone curves.
 * Normalized or raw 0..255 coordinates.
 */
data class CurvePoint(
    val x: Float,
    val y: Float
)

/**
 * Lightroom Color Channels for HSL (8 channels)
 */
enum class HslColorChannel(val index: Int, val centerHue: Float, val channelName: String) {
    RED(0, 0f, "Red"),
    ORANGE(1, 30f, "Orange"),
    YELLOW(2, 60f, "Yellow"),
    GREEN(3, 120f, "Green"),
    AQUA(4, 180f, "Aqua"),
    BLUE(5, 240f, "Blue"),
    PURPLE(6, 280f, "Purple"),
    MAGENTA(7, 320f, "Magenta")
}

/**
 * Complete parsed Adobe Lightroom (.xmp / .xml) preset model.
 */
data class PresetData(
    val id: String,
    val name: String,
    val fileName: String,
    val isBuiltIn: Boolean = false,

    // Basic exposure & tone
    val exposure: Float = 0.0f,     // -5.0 to +5.0 EV
    val contrast: Float = 0.0f,     // -100 to +100
    val highlights: Float = 0.0f,   // -100 to +100
    val shadows: Float = 0.0f,      // -100 to +100
    val whites: Float = 0.0f,       // -100 to +100
    val blacks: Float = 0.0f,       // -100 to +100

    // White balance & color presence
    val temperature: Float = 0.0f,  // -100 to +100 offset or Kelvin relative
    val tint: Float = 0.0f,         // -100 to +100
    val vibrance: Float = 0.0f,     // -100 to +100
    val saturation: Float = 0.0f,   // -100 to +100
    val clarity: Float = 0.0f,      // -100 to +100
    val dehaze: Float = 0.0f,       // -100 to +100

    // Tone Curve Points (Master RGB, and separate R, G, B channels)
    val toneCurveRGB: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(255f, 255f)),
    val toneCurveRed: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(255f, 255f)),
    val toneCurveGreen: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(255f, 255f)),
    val toneCurveBlue: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(255f, 255f)),

    // HSL 8 Channels (-100 to +100 each)
    val hueAdjustments: FloatArray = FloatArray(8),
    val saturationAdjustments: FloatArray = FloatArray(8),
    val luminanceAdjustments: FloatArray = FloatArray(8),

    // Split Toning / Color Grading
    val splitShadowHue: Float = 0f,       // 0 to 360
    val splitShadowSat: Float = 0f,       // 0 to 100
    val splitHighlightHue: Float = 0f,    // 0 to 360
    val splitHighlightSat: Float = 0f,    // 0 to 100
    val splitBalance: Float = 0f,         // -100 to 100

    // UI presentation color
    val accentColor: Long = 0xFFFFB74D
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PresetData) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    companion object {
        val ORIGINAL = PresetData(
            id = "preset_original",
            name = "Original",
            fileName = "None",
            isBuiltIn = true,
            accentColor = 0xFF9E9E9E
        )
    }
}
