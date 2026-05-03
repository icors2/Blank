package com.salon.nailtryon

import android.graphics.Bitmap

/**
 * Scans the TFLite output bitmap to find the nail's physical coordinates.
 */
object MaskAnalyzer {

    /**
     * Scans the TFLite output bitmap to find the nail's physical coordinates,
     * accounting for rotation using the provided angle.
     */
    fun analyzeNailMask(maskBitmap: Bitmap, angleDegrees: Float): NailGeometry? {
        val width = maskBitmap.width
        val height = maskBitmap.height
        
        // Direction vector for the finger (Surveyor)
        val angleRad = Math.toRadians((angleDegrees - 90).toDouble())
        val ux = Math.cos(angleRad).toFloat()
        val uy = Math.sin(angleRad).toFloat()
        
        // Normal vector (Perpendicular to finger)
        val nx = -uy
        val ny = ux
        
        var minProjN = Float.MAX_VALUE
        var maxProjN = Float.MIN_VALUE
        var minProjU = Float.MAX_VALUE
        var maxProjU = Float.MIN_VALUE
        
        var count = 0
        var sumX = 0f
        var sumY = 0f

        for (y in 0 until height) {
            for (x in 0 until width) {
                val alpha = (maskBitmap.getPixel(x, y) shr 24) and 0xFF
                if (alpha > 128) {
                    val px = x.toFloat()
                    val py = y.toFloat()
                    
                    // Project onto normal (for width)
                    val projN = px * nx + py * ny
                    minProjN = minOf(minProjN, projN)
                    maxProjN = maxOf(maxProjN, projN)
                    
                    // Project onto direction (for length)
                    val projU = px * ux + py * uy
                    minProjU = minOf(minProjU, projU)
                    maxProjU = maxOf(maxProjU, projU)
                    
                    sumX += px
                    sumY += py
                    count++
                }
            }
        }

        if (count == 0) return null

        val nailWidth = maxProjN - minProjN
        val nailLength = maxProjU - minProjU
        
        // Center point in pixel space
        val centerX = sumX / count
        val centerY = sumY / count
        
        // The "Cuticle" point is the one furthest back along the finger direction (minProjU)
        // We find the average of pixels at the very base to get a stable anchor point.
        var cuticleSumX = 0f
        var cuticleSumY = 0f
        var cuticleCount = 0
        val thresholdU = minProjU + (nailLength * 0.03f) // Narrow 3% window for extreme precision

        for (y in 0 until height) {
            for (x in 0 until width) {
                val alpha = (maskBitmap.getPixel(x, y) shr 24) and 0xFF
                if (alpha > 128) {
                    val px = x.toFloat()
                    val py = y.toFloat()
                    val projU = px * ux + py * uy
                    if (projU < thresholdU) {
                        cuticleSumX += px
                        cuticleSumY += py
                        cuticleCount++
                    }
                }
            }
        }

        val cuticleX = if (cuticleCount > 0) cuticleSumX / cuticleCount else centerX
        val cuticleY = if (cuticleCount > 0) cuticleSumY / cuticleCount else centerY

        return NailGeometry(centerX, centerY, nailWidth, nailLength, angleDegrees, cuticleX, cuticleY)
    }

    /**
     * Calculates the rotation angle between two points (e.g., DIP and Tip).
     */
    fun calculateAngle(p1: Pair<Float, Float>, p2: Pair<Float, Float>): Float {
        val dy = p2.second - p1.second
        val dx = p2.first - p1.first
        // Use atan2 and convert to degrees. 
        // We add 90 because the vector drawable usually points 'up' (negative Y).
        return Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
    }
}
