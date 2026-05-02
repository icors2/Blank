package com.salon.nailtryon

import android.graphics.Bitmap
import android.graphics.Point
import java.util.ArrayDeque

/**
 * Handles isolation of a single nail from a full segmentation mask using a flood-fill algorithm.
 */
object NailSelector {

    /**
     * Isolates a single nail from an [ALPHA_8] mask bitmap starting from [startX], [startY].
     * Returns a new [ALPHA_8] bitmap of the same size containing only the connected nail.
     */
    fun isolateSingleNail(fullMask: Bitmap, startX: Int, startY: Int): Bitmap {
        val width = fullMask.width
        val height = fullMask.height
        val isolatedMask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        
        // Threshold for what we consider a "nail" pixel
        val threshold = 128 // 0.5 * 255

        val pixels = ByteArray(width * height)
        val outPixels = ByteArray(width * height)
        
        val buffer = java.nio.ByteBuffer.allocate(width * height)
        fullMask.copyPixelsToBuffer(buffer)
        buffer.rewind()
        buffer.get(pixels)

        // If the user tapped on empty space (not a nail), return the empty mask
        val startIdx = startY * width + startX
        if (startIdx < 0 || startIdx >= pixels.size) return isolatedMask
        
        val startAlpha = pixels[startIdx].toInt() and 0xFF
        if (startAlpha < threshold) {
            return isolatedMask
        }

        // Standard Flood Fill queue
        val queue = ArrayDeque<Point>()
        queue.add(Point(startX, startY))
        
        // Use a visited array to avoid cycles/re-processing
        val visited = java.util.BitSet(width * height)
        visited.set(startIdx)
        outPixels[startIdx] = pixels[startIdx]

        val directions = arrayOf(
            Point(0, 1), Point(0, -1), Point(1, 0), Point(-1, 0),
            Point(1, 1), Point(1, -1), Point(-1, 1), Point(-1, -1)
        )

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()

            for (dir in directions) {
                val nextX = current.x + dir.x
                val nextY = current.y + dir.y

                if (nextX in 0 until width && nextY in 0 until height) {
                    val idx = nextY * width + nextX
                    val alpha = pixels[idx].toInt() and 0xFF
                    if (!visited.get(idx) && alpha >= threshold) {
                        visited.set(idx)
                        outPixels[idx] = pixels[idx]
                        queue.add(Point(nextX, nextY))
                    }
                }
            }
        }
        
        val outBuffer = java.nio.ByteBuffer.wrap(outPixels)
        isolatedMask.copyPixelsFromBuffer(outBuffer)
        return isolatedMask
    }

    /**
     * Translates coordinates from a view with [ContentScale.Fit] to bitmap coordinates.
     */
    fun translateCoordinates(
        touchX: Float,
        touchY: Float,
        viewWidth: Float,
        viewHeight: Float,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): Point? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        
        val viewAspectRatio = viewWidth / viewHeight
        val bitmapAspectRatio = bitmapWidth.toFloat() / bitmapHeight

        val drawWidth: Float
        val drawHeight: Float
        val offsetX: Float
        val offsetY: Float

        if (viewAspectRatio > bitmapAspectRatio) {
            drawHeight = viewHeight
            drawWidth = drawHeight * bitmapAspectRatio
            offsetX = (viewWidth - drawWidth) / 2f
            offsetY = 0f
        } else {
            drawWidth = viewWidth
            drawHeight = drawWidth / bitmapAspectRatio
            offsetX = 0f
            offsetY = (viewHeight - drawHeight) / 2f
        }

        val relativeX = (touchX - offsetX) / drawWidth
        val relativeY = (touchY - offsetY) / drawHeight

        if (relativeX < 0f || relativeX > 1f || relativeY < 0f || relativeY > 1f) return null

        return Point(
            (relativeX * bitmapWidth).toInt().coerceIn(0, bitmapWidth - 1),
            (relativeY * bitmapHeight).toInt().coerceIn(0, bitmapHeight - 1)
        )
    }
}
