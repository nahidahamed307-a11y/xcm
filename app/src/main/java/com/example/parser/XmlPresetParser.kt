package com.example.parser

import android.util.Log
import com.example.model.CurvePoint
import com.example.model.HslColorChannel
import com.example.model.PresetData
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory

/**
 * High-performance offline parser for Adobe Lightroom .xml and .xmp preset files.
 */
object XmlPresetParser {
    private const val TAG = "XmlPresetParser"

    /**
     * Parses an input stream containing .xml or .xmp Lightroom preset data.
     */
    fun parseFromStream(inputStream: InputStream, fallbackName: String): PresetData {
        val content = inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return parseFromString(content, fallbackName)
    }

    /**
     * Parses raw string XML / XMP data into a structured [PresetData] model.
     */
    fun parseFromString(xmlString: String, fallbackName: String): PresetData {
        val attributesMap = mutableMapOf<String, String>()
        val curveMap = mutableMapOf<String, MutableList<CurvePoint>>()

        var presetName = fallbackName
            .removeSuffix(".xmp")
            .removeSuffix(".xml")
            .replace("_", " ")

        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val input = ByteArrayInputStream(xmlString.toByteArray(StandardCharsets.UTF_8))
            val doc: Document = builder.parse(input)
            doc.documentElement.normalize()

            // 1. Traverse all nodes and collect attributes and text elements
            traverseDom(doc.documentElement, attributesMap, curveMap)
        } catch (t: Throwable) {
            safeLogW(TAG, "DOM parsing error, falling back to regex extraction: ${t.message}")
            parseWithRegexFallback(xmlString, attributesMap, curveMap)
        }

        if (attributesMap.isEmpty()) {
            parseWithRegexFallback(xmlString, attributesMap, curveMap)
        }

        // Extract preset name if present
        attributesMap["crs:Name"]?.let { presetName = it }
        attributesMap["crs:PresetName"]?.let { presetName = it }
        attributesMap["dc:title"]?.let { presetName = it }

        // 2. Extract adjustments from gathered dictionary
        val exposure = extractFloat(attributesMap, "crs:Exposure2012", "Exposure2012", "Exposure") ?: 0.0f
        val contrast = extractFloat(attributesMap, "crs:Contrast2012", "Contrast2012", "Contrast") ?: 0.0f
        val highlights = extractFloat(attributesMap, "crs:Highlights2012", "Highlights2012", "Highlights") ?: 0.0f
        val shadows = extractFloat(attributesMap, "crs:Shadows2012", "Shadows2012", "Shadows") ?: 0.0f
        val whites = extractFloat(attributesMap, "crs:Whites2012", "Whites2012", "Whites") ?: 0.0f
        val blacks = extractFloat(attributesMap, "crs:Blacks2012", "Blacks2012", "Blacks") ?: 0.0f

        // White balance
        val tempRaw = extractFloat(attributesMap, "crs:Temperature", "Temperature", "IncrementalTemperature") ?: 0.0f
        // Normalize temperature if given in Kelvin (e.g. 5500K -> offset from 5000K)
        val temp = if (tempRaw > 1000f) {
            ((tempRaw - 5500f) / 45f).coerceIn(-100f, 100f)
        } else {
            tempRaw.coerceIn(-100f, 100f)
        }
        val tint = extractFloat(attributesMap, "crs:Tint", "Tint", "IncrementalTint") ?: 0.0f
        val vibrance = extractFloat(attributesMap, "crs:Vibrance", "Vibrance") ?: 0.0f
        val saturation = extractFloat(attributesMap, "crs:Saturation", "Saturation") ?: 0.0f
        val clarity = extractFloat(attributesMap, "crs:Clarity2012", "Clarity2012", "Clarity") ?: 0.0f
        val dehaze = extractFloat(attributesMap, "crs:Dehaze", "Dehaze") ?: 0.0f

        // Tone curves
        val curveRGB = curveMap["ToneCurvePV2012"]?.takeIf { it.isNotEmpty() }
            ?: parseCurveString(attributesMap["crs:ToneCurvePV2012"] ?: attributesMap["ToneCurvePV2012"])
            ?: defaultLinearCurve()

        val curveRed = curveMap["ToneCurvePV2012Red"]?.takeIf { it.isNotEmpty() }
            ?: parseCurveString(attributesMap["crs:ToneCurvePV2012Red"] ?: attributesMap["ToneCurvePV2012Red"])
            ?: defaultLinearCurve()

        val curveGreen = curveMap["ToneCurvePV2012Green"]?.takeIf { it.isNotEmpty() }
            ?: parseCurveString(attributesMap["crs:ToneCurvePV2012Green"] ?: attributesMap["ToneCurvePV2012Green"])
            ?: defaultLinearCurve()

        val curveBlue = curveMap["ToneCurvePV2012Blue"]?.takeIf { it.isNotEmpty() }
            ?: parseCurveString(attributesMap["crs:ToneCurvePV2012Blue"] ?: attributesMap["ToneCurvePV2012Blue"])
            ?: defaultLinearCurve()

        // HSL Adjustments for 8 Channels
        val hueAdj = FloatArray(8)
        val satAdj = FloatArray(8)
        val lumAdj = FloatArray(8)

        for (channel in HslColorChannel.entries) {
            val idx = channel.index
            val name = channel.channelName
            hueAdj[idx] = extractFloat(attributesMap, "crs:HueAdjustment$name", "HueAdjustment$name") ?: 0f
            satAdj[idx] = extractFloat(attributesMap, "crs:SaturationAdjustment$name", "SaturationAdjustment$name") ?: 0f
            lumAdj[idx] = extractFloat(attributesMap, "crs:LuminanceAdjustment$name", "LuminanceAdjustment$name") ?: 0f
        }

        // Split Toning
        val splitShadowHue = extractFloat(attributesMap, "crs:SplitToningShadowHue", "SplitToningShadowHue") ?: 0f
        val splitShadowSat = extractFloat(attributesMap, "crs:SplitToningShadowSaturation", "SplitToningShadowSaturation") ?: 0f
        val splitHighlightHue = extractFloat(attributesMap, "crs:SplitToningHighlightHue", "SplitToningHighlightHue") ?: 0f
        val splitHighlightSat = extractFloat(attributesMap, "crs:SplitToningHighlightSaturation", "SplitToningHighlightSaturation") ?: 0f
        val splitBalance = extractFloat(attributesMap, "crs:SplitToningBalance", "SplitToningBalance") ?: 0f

        return PresetData(
            id = UUID.randomUUID().toString(),
            name = presetName,
            fileName = fallbackName,
            isBuiltIn = false,
            exposure = exposure,
            contrast = contrast,
            highlights = highlights,
            shadows = shadows,
            whites = whites,
            blacks = blacks,
            temperature = temp,
            tint = tint,
            vibrance = vibrance,
            saturation = saturation,
            clarity = clarity,
            dehaze = dehaze,
            toneCurveRGB = curveRGB,
            toneCurveRed = curveRed,
            toneCurveGreen = curveGreen,
            toneCurveBlue = curveBlue,
            hueAdjustments = hueAdj,
            saturationAdjustments = satAdj,
            luminanceAdjustments = lumAdj,
            splitShadowHue = splitShadowHue,
            splitShadowSat = splitShadowSat,
            splitHighlightHue = splitHighlightHue,
            splitHighlightSat = splitHighlightSat,
            splitBalance = splitBalance,
            accentColor = pickAccentColor(presetName)
        )
    }

    private fun traverseDom(
        element: Element,
        attributes: MutableMap<String, String>,
        curves: MutableMap<String, MutableList<CurvePoint>>
    ) {
        // Collect attributes
        val attrMap = element.attributes
        for (i in 0 until attrMap.length) {
            val item = attrMap.item(i)
            attributes[item.nodeName] = item.nodeValue
            // Also store stripped key (without crs: prefix)
            val localName = item.localName ?: item.nodeName.substringAfter(":")
            attributes[localName] = item.nodeValue
        }

        // Check if current element is a ToneCurve sequence
        val nodeName = element.localName ?: element.nodeName.substringAfter(":")
        if (nodeName.startsWith("ToneCurvePV2012")) {
            val list = mutableListOf<CurvePoint>()
            val liNodes = element.getElementsByTagNameNS("*", "li")
            if (liNodes.length > 0) {
                for (j in 0 until liNodes.length) {
                    val text = liNodes.item(j).textContent.trim()
                    parsePoint(text)?.let { list.add(it) }
                }
            } else {
                val nonNsLi = element.getElementsByTagName("rdf:li")
                for (j in 0 until nonNsLi.length) {
                    val text = nonNsLi.item(j).textContent.trim()
                    parsePoint(text)?.let { list.add(it) }
                }
            }
            if (list.isNotEmpty()) {
                curves[nodeName] = list
            }
        }

        // Recurse children
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) {
                val childEl = child as Element
                val tag = childEl.localName ?: childEl.nodeName.substringAfter(":")
                // If it's a leaf node with text content, record it
                if (childEl.childNodes.length == 1 && childEl.firstChild.nodeType == Node.TEXT_NODE) {
                    val text = childEl.textContent.trim()
                    attributes[childEl.nodeName] = text
                    attributes[tag] = text
                }
                traverseDom(childEl, attributes, curves)
            }
        }
    }

    private fun parseWithRegexFallback(
        content: String,
        attributes: MutableMap<String, String>,
        curves: MutableMap<String, MutableList<CurvePoint>>
    ) {
        // Match attribute key="value" or <tag>value</tag>
        val attrPattern = Pattern.compile("(?:crs:)?([a-zA-Z0-9_]+)\\s*=\\s*[\"']([^\"']*)[\"']")
        val matcher = attrPattern.matcher(content)
        while (matcher.find()) {
            val key = matcher.group(1) ?: continue
            val value = matcher.group(2) ?: continue
            attributes[key] = value
            attributes["crs:$key"] = value
        }

        // Regex for tone curve sequence points: <rdf:li>0, 0</rdf:li>
        val curveRegex = Pattern.compile("<(?:crs:)?(ToneCurvePV2012[a-zA-Z0-9]*)>(.*?)</(?:crs:)?\\1>", Pattern.DOTALL)
        val curveMatcher = curveRegex.matcher(content)
        while (curveMatcher.find()) {
            val curveName = curveMatcher.group(1) ?: continue
            val body = curveMatcher.group(2) ?: continue
            val ptMatcher = Pattern.compile("<rdf:li>\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*</rdf:li>").matcher(body)
            val pts = mutableListOf<CurvePoint>()
            while (ptMatcher.find()) {
                val x = ptMatcher.group(1)?.toFloatOrNull() ?: 0f
                val y = ptMatcher.group(2)?.toFloatOrNull() ?: 0f
                pts.add(CurvePoint(x, y))
            }
            if (pts.isNotEmpty()) {
                curves[curveName] = pts
            }
        }
    }

    private fun parsePoint(text: String): CurvePoint? {
        val parts = text.split(",")
        if (parts.size == 2) {
            val x = parts[0].trim().toFloatOrNull() ?: return null
            val y = parts[1].trim().toFloatOrNull() ?: return null
            return CurvePoint(x, y)
        }
        return null
    }

    private fun parseCurveString(raw: String?): List<CurvePoint>? {
        if (raw.isNullOrBlank()) return null
        val pts = mutableListOf<CurvePoint>()
        val pairs = raw.split(";")
        for (pair in pairs) {
            parsePoint(pair)?.let { pts.add(it) }
        }
        return if (pts.size >= 2) pts else null
    }

    private fun extractFloat(map: Map<String, String>, vararg keys: String): Float? {
        for (k in keys) {
            map[k]?.let { v ->
                v.replace("+", "").trim().toFloatOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun defaultLinearCurve(): List<CurvePoint> =
        listOf(CurvePoint(0f, 0f), CurvePoint(255f, 255f))

    private fun pickAccentColor(name: String): Long {
        val lower = name.lowercase()
        return when {
            lower.contains("warm") || lower.contains("golden") -> 0xFFFFA726
            lower.contains("teal") || lower.contains("aqua") || lower.contains("cyan") -> 0xFF26C6DA
            lower.contains("vintage") || lower.contains("film") -> 0xFFFFB74D
            lower.contains("b&w") || lower.contains("mono") || lower.contains("black") -> 0xFFE0E0E0
            lower.contains("neon") || lower.contains("cyber") -> 0xFFE040FB
            lower.contains("pastel") || lower.contains("rose") -> 0xFFF06292
            else -> 0xFF81D4FA
        }
    }

    /**
     * Built-in authentic Lightroom presets ready for instant zero-lag preview.
     */
    fun getBuiltInPresets(): List<PresetData> {
        return listOf(
            PresetData.ORIGINAL,

            // 1. Warm Cinematic
            PresetData(
                id = "preset_warm_cinematic",
                name = "Warm Cinematic",
                fileName = "WarmCinematic.xmp",
                isBuiltIn = true,
                exposure = 0.20f,
                contrast = 18f,
                highlights = -25f,
                shadows = 20f,
                whites = 10f,
                blacks = -12f,
                temperature = 18f,
                tint = 4f,
                vibrance = 16f,
                saturation = -4f,
                clarity = 8f,
                toneCurveRGB = listOf(
                    CurvePoint(0f, 15f), // Lifted blacks (matte look)
                    CurvePoint(64f, 54f),
                    CurvePoint(192f, 204f),
                    CurvePoint(255f, 245f) // Softened highlights
                ),
                hueAdjustments = floatArrayOf(5f, -8f, -12f, 10f, 15f, -5f, 0f, 0f),
                saturationAdjustments = floatArrayOf(8f, 12f, 5f, -15f, -10f, -20f, -5f, 0f),
                luminanceAdjustments = floatArrayOf(5f, 8f, 10f, 0f, 0f, -5f, 0f, 0f),
                splitShadowHue = 210f,
                splitShadowSat = 18f,
                splitHighlightHue = 42f,
                splitHighlightSat = 24f,
                splitBalance = 15f,
                accentColor = 0xFFFFA726
            ),

            // 2. Moody Teal & Orange
            PresetData(
                id = "preset_teal_orange",
                name = "Teal & Orange",
                fileName = "TealAndOrange.xmp",
                isBuiltIn = true,
                exposure = 0.10f,
                contrast = 25f,
                highlights = -30f,
                shadows = 15f,
                whites = 15f,
                blacks = -20f,
                temperature = 10f,
                tint = -5f,
                vibrance = 25f,
                saturation = 5f,
                toneCurveRGB = listOf(
                    CurvePoint(0f, 5f),
                    CurvePoint(64f, 48f),
                    CurvePoint(180f, 195f),
                    CurvePoint(255f, 252f)
                ),
                hueAdjustments = floatArrayOf(15f, -10f, -25f, -30f, -10f, 15f, 0f, 0f),
                saturationAdjustments = floatArrayOf(15f, 25f, 10f, -20f, 25f, 20f, -10f, 0f),
                luminanceAdjustments = floatArrayOf(5f, 10f, 5f, -5f, 0f, -8f, 0f, 0f),
                splitShadowHue = 195f,
                splitShadowSat = 35f,
                splitHighlightHue = 36f,
                splitHighlightSat = 40f,
                splitBalance = 5f,
                accentColor = 0xFF26C6DA
            ),

            // 3. Vintage Film 1970
            PresetData(
                id = "preset_vintage_film",
                name = "Vintage 1970",
                fileName = "Vintage1970.xmp",
                isBuiltIn = true,
                exposure = 0.05f,
                contrast = -10f,
                highlights = -20f,
                shadows = 30f,
                whites = -15f,
                blacks = 25f,
                temperature = 22f,
                tint = 12f,
                vibrance = -10f,
                saturation = -12f,
                toneCurveRGB = listOf(
                    CurvePoint(0f, 28f), // Faded vintage blacks
                    CurvePoint(70f, 75f),
                    CurvePoint(190f, 185f),
                    CurvePoint(255f, 230f) // Warm vintage roll-off
                ),
                toneCurveRed = listOf(
                    CurvePoint(0f, 10f),
                    CurvePoint(255f, 255f)
                ),
                toneCurveBlue = listOf(
                    CurvePoint(0f, 25f), // Yellowing shadows
                    CurvePoint(255f, 240f)
                ),
                hueAdjustments = floatArrayOf(0f, 5f, 10f, -15f, 0f, 0f, 0f, 0f),
                saturationAdjustments = floatArrayOf(-10f, -5f, -8f, -25f, -15f, -25f, -15f, -10f),
                luminanceAdjustments = floatArrayOf(8f, 12f, 5f, 5f, 0f, 0f, 0f, 0f),
                splitShadowHue = 55f,
                splitShadowSat = 18f,
                splitHighlightHue = 38f,
                splitHighlightSat = 15f,
                splitBalance = 0f,
                accentColor = 0xFFFFCA28
            ),

            // 4. B&W Street High Contrast
            PresetData(
                id = "preset_bw_high_contrast",
                name = "B&W High Contrast",
                fileName = "BWStreet.xmp",
                isBuiltIn = true,
                exposure = 0.15f,
                contrast = 45f,
                highlights = 20f,
                shadows = -30f,
                whites = 30f,
                blacks = -35f,
                saturation = -100f, // Complete monochrome
                vibrance = -100f,
                clarity = 30f,
                toneCurveRGB = listOf(
                    CurvePoint(0f, 0f),
                    CurvePoint(50f, 28f),
                    CurvePoint(128f, 128f),
                    CurvePoint(200f, 225f),
                    CurvePoint(255f, 255f)
                ),
                accentColor = 0xFFEEEEEE
            ),

            // 5. Pastel Golden Hour
            PresetData(
                id = "preset_pastel_golden",
                name = "Pastel Golden",
                fileName = "PastelGolden.xmp",
                isBuiltIn = true,
                exposure = 0.35f,
                contrast = -15f,
                highlights = -40f,
                shadows = 35f,
                whites = 20f,
                blacks = 10f,
                temperature = 28f,
                tint = 15f,
                vibrance = 20f,
                saturation = -8f,
                toneCurveRGB = listOf(
                    CurvePoint(0f, 20f),
                    CurvePoint(80f, 92f),
                    CurvePoint(180f, 190f),
                    CurvePoint(255f, 248f)
                ),
                hueAdjustments = floatArrayOf(8f, 12f, 15f, 20f, 0f, 0f, 0f, 0f),
                saturationAdjustments = floatArrayOf(10f, 15f, 10f, -15f, -10f, -10f, 5f, 5f),
                luminanceAdjustments = floatArrayOf(10f, 15f, 12f, 5f, 0f, 0f, 0f, 0f),
                splitShadowHue = 320f, // Subtle rose shadows
                splitShadowSat = 15f,
                splitHighlightHue = 45f, // Golden highlights
                splitHighlightSat = 30f,
                splitBalance = 20f,
                accentColor = 0xFFF48FB1
            ),

            // 6. Cyberpunk Neon
            PresetData(
                id = "preset_cyberpunk",
                name = "Cyberpunk Neon",
                fileName = "Cyberpunk.xmp",
                isBuiltIn = true,
                exposure = 0.0f,
                contrast = 35f,
                highlights = -20f,
                shadows = -10f,
                whites = 25f,
                blacks = -30f,
                temperature = -15f,
                tint = 35f, // Heavy magenta bias
                vibrance = 45f,
                saturation = 20f,
                toneCurveRGB = listOf(
                    CurvePoint(0f, 0f),
                    CurvePoint(60f, 40f),
                    CurvePoint(190f, 215f),
                    CurvePoint(255f, 255f)
                ),
                hueAdjustments = floatArrayOf(25f, 0f, 0f, 0f, -20f, 10f, 30f, 25f),
                saturationAdjustments = floatArrayOf(40f, 10f, 0f, -20f, 45f, 35f, 50f, 50f),
                luminanceAdjustments = floatArrayOf(10f, 0f, 0f, -10f, 15f, 10f, 20f, 20f),
                splitShadowHue = 190f, // Electric Cyan shadows
                splitShadowSat = 45f,
                splitHighlightHue = 315f, // Hot Magenta highlights
                splitHighlightSat = 45f,
                splitBalance = -10f,
                accentColor = 0xFF00E5FF
            )
        )
    }

    private fun safeLogW(tag: String, msg: String) {
        try {
            Log.w(tag, msg)
        } catch (_: Throwable) {
            System.err.println("[$tag] $msg")
        }
    }
}
