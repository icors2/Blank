package com.salon.nailtryon

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat

/**
 * Tints and warps the SVG extension to fit the detected nail geometry.
 */
class NailShapeRenderer(private val context: Context) {

    /**
     * Draws a nail extension anchored to the cuticle.
     * 
     * @param lengthMultiplier How much longer the extension is than the natural nail (e.g. 1.5f)
     * @param widthMultiplier Fudge factor to ensure full coverage (e.g. 1.15f)
     */
    fun drawExtension(
        canvas: Canvas, 
        geometry: NailGeometry, 
        shapeDrawableId: Int,
        brandHexColor: String,
        lengthMultiplier: Float = 1.5f,
        widthMultiplier: Float = 1.15f,
        design: NailDesign = NailDesign.SOLID
    ) {
        val drawable = ContextCompat.getDrawable(context, shapeDrawableId) ?: return
        
        // 1. Prepare Base Color with Translucency
        val baseColor = Color.parseColor(brandHexColor)
        // Alpha Translucency: ~90% (230/255)
        val alphaColor = ColorUtils.setAlphaComponent(baseColor, 230)
        
        val wrappedDrawable = DrawableCompat.wrap(drawable).mutate()
        DrawableCompat.setTint(wrappedDrawable, alphaColor)

        val intrinsicWidth = drawable.intrinsicWidth.toFloat()
        val intrinsicHeight = drawable.intrinsicHeight.toFloat()

        // Normalized Viewport: 200x300
        // Path Content: Width 160 (20 to 180), Height 280 (20 to 300)
        // Anchor Point: (100, 300)
        val vWidth = 200f
        val vContentW = 160f 
        val vContentH = 280f
        val vAnchorX = 100f
        val vAnchorY = 300f 

        val pxScale = intrinsicWidth / vWidth
        val intrinsicContentW = vContentW * pxScale
        val intrinsicContentH = vContentH * pxScale
        val intrinsicAnchorX = vAnchorX * pxScale
        val intrinsicAnchorY = vAnchorY * pxScale

        // 1. DYNAMIC WIDTH
        val scaleX = (geometry.width * widthMultiplier) / intrinsicContentW
        
        // 2. DYNAMIC LENGTH
        // Scale relative to natural nail length
        val scaleY = (geometry.length * lengthMultiplier) / intrinsicContentH

        val matrix = Matrix()

        // Step A: Move to BOTTOM-CENTER anchor
        matrix.postTranslate(-intrinsicAnchorX, -intrinsicAnchorY)
        
        // Step B: Scale
        matrix.postScale(scaleX, scaleY)
        
        // Step C: Rotate
        matrix.postRotate(geometry.angleDegrees)
        
        // Step D: Move to Cuticle Center with overlap nudge
        val angleRad = Math.toRadians((geometry.angleDegrees - 90).toDouble())
        val ux = Math.cos(angleRad).toFloat()
        val uy = Math.sin(angleRad).toFloat()
        
        // Overlap: tuck it ~12% of nail width under the skin fold
        val overlap = geometry.width * 0.12f

        matrix.postTranslate(
            geometry.cuticleX - ux * overlap,
            geometry.cuticleY - uy * overlap
        )

        // 5. Render to Canvas
        canvas.save()
        canvas.concat(matrix)
        
        // Draw the base shape
        wrappedDrawable.setBounds(0, 0, intrinsicWidth.toInt(), intrinsicHeight.toInt())
        wrappedDrawable.draw(canvas)
        
        // 6. Apply Cylindrical Shading (3D Illusion)
        val lightPaint = Paint().apply {
            isAntiAlias = true
            // SRC_ATOP ensures the gradient only draws where the base nail was just drawn
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) 
            shader = LinearGradient(
                0f, 0f, intrinsicWidth, 0f, 
                intArrayOf(
                    Color.argb(100, 0, 0, 0),    // Left Sidewall Shadow
                    Color.argb(80, 255, 255, 255), // Center Apex Highlight
                    Color.argb(100, 0, 0, 0)     // Right Sidewall Shadow
                ),
                floatArrayOf(0.0f, 0.5f, 1.0f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, intrinsicWidth, intrinsicHeight, lightPaint)
        
        // 7. Add design-specific overlays
        if (design == NailDesign.GLITTER) {
            val paint = Paint().apply {
                color = Color.WHITE
                alpha = 180
            }
            val random = java.util.Random(42)
            for (i in 0 until 40) {
                val gx = random.nextFloat() * intrinsicWidth
                val gy = random.nextFloat() * intrinsicHeight
                // Only draw if within the shape content area
                if (gy < intrinsicAnchorY && gx > 20 && gx < 180) {
                    canvas.drawCircle(gx, gy, 1.5f, paint)
                }
            }
        }

        canvas.restore()
    }
}
