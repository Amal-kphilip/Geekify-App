package com.geekify.android.ui.account

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.geekify.android.data.auth.AccountUser
import com.geekify.android.ui.glass.GlassRole
import com.geekify.android.ui.glass.GlassStyle
import com.geekify.android.ui.theme.InkPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountSheetVisualTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testUser = AccountUser(
        uid = "test_user_1",
        email = "listener@geekify.audio",
        name = "Amal Philip",
        photoUrl = null
    )

    private fun relativeLuminance(colorInt: Int): Double {
        val r = ((colorInt ushr 16) and 0xFF) / 255.0
        val g = ((colorInt ushr 8) and 0xFF) / 255.0
        val b = (colorInt and 0xFF) / 255.0
        val rLin = if (r <= 0.04045) r / 12.92 else ((r + 0.055) / 1.055).pow(2.4)
        val gLin = if (g <= 0.04045) g / 12.92 else ((g + 0.055) / 1.055).pow(2.4)
        val bLin = if (b <= 0.04045) b / 12.92 else ((b + 0.055) / 1.055).pow(2.4)
        return 0.2126 * rLin + 0.7152 * gLin + 0.0722 * bLin
    }

    private fun contrastRatio(fg: Int, bg: Int): Double {
        val l1 = relativeLuminance(fg)
        val l2 = relativeLuminance(bg)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** Old glow with opaque InkPanel bottom fade */
    private fun DrawScope.drawProfileGlowOld() {
        val w = size.width
        val h = size.height
        drawRect(
            Brush.radialGradient(
                listOf(Color(0xFF8A4DFF).copy(alpha = 0.50f), Color.Transparent),
                center = Offset(w * 0.15f, h * 0.05f),
                radius = w * 1.0f
            )
        )
        drawRect(
            Brush.radialGradient(
                listOf(Color(0xFFFF8FC4).copy(alpha = 0.55f), Color.Transparent),
                center = Offset(w * 0.02f, 0f),
                radius = w * 0.55f
            )
        )
        drawRect(Brush.verticalGradient(0.5f to Color.Transparent, 1f to InkPanel))
    }

    /** New glow masked with smooth alpha fade and no opaque InkPanel */
    private val layerPaint = Paint()
    private fun DrawScope.drawProfileGlowNew() {
        val w = size.width
        val h = size.height
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(0f, 0f, w, h), layerPaint)
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0xFF8A4DFF).copy(alpha = 0.50f), Color.Transparent),
                    center = Offset(w * 0.15f, h * 0.05f),
                    radius = w * 1.0f
                )
            )
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0xFFFF8FC4).copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(w * 0.02f, 0f),
                    radius = w * 0.55f
                )
            )
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black,
                    0.45f to Color.Black,
                    1f to Color.Transparent
                ),
                blendMode = BlendMode.DstIn
            )
            canvas.restore()
        }
    }

    @Test
    fun testSeamMeasurementBeforeAndAfter() {
        val artifactDir = File("C:/Users/Amal/.gemini/antigravity/brain/24edfc6f-11e7-41f5-96f3-c41eca888903")
        artifactDir.mkdirs()

        // 1. Measure BEFORE on bright lavender card backdrop
        val lavenderCard = Color(0xFFC3A5FF) // typical bright carousel card
        var isFixed by mutableStateOf(false)

        composeTestRule.setContent {
            Box(modifier = Modifier.width(360.dp).height(640.dp)) {
                // Background simulating bright card behind the seam region (y=200dp to 400dp)
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxWidth().height(180.dp).background(Color(0xFF0F0E13)))
                    Box(modifier = Modifier.fillMaxWidth().height(260.dp).background(lavenderCard))
                    Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color(0xFF0F0E13)))
                }

                // Panel container
                val panelShape = RoundedCornerShape(topEnd = 36.dp, bottomEnd = 36.dp)
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(320.dp)
                        .clip(panelShape)
                        .background(Color(0xFF171717).copy(alpha = 0.50f)) // glass tint
                ) {
                    if (isFixed) {
                        // Uniform dark veil across whole panel
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(InkPanel.copy(alpha = 0.84f))
                        )
                    }
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .drawBehind {
                                    if (isFixed) drawProfileGlowNew() else drawProfileGlowOld()
                                }
                        )
                        // Content area (DrawerRow items)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                }
            }
        }
        val beforeBitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        FileOutputStream(File(artifactDir, "seam_reproduce_before.png")).use {
            beforeBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        // Measure seam in BEFORE bitmap at x = 160 (center of panel) across header boundary (y ~ 220dp)
        // With density = 1.0 (or default), find the transition
        val sampleX = (160 * beforeBitmap.density / 160).coerceIn(10, beforeBitmap.width - 10)
        val headerY = (220 * beforeBitmap.density / 160).coerceIn(10, beforeBitmap.height - 10)

        // Above seam (y = headerY - 5): inside header, masked by opaque InkPanel
        val colorAboveBefore = beforeBitmap.getPixel(sampleX, headerY - 5)
        // Below seam (y = headerY + 15): in content area, bright lavender shines through glass
        val colorBelowBefore = beforeBitmap.getPixel(sampleX, headerY + 15)

        val lumAboveBefore = relativeLuminance(colorAboveBefore)
        val lumBelowBefore = relativeLuminance(colorBelowBefore)
        val deltaLumBefore = abs(lumBelowBefore - lumAboveBefore)

        println("BEFORE: AboveSeam=$colorAboveBefore (lum=${"%.4f".format(lumAboveBefore)}), BelowSeam=$colorBelowBefore (lum=${"%.4f".format(lumBelowBefore)}), DeltaLum=${"%.4f".format(deltaLumBefore)}")
        // Confirm there is a substantial luminance jump (the seam)
        assertTrue("Before fix, delta luminance across seam must be visible (> 0.10)", deltaLumBefore > 0.10)

        // 2. Measure AFTER with Uniform Veil (0.84) and New Faded Header Glow
        isFixed = true
        composeTestRule.waitForIdle()

        val afterBitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        FileOutputStream(File(artifactDir, "seam_fixed_after.png")).use {
            afterBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        val colorAboveAfter = afterBitmap.getPixel(sampleX, headerY - 5)
        val colorBelowAfter = afterBitmap.getPixel(sampleX, headerY + 15)
        val lumAboveAfter = relativeLuminance(colorAboveAfter)
        val lumBelowAfter = relativeLuminance(colorBelowAfter)
        val deltaLumAfter = abs(lumBelowAfter - lumAboveAfter)

        println("AFTER: AboveSeam=$colorAboveAfter (lum=${"%.4f".format(lumAboveAfter)}), BelowSeam=$colorBelowAfter (lum=${"%.4f".format(lumBelowAfter)}), DeltaLum=${"%.4f".format(deltaLumAfter)}")

        // Seam is eliminated: delta luminance across boundary is smooth (< 0.02)
        assertTrue("After fix, delta luminance across seam must be smooth (< 0.02), got $deltaLumAfter", deltaLumAfter < 0.02)

        // 3. Contrast verification against brightest white background
        val whiteInt = 0xFFFFFFFF.toInt()
        val bgBehindText = colorBelowAfter
        val textContrast = contrastRatio(whiteInt, bgBehindText)
        println("TEXT CONTRAST RATIO against brightest background: ${"%.2f".format(textContrast)}:1")
        assertTrue("Text contrast ratio must exceed WCAG AAA (7.0:1), got $textContrast", textContrast >= 7.0)
    }
}
