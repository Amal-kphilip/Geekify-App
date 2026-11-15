package com.geekify.android.ui.ascii

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import kotlin.math.max

/**
 * Pre-rendered alpha-mask glyph atlas for ASCII characters 0..127.
 * Pre-computes anti-aliased font rasterization once per (cw, ch, textSize)
 * so per-cell glyph compositing on the simulation thread avoids any Skia drawText overhead.
 */
class AsciiGlyphAtlas(
    val cw: Int,
    val ch: Int,
    val textSize: Float,
    val masks: ByteArray // 128 * cw * ch alpha values (0..255)
) {
    companion object {
        private var cachedCw = -1
        private var cachedCh = -1
        private var cachedTextSize = -1f
        private var cachedAtlas: AsciiGlyphAtlas? = null

        /**
         * Returns a cached atlas for the given cell dimensions and text size, or creates a new one.
         */
        @Synchronized
        fun getOrCreate(cw: Float, ch: Float): AsciiGlyphAtlas {
            val cwInt = max(1, cw.toInt())
            val chInt = max(1, ch.toInt())
            val textSize = max(8f, ch * 0.74f)

            val current = cachedAtlas
            if (current != null && cachedCw == cwInt && cachedCh == chInt && kotlin.math.abs(cachedTextSize - textSize) < 0.01f) {
                return current
            }

            val atlas = create(cwInt, chInt, textSize)
            cachedCw = cwInt
            cachedCh = chInt
            cachedTextSize = textSize
            cachedAtlas = atlas
            return atlas
        }

        fun create(cwInt: Int, chInt: Int, textSize: Float): AsciiGlyphAtlas {
            val totalGlyphs = 128
            val masks = ByteArray(totalGlyphs * cwInt * chInt)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                this.textSize = textSize
                textAlign = Paint.Align.CENTER
                color = Color.WHITE
            }
            val textOffsetY = -(paint.descent() + paint.ascent()) * 0.5f
            val halfCw = cwInt * 0.5f
            val halfCh = chInt * 0.5f + textOffsetY

            try {
                val singleBitmap = Bitmap.createBitmap(cwInt, chInt, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(singleBitmap)
                val charPixels = IntArray(cwInt * chInt)
                val charBuf = CharArray(1)

                for (c in 32..126) {
                    canvas.drawColor(0, PorterDuff.Mode.CLEAR)
                    charBuf[0] = c.toChar()
                    canvas.drawText(charBuf, 0, 1, halfCw, halfCh, paint)
                    singleBitmap.getPixels(charPixels, 0, cwInt, 0, 0, cwInt, chInt)

                    val baseOffset = c * cwInt * chInt
                    for (i in 0 until cwInt * chInt) {
                        val alpha = (charPixels[i] ushr 24) and 0xFF
                        masks[baseOffset + i] = alpha.toByte()
                    }
                }
                singleBitmap.recycle()
            } catch (_: Throwable) {
                // Headless fallback if Bitmap creation fails in stripped environments
            }

            return AsciiGlyphAtlas(cwInt, chInt, textSize, masks)
        }
    }
}
