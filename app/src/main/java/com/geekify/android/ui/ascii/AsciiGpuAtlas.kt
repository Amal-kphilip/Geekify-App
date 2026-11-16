package com.geekify.android.ui.ascii

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.abs
import kotlin.math.max

/**
 * Pre-rendered device-resolution glyph atlas for AGSL RuntimeShader.
 *
 * Renders characters 0..127 into a 16x8 cell texture atlas.
 * Stored as a BitmapShader to be bound as an input shader in AGSL.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class AsciiGpuAtlas(
    val atlasCw: Int,
    val atlasCh: Int,
    val textSize: Float,
    val bitmap: Bitmap,
    val shader: BitmapShader
) {
    companion object {
        const val ATLAS_COLS = 16
        const val ATLAS_ROWS = 8

        private var cachedCw = -1
        private var cachedCh = -1
        private var cachedTextSize = -1f
        private var cachedInstance: AsciiGpuAtlas? = null

        @Synchronized
        fun getOrCreate(cw: Float, ch: Float, scaleFactor: Float = 1.0f): AsciiGpuAtlas {
            val safeScale = scaleFactor.coerceIn(0.5f, 4.0f)
            val atlasCw = max(1, (cw * safeScale).toInt())
            val atlasCh = max(1, (ch * safeScale).toInt())
            val textSize = max(8f, ch * safeScale * 0.74f)

            val current = cachedInstance
            if (current != null && cachedCw == atlasCw && cachedCh == atlasCh && abs(cachedTextSize - textSize) < 0.01f && !current.bitmap.isRecycled) {
                return current
            }

            val atlas = create(atlasCw, atlasCh, textSize)
            cachedCw = atlasCw
            cachedCh = atlasCh
            cachedTextSize = textSize
            cachedInstance = atlas
            return atlas
        }

        private fun create(atlasCw: Int, atlasCh: Int, textSize: Float): AsciiGpuAtlas {
            val totalW = ATLAS_COLS * atlasCw
            val totalH = ATLAS_ROWS * atlasCh

            val bitmap = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                this.textSize = textSize
                textAlign = Paint.Align.CENTER
                color = Color.WHITE
            }
            val textOffsetY = -(paint.descent() + paint.ascent()) * 0.5f
            val charBuf = CharArray(1)

            for (c in 32..126) {
                val col = c % ATLAS_COLS
                val row = c / ATLAS_COLS
                val originX = col * atlasCw + atlasCw * 0.5f
                val originY = row * atlasCh + atlasCh * 0.5f + textOffsetY
                charBuf[0] = c.toChar()
                canvas.drawText(charBuf, 0, 1, originX, originY, paint)
            }

            val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                shader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            }

            return AsciiGpuAtlas(atlasCw, atlasCh, textSize, bitmap, shader)
        }
    }
}
