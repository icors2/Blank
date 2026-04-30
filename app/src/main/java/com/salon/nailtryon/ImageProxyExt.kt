package com.salon.nailtryon

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

/**
 * Converts [ImageProxy] to [Bitmap] when analysis uses
 * [androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888].
 */
internal fun ImageProxy.toBitmapRgba(): Bitmap {
    require(planes.size == 1) { "Expected single RGBA plane; got ${planes.size}" }

    val plane = planes[0]
    val buffer = plane.buffer.duplicate()
    buffer.rewind()

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride

    val pixels = IntArray(width * height)
    var outputIndex = 0
    for (row in 0 until height) {
        val rowStart = row * rowStride
        for (col in 0 until width) {
            val i = rowStart + col * pixelStride
            buffer.position(i)
            val r = buffer.get().toInt() and 0xFF
            val g = buffer.get().toInt() and 0xFF
            val b = buffer.get().toInt() and 0xFF
            val a = buffer.get().toInt() and 0xFF
            pixels[outputIndex++] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

    val rotationDegrees = imageInfo.rotationDegrees
    return if (rotationDegrees == 0) {
        bitmap
    } else {
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
