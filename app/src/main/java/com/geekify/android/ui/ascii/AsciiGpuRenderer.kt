package com.geekify.android.ui.ascii

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * GPU hardware-accelerated ASCII renderer using Android 13+ AGSL RuntimeShader.
 *
 * Runs inside the Compose draw phase via a RuntimeShader drawn into the native Canvas.
 * Keeps memory footprint minimal by uploading cols x rows cell textures (A, B, C)
 * and evaluating font glyphs from a pre-rendered GPU glyph atlas.
 * Seamlessly samples with glass backdrops (hazeSource / layerBackdrop).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class AsciiGpuRenderer {

    companion object {
        const val AGSL_SOURCE = """
uniform shader uCellCurrA;
uniform shader uCellCurrB;
uniform shader uCellCurrC;
uniform shader uCellPrevA;
uniform shader uCellPrevB;
uniform shader uCellPrevC;
uniform shader uAtlas;
uniform float uInterp;
uniform float2 uCellSize;
uniform float2 uGridSize;
uniform float2 uAtlasCell;
uniform float3 uActiveBg;
uniform float uGrid;
uniform float uTrail;
uniform float uTime;
uniform float3 uContrast;
uniform float3 uTrailColor;

half4 main(float2 fragCoord) {
    float col = floor(fragCoord.x / uCellSize.x);
    float row = floor(fragCoord.y / uCellSize.y);
    if (col < 0.0 || col >= uGridSize.x || row < 0.0 || row >= uGridSize.y) {
        return half4(uActiveBg / 255.0, 1.0);
    }

    float localX = fragCoord.x - col * uCellSize.x;
    float localY = fragCoord.y - row * uCellSize.y;

    float2 cellCoord = float2(col + 0.5, row + 0.5);
    half4 currA = uCellCurrA.eval(cellCoord);
    half4 currB = uCellCurrB.eval(cellCoord);
    half4 currC = uCellCurrC.eval(cellCoord);
    half4 prevA = uCellPrevA.eval(cellCoord);
    half4 prevB = uCellPrevB.eval(cellCoord);
    half4 prevC = uCellPrevC.eval(cellCoord);

    // 1. Solid background
    half3 bg = uActiveBg / 255.0;
    half3 color = bg;

    // 2. Grid lines
    if (uGrid > 0.01) {
        if (localX < 1.0 || localY < 1.0) {
            half ga = clamp(0.055 * uGrid, 0.0, 0.35);
            half3 gridTarget = (bg.r + bg.g + bg.b > 1.5) ? half3(0.0, 0.0, 0.0) : half3(1.0, 1.0, 1.0);
            color = mix(color, gridTarget, ga);
        }
    }

    // Unpack solid / energy
    half isSolidCurr = currC.b > 0.5 ? 1.0 : 0.0;
    half isSolidPrev = prevC.b > 0.5 ? 1.0 : 0.0;
    half isSolid = mix(isSolidPrev, isSolidCurr, uInterp);

    // 3. Trail particles
    if (uTrail > 0.01 && isSolid < 0.5) {
        half energyCurr = currC.g;
        half energyPrev = prevC.g;
        half energy = mix(energyPrev, energyCurr, uInterp);

        if (energy >= 0.12) {
            float dotSize = max(2.0, floor(uCellSize.x * 0.22));
            if (energy > 0.6) {
                dotSize += 1.0;
            }
            float ox = floor((uCellSize.x - dotSize) * 0.5);
            float oy = floor((uCellSize.y - dotSize) * 0.5);

            if (localX >= ox && localX < ox + dotSize && localY >= oy && localY < oy + dotSize) {
                float hash = fract(sin(col * 12.9898 + row * 78.233 + 3.0) * 43758.5453);
                float twinkle = 0.75 + 0.25 * sin(uTime * 5.0 + hash * 40.0);
                half alpha = clamp(half(energy * twinkle * 0.42), 0.08, 0.75);
                half3 inkCol = uTrailColor / 255.0;
                color = mix(color, inkCol, alpha);
            }
        }
    }

    // 4. Solid block fill
    if (isSolid >= 0.5) {
        half3 fillColCurr = currB.rgb;
        half3 fillColPrev = prevB.rgb;
        half3 fillCol = mix(fillColPrev, fillColCurr, uInterp);
        if (localX >= 1.0 && localY >= 1.0) {
            color = fillCol;
        }
    }

    // 5. Text glyph via Atlas
    half glyphCode = uInterp < 0.5 ? prevC.r * 255.0 : currC.r * 255.0;
    int code = int(floor(glyphCode + 0.5));

    if (code >= 33 && code <= 126) {
        half3 textCol = mix(prevA.rgb, currA.rgb, uInterp);

        float fCode = float(code);
        float fRow = floor(fCode * 0.0625);
        float fCol = fCode - fRow * 16.0;

        float u = localX / uCellSize.x;
        float v = localY / uCellSize.y;

        if (u >= 0.0 && u < 1.0 && v >= 0.0 && v < 1.0) {
            float atlasX = (fCol + u) * uAtlasCell.x;
            float atlasY = (fRow + v) * uAtlasCell.y;
            half glyphAlpha = uAtlas.eval(float2(atlasX, atlasY)).a;
            color = mix(color, textCol, glyphAlpha);
        }
    }

    return half4(color, 1.0);
}
"""
    }

    private var shader: RuntimeShader? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var cols = 0
    private var rows = 0

    internal var currBitmapA: Bitmap? = null
    internal var currBitmapB: Bitmap? = null
    internal var currBitmapC: Bitmap? = null
    private var prevBitmapA: Bitmap? = null
    private var prevBitmapB: Bitmap? = null
    private var prevBitmapC: Bitmap? = null

    private var currShaderA: BitmapShader? = null
    private var currShaderB: BitmapShader? = null
    private var currShaderC: BitmapShader? = null
    private var prevShaderA: BitmapShader? = null
    private var prevShaderB: BitmapShader? = null
    private var prevShaderC: BitmapShader? = null

    private var currPixelsA: IntArray = IntArray(0)
    private var currPixelsB: IntArray = IntArray(0)
    private var currPixelsC: IntArray = IntArray(0)
    private var prevPixelsA: IntArray = IntArray(0)
    private var prevPixelsB: IntArray = IntArray(0)
    private var prevPixelsC: IntArray = IntArray(0)

    init {
        try {
            shader = RuntimeShader(AGSL_SOURCE)
            paint.shader = shader
        } catch (t: Throwable) {
            AsciiGpuSupport.disableGpu(t)
        }
    }

    private fun ensureBitmaps(newCols: Int, newRows: Int) {
        if (cols == newCols && rows == newRows && currBitmapA != null && !currBitmapA!!.isRecycled) {
            return
        }

        cols = newCols
        rows = newRows
        val total = cols * rows

        currPixelsA = IntArray(total)
        currPixelsB = IntArray(total)
        currPixelsC = IntArray(total)
        prevPixelsA = IntArray(total)
        prevPixelsB = IntArray(total)
        prevPixelsC = IntArray(total)

        currBitmapA?.recycle()
        currBitmapB?.recycle()
        currBitmapC?.recycle()
        prevBitmapA?.recycle()
        prevBitmapB?.recycle()
        prevBitmapC?.recycle()

        currBitmapA = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        currBitmapB = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        currBitmapC = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        prevBitmapA = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        prevBitmapB = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        prevBitmapC = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)

        currShaderA = BitmapShader(currBitmapA!!, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
        }
        currShaderB = BitmapShader(currBitmapB!!, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
        }
        currShaderC = BitmapShader(currBitmapC!!, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
        }
        prevShaderA = BitmapShader(prevBitmapA!!, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
        }
        prevShaderB = BitmapShader(prevBitmapB!!, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
        }
        prevShaderC = BitmapShader(prevBitmapC!!, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
        }
    }

    /**
     * Updates frame cell textures on simulation tick.
     */
    fun uploadFrame(frame: AsciiFrame) {
        if (frame.cols <= 0 || frame.rows <= 0) return

        try {
            ensureBitmaps(frame.cols, frame.rows)

            // Shift current to previous
            System.arraycopy(currPixelsA, 0, prevPixelsA, 0, currPixelsA.size)
            System.arraycopy(currPixelsB, 0, prevPixelsB, 0, currPixelsB.size)
            System.arraycopy(currPixelsC, 0, prevPixelsC, 0, currPixelsC.size)

            prevBitmapA?.setPixels(prevPixelsA, 0, frame.cols, 0, 0, frame.cols, frame.rows)
            prevBitmapB?.setPixels(prevPixelsB, 0, frame.cols, 0, 0, frame.cols, frame.rows)
            prevBitmapC?.setPixels(prevPixelsC, 0, frame.cols, 0, 0, frame.cols, frame.rows)

            // Populate current
            val total = frame.cols * frame.rows
            val colors = frame.cellColor
            val glyphs = frame.cellGlyph
            val fills = frame.cellFill
            val flags = frame.cellFlag
            val energies = frame.energy

            for (i in 0 until total) {
                val flag = if (i < flags.size) flags[i] else CELL_NONE
                val glyph = if (i < glyphs.size) glyphs[i].code.coerceIn(0, 127) else 0
                val safeGlyph = if (flag == CELL_NONE) 0 else glyph
                val color = if (i < colors.size) colors[i] else 0

                // Bitmap A: Foreground Color (fully opaque, ARGB: R in bits 16..23, G in 8..15, B in 0..7)
                currPixelsA[i] = (0xFF shl 24) or (color and 0x00FFFFFF)

                // Bitmap B: Fill Color (fully opaque, ARGB: R in bits 16..23, G in 8..15, B in 0..7)
                val fill = if (flag == CELL_SOLID && i < fills.size) fills[i] else 0
                currPixelsB[i] = (0xFF shl 24) or (fill and 0x00FFFFFF)

                // Bitmap C: Cell Data (safeGlyph in R [bits 16..23], energy in G [bits 8..15], isSolid in B [bits 0..7], opaque A)
                val e = if (i < energies.size) energies[i] else 0f
                val eByte = (e * 255f).toInt().coerceIn(0, 255)
                val isSolidByte = if (flag == CELL_SOLID) 255 else 0
                currPixelsC[i] = (0xFF shl 24) or (safeGlyph shl 16) or (eByte shl 8) or isSolidByte
            }

            currBitmapA?.setPixels(currPixelsA, 0, frame.cols, 0, 0, frame.cols, frame.rows)
            currBitmapB?.setPixels(currPixelsB, 0, frame.cols, 0, 0, frame.cols, frame.rows)
            currBitmapC?.setPixels(currPixelsC, 0, frame.cols, 0, 0, frame.cols, frame.rows)
        } catch (t: Throwable) {
            AsciiGpuSupport.disableGpu(t)
        }
    }

    /**
     * Renders the interpolated frame onto the canvas using the AGSL shader.
     */
    fun draw(
        canvas: Canvas,
        frame: AsciiFrame,
        uInterp: Float,
        scaleFactor: Float
    ): Boolean {
        if (!AsciiGpuSupport.isSupported()) return false
        val s = shader ?: return false

        try {
            if (cols <= 0 || rows <= 0 || currBitmapA == null) {
                uploadFrame(frame)
            }

            val atlas = AsciiGpuAtlas.getOrCreate(frame.cw, frame.ch, scaleFactor)

            s.setInputShader("uCellCurrA", currShaderA!!)
            s.setInputShader("uCellCurrB", currShaderB!!)
            s.setInputShader("uCellCurrC", currShaderC!!)
            s.setInputShader("uCellPrevA", prevShaderA!!)
            s.setInputShader("uCellPrevB", prevShaderB!!)
            s.setInputShader("uCellPrevC", prevShaderC!!)
            s.setInputShader("uAtlas", atlas.shader)

            s.setFloatUniform("uInterp", uInterp.coerceIn(0f, 1f))
            s.setFloatUniform("uCellSize", frame.cw, frame.ch)
            s.setFloatUniform("uGridSize", frame.cols.toFloat(), frame.rows.toFloat())
            s.setFloatUniform("uAtlasCell", atlas.atlasCw.toFloat(), atlas.atlasCh.toFloat())
            s.setFloatUniform("uActiveBg", frame.activeBg[0], frame.activeBg[1], frame.activeBg[2])
            s.setFloatUniform("uGrid", frame.grid)
            s.setFloatUniform("uTrail", frame.trail)
            s.setFloatUniform("uTime", frame.timeSec)
            s.setFloatUniform("uContrast", frame.contrast[0], frame.contrast[1], frame.contrast[2])
            s.setFloatUniform("uTrailColor", frame.trailColor[0], frame.trailColor[1], frame.trailColor[2])

            canvas.drawRect(0f, 0f, frame.width, frame.height, paint)
            return true
        } catch (t: Throwable) {
            AsciiGpuSupport.disableGpu(t)
            return false
        }
    }
}
