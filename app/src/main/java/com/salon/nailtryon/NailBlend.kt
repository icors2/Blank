package com.salon.nailtryon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.salon.nailtryon.R
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
            val nailLen = len * 0.55f
            val nailW = len * 0.38f
            val cx = tx - ux * nailLen * 0.45f
            val cy = ty - uy * nailLen * 0.45f
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
            val nailLen = len * 0.50f
            val nailW = len * 0.40f
            val cx = tx - ux * nailLen * 0.40f
            val cy = ty - uy * nailLen * 0.40f
            oval(cx, cy, nailW, nailLen, angle)
        }

        return mask
    }

    fun buildVectorMask(
        context: Context,
        width: Int,
        height: Int,
        landmarks: List<Pair<Float, Float>>,
        shape: NailShape
    ): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(mask)
        
        val resId = when (shape) {
            NailShape.COFFIN -> R.drawable.coffin_vector // Assuming converted to XML
            else -> return mask
        }

        fun pt(index: Int): Pair<Float, Float> {
            val (nx, ny) = landmarks[index]
            return nx * width to ny * height
        }

        fun drawFinger(tipIdx: Int, dipIdx: Int, isThumb: Boolean = false) {
            val (tx, ty) = pt(tipIdx)
            val (dx, dy) = pt(dipIdx)
            val dirX = tx - dx
            val dirY = ty - dy
            val len = hypot(dirX.toDouble(), dirY.toDouble()).toFloat().coerceAtLeast(1f)
            val ux = dirX / len
            val uy = dirY / len
            val angle = Math.toDegrees(kotlin.math.atan2(uy.toDouble(), ux.toDouble())).toFloat()

            val drawable = ContextCompat.getDrawable(context, resId) ?: return
            val svgW = drawable.intrinsicWidth.toFloat()
            val svgH = drawable.intrinsicHeight.toFloat()

            val matrix = Matrix()

            // Center horizontally and move base to origin
            matrix.preTranslate(-svgW / 2f, -svgH)
            
            // Scale based on finger length
            val scale = (len * (if (isThumb) 0.55f else 0.65f)) / svgH
            matrix.postScale(scale, scale)
            
            // Rotate to match finger direction (angle + 90 because vector points 'up')
            matrix.postRotate(angle + 90f)
            
            // Translate to fingertip position
            matrix.postTranslate(tx, ty)
            
            // Nudge slightly back towards the hand to overlap the nail bed
            matrix.postTranslate(-ux * len * 0.15f, -uy * len * 0.15f)

            // Draw with white tint into ALPHA_8 mask
            drawNailShape(context, canvas, resId, "#FFFFFF", matrix)
        }

        for (i in tipIndices.indices) {
            drawFinger(tipIndices[i], dipIndices[i])
        }
        drawFinger(THUMB_TIP, THUMB_IP, isThumb = true)

        return mask
    }

    fun buildFrenchTipMask(
        width: Int,
        height: Int,
        landmarks: List<Pair<Float, Float>>,
        shape: NailShape = NailShape.NATURAL
    ): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(mask)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
            // Slightly blur the French tip edge for a natural look
            maskFilter = android.graphics.BlurMaskFilter(2f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }

        fun pt(index: Int): Pair<Float, Float> {
            val (nx, ny) = landmarks[index]
            return nx * width to ny * height
        }

        fun drawTip(tipIdx: Int, dipIdx: Int, isThumb: Boolean = false) {
            val (tx, ty) = pt(tipIdx)
            val (dx, dy) = pt(dipIdx)
            val dirX = tx - dx
            val dirY = ty - dy
            val len = hypot(dirX.toDouble(), dirY.toDouble()).toFloat().coerceAtLeast(1f)
            val ux = dirX / len
            val uy = dirY / len
            val angle = kotlin.math.atan2(uy.toDouble(), ux.toDouble()).toFloat()
            
            // French tip is at the very end of the nail, but needs to overlap the nail bed
            val tipW = len * (if (isThumb) 0.42f else 0.38f)
            val tipH = len * 0.22f // Slightly thicker tip for visibility
            
            canvas.save()
            canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat(), tx, ty)
            
            // Position the tip: tx, ty is the fingertip. 
            // We want the tip to extend from the fingertip slightly back towards the DIP.
            // Rect is defined relative to the rotated canvas.
            val rect = android.graphics.RectF(
                tx - tipW / 2f, 
                ty - tipH * 0.2f, // Extend slightly past the tip
                tx + tipW / 2f, 
                ty + tipH * 0.8f  // Most of the tip goes "down" into the nail area
            )
            canvas.drawOval(rect, paint)
            canvas.restore()
        }

        for (i in tipIndices.indices) {
            drawTip(tipIndices[i], dipIndices[i])
        }
        drawTip(THUMB_TIP, THUMB_IP, isThumb = true)

        return mask
    }
}

/**
 * recolors nail regions using [mask] as alpha (full resolution). [mask] must match [base] dimensions.
 *
 * Uses **Luminance-Preserving Blending**: keeps the underlying shading (highlights/shadows)
 * from the photo and applies the polish color. Adds a gloss pass for photorealism.
 */
fun blendNailPolish(
    base: Bitmap,
    mask: Bitmap,
    polishArgb: Int,
    opacity: Float,
    design: NailDesign = NailDesign.SOLID,
    tipMask: Bitmap? = null
): Bitmap {
    require(base.width == mask.width && base.height == mask.height) {
        "Mask size must match base (${base.width}x${base.height} vs ${mask.width}x${mask.height})"
    }
    val out = base.copy(Bitmap.Config.ARGB_8888, true)
    val w = base.width
    val h = base.height
    
    // Primary polish color
    val pr = (polishArgb shr 16 and 0xFF) / 255f
    val pg = (polishArgb shr 8 and 0xFF) / 255f
    val pb = (polishArgb and 0xFF) / 255f
    
    // French tip color (usually white)
    val tr = 1.0f
    val tg = 1.0f
    val tb = 1.0f

    val basePixels = IntArray(w * h)
    val maskPixels = IntArray(w * h)
    val tipPixels = if (tipMask != null && design == NailDesign.FRENCH) IntArray(w * h) else null
    
    base.getPixels(basePixels, 0, w, 0, 0, w, h)
    mask.getPixels(maskPixels, 0, w, 0, 0, w, h)
    if (tipPixels != null && tipMask != null) {
        tipMask.getPixels(tipPixels, 0, w, 0, 0, w, h)
    }

    val op = opacity.coerceIn(0f, 1f)
    val isMatte = design == NailDesign.MATTE
    val isGlitter = design == NailDesign.GLITTER
    val isFrench = design == NailDesign.FRENCH

    var i = 0
    while (i < basePixels.size) {
        val ma = (maskPixels[i] shr 24) and 0xFF
        if (ma > 2) {
            val mix = (ma / 255f) * op
            val color = basePixels[i]
            val br = (color shr 16 and 0xFF) / 255f
            val bg = (color shr 8 and 0xFF) / 255f
            val bb = (color and 0xFF) / 255f
            val ba = (color shr 24 and 0xFF)

            // Calculate perceived brightness (luminance)
            val lum = 0.299f * br + 0.587f * bg + 0.114f * bb
            
            // Determine if this pixel is part of the French tip
            val tipAlpha = if (tipPixels != null) (tipPixels[i] shr 24 and 0xFF) / 255f else 0f
            
            // Base tinted color
            var nr: Float
            var ng: Float
            var nb: Float
            
            if (isFrench && tipAlpha > 0.05f) {
                // Blend between polish and tip color based on tipAlpha
                // French tips look better with a bit more brightness
                val tipLum = (lum * 1.2f).coerceAtMost(1.0f)
                nr = (pr * (1 - tipAlpha) + tr * tipAlpha) * tipLum
                ng = (pg * (1 - tipAlpha) + tg * tipAlpha) * tipLum
                nb = (pb * (1 - tipAlpha) + tb * tipAlpha) * tipLum
            } else {
                // Matte effect: slightly flatten the color base
                val baseLum = if (isMatte) lum * 0.9f else lum * 1.1f
                nr = pr * baseLum
                ng = pg * baseLum
                nb = pb * baseLum
            }
            
            // Specular highlights: significantly reduced for Matte
            val currentGlossIntensity = if (isMatte) GLOSS_INTENSITY * 0.1f else GLOSS_INTENSITY
            if (lum > SPECULAR_V_THRESHOLD) {
                val specular = ((lum - SPECULAR_V_THRESHOLD) / (1f - SPECULAR_V_THRESHOLD)).coerceAtLeast(0f)
                val gloss = Math.pow(specular.toDouble(), 1.5).toFloat() * currentGlossIntensity
                nr += gloss
                ng += gloss
                nb += gloss
            }

            // Glitter effect: random bright speckles
            if (isGlitter) {
                // Pseudo-random noise
                val noise = ((i * 1103515245 + 12345) and 0x7FFFFFFF).toFloat() / 0x7FFFFFFF
                if (noise > 0.92f) { // ~8% of pixels
                    val sparkle = (noise - 0.92f) * 10.0f * op // More intense
                    nr += sparkle
                    ng += sparkle
                    nb += sparkle
                }
            }

            // Final blend between base photo and processed nail
            val fr = (br * (1 - mix) + nr * mix).coerceIn(0f, 1f)
            val fg = (bg * (1 - mix) + ng * mix).coerceIn(0f, 1f)
            val fb = (bb * (1 - mix) + nb * mix).coerceIn(0f, 1f)

            basePixels[i] = (ba shl 24) or 
                ((fr * 255).toInt() shl 16) or 
                ((fg * 255).toInt() shl 8) or 
                (fb * 255).toInt()
        }
        i++
    }
    out.setPixels(basePixels, 0, w, 0, 0, w, h)
    return out
}


private const val SPECULAR_V_THRESHOLD = 0.45f
private const val GLOSS_INTENSITY = 0.35f

/** 
 * Removed standalone design applier as logic was moved into blendNailPolish for 
 * performance and to properly access mask/specular data. 
 */

fun Color.toPolishArgb(): Int = toArgb()

// ==========================================
// [LABEL: SVG Vector Tinting & Drawing]
// Uses VectorDrawableCompat for maximum device compatibility
// ==========================================

fun drawNailShape(context: Context, canvas: Canvas, shapeResId: Int, brandColorHex: String, matrix: Matrix) {
    // 1. Load the Vector (SVG)
    val drawable = ContextCompat.getDrawable(context, shapeResId) ?: return
    
    // 2. Apply the Tint (The brand color)
    val color = AndroidColor.parseColor(brandColorHex)
    
    // Use DrawableCompat to ensure tinting works on older Android versions
    val wrappedDrawable = DrawableCompat.wrap(drawable).mutate()
    DrawableCompat.setTint(wrappedDrawable, color)
    
    // 3. Draw to Canvas
    // Vectors don't have a 'matrix' parameter in their draw() method, 
    // so we apply the matrix to the canvas itself
    canvas.save()
    canvas.concat(matrix)
    
    // Set the bounds (size) of the vector before drawing
    wrappedDrawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
    wrappedDrawable.draw(canvas)
    
    canvas.restore()
}
