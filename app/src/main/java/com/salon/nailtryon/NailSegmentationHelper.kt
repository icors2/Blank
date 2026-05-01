package com.salon.nailtryon

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Optional bundled **nail_seg.tflite** for nail-region masking.
 * Drop a compatible model into `assets/`; until then [isReady] is false and the app uses landmarks only.
 *
 * Contract for the default loader (adjust if your export differs):
 * - Input: float32 **NHWC** `[1, H, W, 3]` RGB in \[0, 1\]
 * - Output: float32 `[1, H, W, 1]` or `[1, H, W]` nail probability in \[0, 1\]
 */
class NailSegmentationHelper(context: Context) {

    private val interpreter: Interpreter? = try {
        val buffer = loadModelFile(context.applicationContext, MODEL_ASSET_NAME)
        Interpreter(buffer, Interpreter.Options().apply { setNumThreads(4) })
    } catch (_: Throwable) {
        null
    }

    val isReady: Boolean get() = interpreter != null

    /**
     * Returns a mask bitmap (**ALPHA_8**, same size as [bitmap]) or null if no model / failure.
     */
    fun buildMaskForBitmap(bitmap: Bitmap): Bitmap? {
        val interp = interpreter ?: return null
        return try {
            val inTensor = interp.getInputTensor(0)
            val shape = inTensor.shape()
            val ih = shape.getOrElse(1) { 256 }
            val iw = shape.getOrElse(2) { 256 }
            val scaled = Bitmap.createScaledBitmap(bitmap, iw, ih, true)

            val inputBuffer = rgbBitmapToFloatBuffer(scaled)
            val outTensor = interp.getOutputTensor(0)
            val outShape = outTensor.shape()
            val outCount = outShape.fold(1, Int::times)
            val outputBuffer = ByteBuffer.allocateDirect(outCount * 4).apply {
                order(ByteOrder.nativeOrder())
            }

            interp.run(inputBuffer, outputBuffer)

            val lowMask = floatBufferToAlphaMask(outputBuffer, outShape, iw, ih)
            if (bitmap.width == lowMask.width && bitmap.height == lowMask.height) {
                lowMask
            } else {
                Bitmap.createScaledBitmap(lowMask, bitmap.width, bitmap.height, true)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun close() {
        interpreter?.close()
    }

    private fun rgbBitmapToFloatBuffer(bitmap: Bitmap): ByteBuffer {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val buffer = ByteBuffer.allocateDirect(1 * h * w * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            buffer.putFloat(((p shr 16) and 0xFF) / 255f)
            buffer.putFloat(((p shr 8) and 0xFF) / 255f)
            buffer.putFloat((p and 0xFF) / 255f)
            i++
        }
        buffer.rewind()
        return buffer
    }

    private fun floatBufferToAlphaMask(
        buffer: ByteBuffer,
        shape: IntArray,
        expectedW: Int,
        expectedH: Int,
    ): Bitmap {
        buffer.rewind()
        val oh = shape.getOrElse(1) { expectedH }
        val ow = shape.getOrElse(2) { expectedW }
        val oc = shape.getOrNull(3) ?: 1

        val mask = Bitmap.createBitmap(ow, oh, Bitmap.Config.ALPHA_8)
        val row = IntArray(ow)
        var y = 0
        while (y < oh) {
            var x = 0
            while (x < ow) {
                val idx = if (oc <= 1) {
                    y * ow + x
                } else {
                    (y * ow + x) * oc // take first channel as nail prob
                }
                buffer.position(idx * 4)
                val v = if (buffer.remaining() >= 4) buffer.float else 0f
                val a = (v.coerceIn(0f, 1f) * 255).toInt()
                row[x] = (a shl 24)
                x++
            }
            mask.setPixels(row, 0, ow, 0, y, ow, 1)
            y++
        }
        return mask
    }

    companion object {
        const val MODEL_ASSET_NAME = "nail_seg.tflite"

        private fun loadModelFile(context: Context, assetName: String): MappedByteBuffer {
            context.assets.openFd(assetName).use { afd ->
                java.io.FileInputStream(afd.fileDescriptor).channel.use { channel ->
                    return channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        afd.startOffset,
                        afd.declaredLength,
                    )
                }
            }
        }
    }
}
