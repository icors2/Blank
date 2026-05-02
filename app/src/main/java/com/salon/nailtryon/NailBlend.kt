package com.salon.nailtryon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Builds a soft alpha mask from MediaPipe hand landmarks by painting nail-shaped ovals.
 * Runs at [width]×[height] (often downscaled from the photo for speed).
 */
object LandmarkNailMask {
    private val tipIndices = intArrayOf(8, 12, 16, 20)
    private val dipIndices = intArrayOf(7, 11, 15, 19)
    private const val THUMB_TIP = 4
    private const val THUMB_IP = 3

    fun buildSoftMask(
        width: Int,
        height: Int,
        landmarks: List<Pair<Float, Float>>,
        featherPx: Float = 6f,
    ): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(mask)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
            maskFilter = android.graphics.BlurMaskFilter(featherPx, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }

        fun pt(index: Int): Pair<Float, Float> {
            val (nx, ny) = landmarks[index]
            return nx * width to ny * height
        }

        fun oval(cx: Float, cy: Float, nailW: Float, nailH: Float, angleRad: Float) {
            canvas.save()
            canvas.rotate(Math.toDegrees(angleRad.toDouble()).toFloat(), cx, cy)
            val left = cx - nailW / 2f
            val top = cy - nailH / 2f
            val oval = android.graphics.RectF(left, top, left + nailW, top + nailH)
            canvas.drawOval(oval, paint)
            canvas.restore()
        }

        fun finger(tipIdx: Int, dipIdx: Int) {
            val (tx, ty) = pt(tipIdx)
            val (dx, dy) = pt(dipIdx)
            val dirX = tx - dx
            val dirY = ty - dy
            val len = hypot(dirX.toDouble(), dirY.toDouble()).toFloat().coerceAtLeast(1f)
            val ux = dirX / len
            val uy = dirY / len
            val angle = kotlin.math.atan2(uy.toDouble(), ux.toDouble()).toFloat()
            val nailLen = len * 0.42f
            val nailW = len * 0.34f
            val cx = tx - ux * nailLen * 0.35f
            val cy = ty - uy * nailLen * 0.35f
            oval(cx, cy, nailW, nailLen, angle)
        }

        for (i in tipIndices.indices) {
            finger(tipIndices[i], dipIndices[i])
        }

        run {
            val (tx, ty) = pt(THUMB_TIP)
            val (ix, iy) = pt(THUMB_IP)
            val dirX = tx - ix
            val dirY = ty - iy
            val len = hypot(dirX.toDouble(), dirY.toDouble()).toFloat().coerceAtLeast(1f)
            val ux = dirX / len
            val uy = dirY / len
            val angle = kotlin.math.atan2(uy.toDouble(), ux.toDouble()).toFloat()
            val nailLen = len * 0.38f
            val nailW = len * 0.36f
            val cx = tx - ux * nailLen * 0.28f
            val cy = ty - uy * nailLen * 0.28f
            oval(cx, cy, nailW, nailLen, angle)
        }

        return mask
    }
}

/**
 * Recolors nail regions using [mask] as alpha (full resolution). [mask] must match [base] dimensions.
 *
 * Uses **HSV transfer**: keeps value (brightness) from the photo so highlights and shadows stay,
 * and shifts hue/saturation toward the polish — reads more like real lacquer on the nail than
 * flat RGB alpha-over.
 */
fun blendNailPolish(
    base: Bitmap,
    mask: Bitmap,
    polishArgb: Int,
    opacity: Float,
): Bitmap {
    require(base.width == mask.width && base.height == mask.height) {
        "Mask size must match base (${base.width}x${base.height} vs ${mask.width}x${mask.height})"
    }
    val out = base.copy(Bitmap.Config.ARGB_8888, true)
    val w = base.width
    val h = base.height
    val pr = polishArgb shr 16 and 0xFF
    val pg = polishArgb shr 8 and 0xFF
    val pb = polishArgb and 0xFF
    val basePixels = IntArray(w * h)
    val maskPixels = IntArray(w * h)
    base.getPixels(basePixels, 0, w, 0, 0, w, h)
    mask.getPixels(maskPixels, 0, w, 0, 0, w, h)

    val op = opacity.coerceIn(0f, 1f)
    val polishHsv = FloatArray(3)
    AndroidColor.RGBToHSV(pr, pg, pb, polishHsv)
    val hsvPixel = FloatArray(3)

    var i = 0
    while (i < basePixels.size) {
        val ma = (maskPixels[i] shr 24) and 0xFF
        if (ma > 8) {
            val mix = (ma / 255f) * op
            val br = basePixels[i] shr 16 and 0xFF
            val bg = basePixels[i] shr 8 and 0xFF
            val bb = basePixels[i] and 0xFF
            val ba = basePixels[i] shr 24 and 0xFF
            AndroidColor.RGBToHSV(br, bg, bb, hsvPixel)
            val bs = hsvPixel[1]
            val bv = hsvPixel[2]

            val nh = polishHsv[0]
            val ns = bs + (polishHsv[1] - bs) * mix * CHROMA_BLEND
            val nv = bv

            hsvPixel[0] = nh
            hsvPixel[1] = ns.coerceIn(0f, 1f)
            hsvPixel[2] = nv.coerceIn(0f, 1f)
            var blended = AndroidColor.HSVToColor(ba, hsvPixel)

            if (mix > 0.18f && bv > SPECULAR_V_THRESHOLD) {
                val t = ((bv - SPECULAR_V_THRESHOLD) / (1f - SPECULAR_V_THRESHOLD)).coerceIn(0f, 1f)
                val kick = (t * t * SPECULAR_GAIN * mix).toInt()
                var nr = blended shr 16 and 0xFF
                var ng = blended shr 8 and 0xFF
                var nb = blended and 0xFF
                nr = min(255, nr + kick)
                ng = min(255, ng + kick)
                nb = min(255, nb + kick)
                blended = (ba shl 24) or (nr shl 16) or (ng shl 8) or nb
            }

            val nr = blended shr 16 and 0xFF
            val ng = blended shr 8 and 0xFF
            val nb = blended and 0xFF
            val fr = (br * (1 - mix) + nr * mix).toInt().coerceIn(0, 255)
            val fg = (bg * (1 - mix) + ng * mix).toInt().coerceIn(0, 255)
            val fb = (bb * (1 - mix) + nb * mix).toInt().coerceIn(0, 255)
            basePixels[i] = (ba shl 24) or (fr shl 16) or (fg shl 8) or fb
        }
        i++
    }
    out.setPixels(basePixels, 0, w, 0, 0, w, h)
    return out
}

private const val CHROMA_BLEND = 0.94f
private const val SPECULAR_V_THRESHOLD = 0.52f
private const val SPECULAR_GAIN = 28f

/** Applies design-specific tweaks after base polish tint (lightweight, local). */
fun applyDesignToBitmap(bitmap: Bitmap, design: NailDesign, polishArgb: Int, opacity: Float): Bitmap {
    return when (design) {
        NailDesign.SOLID, NailDesign.FRENCH -> bitmap
        NailDesign.GLITTER -> applyGlitter(bitmap, opacity)
        NailDesign.MATTE -> applyMatte(bitmap, opacity)
    }
}

private fun applyGlitter(bitmap: Bitmap, opacity: Float): Bitmap {
    val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val w = out.width
    val h = out.height
    val pixels = IntArray(w * h)
    out.getPixels(pixels, 0, w, 0, 0, w, h)
    var seed = 42L
    var i = 0
    while (i < pixels.size) {
        seed = seed * 6364136223846793005L + 1
        if ((seed and 7L) == 0L) {
            val p = pixels[i]
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            val a = p shr 24 and 0xFF
            val boost = (18 * opacity).toInt().coerceIn(0, 60)
            pixels[i] = (a shl 24) or
                ((min(255, r + boost)) shl 16) or
                ((min(255, g + boost)) shl 8) or
                min(255, b + boost)
        }
        i++
    }
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
}

private fun applyMatte(bitmap: Bitmap, opacity: Float): Bitmap {
    val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val w = out.width
    val h = out.height
    val pixels = IntArray(w * h)
    out.getPixels(pixels, 0, w, 0, 0, w, h)
    val darken = (25 * opacity).toInt().coerceIn(0, 40)
    var i = 0
    while (i < pixels.size) {
        val p = pixels[i]
        val r = (p shr 16 and 0xFF) - darken
        val g = (p shr 8 and 0xFF) - darken
        val b = (p and 0xFF) - darken
        val a = p shr 24 and 0xFF
        pixels[i] = (a shl 24) or
            (max(0, r) shl 16) or
            (max(0, g) shl 8) or
            max(0, b)
        i++
    }
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
}

fun Color.toPolishArgb(): Int = toArgb()
