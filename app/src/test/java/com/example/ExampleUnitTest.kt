package com.example

import com.example.engine.LutGenerator
import com.example.parser.XmlPresetParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testXmlPresetParser_parseXmpString() {
    val sampleXmp = """
      <x:xmpmeta xmlns:x="adobe:ns:meta/">
        <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <rdf:Description
              crs:Name="Golden Sunset"
              crs:Exposure2012="+0.45"
              crs:Contrast2012="+20"
              crs:Highlights2012="-35"
              crs:Shadows2012="+25"
              crs:Temperature="6200"
              crs:Tint="+8"
              crs:Vibrance="+18"
              crs:Saturation="-5"
              crs:HueAdjustmentOrange="-10"
              crs:SaturationAdjustmentOrange="+25"
              crs:SplitToningShadowHue="210"
              crs:SplitToningShadowSaturation="15">
            <crs:ToneCurvePV2012>
              <rdf:Seq>
                <rdf:li>0, 10</rdf:li>
                <rdf:li>64, 55</rdf:li>
                <rdf:li>192, 205</rdf:li>
                <rdf:li>255, 245</rdf:li>
              </rdf:Seq>
            </crs:ToneCurvePV2012>
          </rdf:Description>
        </rdf:RDF>
      </x:xmpmeta>
    """.trimIndent()

    val preset = XmlPresetParser.parseFromString(sampleXmp, "GoldenSunset.xmp")

    assertEquals("Golden Sunset", preset.name)
    assertEquals(0.45f, preset.exposure, 0.01f)
    assertEquals(20f, preset.contrast, 0.01f)
    assertEquals(-35f, preset.highlights, 0.01f)
    assertEquals(25f, preset.shadows, 0.01f)
    assertEquals(8f, preset.tint, 0.01f)
    assertEquals(18f, preset.vibrance, 0.01f)
    assertEquals(-5f, preset.saturation, 0.01f)
    assertTrue(preset.toneCurveRGB.size >= 4)
  }

  @Test
  fun testLutGenerator_generate3dLutBuffer() {
    val presets = XmlPresetParser.getBuiltInPresets()
    assertTrue(presets.isNotEmpty())

    val warmPreset = presets.first { it.name.contains("Warm") }
    val buffer = LutGenerator.generate3dLutBuffer(warmPreset, size = 16)

    assertNotNull(buffer)
    assertEquals(16 * 16 * 16 * 4, buffer.capacity())
  }
}

