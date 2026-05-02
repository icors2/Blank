package com.salon.nailtryon

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max

/**
 * Bundled **[MODEL_ASSET_NAME]** (`assets/nail_seg.tflite`) for nail-region masking.
 *
 * Place your trained Colab export at **`app/src/main/assets/nail_seg.tflite`**.
 * Until the file exists, [isReady] is false and the app uses landmark masks only.
 *
 * Expected I/O (matches typical float32 Colab export):
 * - **Input:** float32 **NHWC** `[1, H, W, 3]` RGB in \[0, 1\] (batch may be omitted in metadata; buffer matches [numElements]).
 * - **Output:** float32 nail probability map `[1, H, W, 1]` or `[H, W]` / `[H, W, 1]` in \[0, 1\] (post-sigmoid).
 */
class NailSegmentationHelper(context: Context) {

    private val interpreter: Interpreter? = try {
        val buffer = loadModelFile(context.applicationContext, MODEL_ASSET_NAME)
        Interpreter(buffer, Interpreter.Options().apply { setNumThreads(4) })
    } catch (e: Throwable) {
        Log.w(TAG, "nail_seg.tflite not loaded (${e.message}). Landmark masks will be used.")
        null
    }

    val isReady: Boolean get() = interpreter != null

    /**
     * Returns a mask bitmap (**ALPHA_8**, same size as [bitmap]) or null on failure.
     */
    fun buildMaskForBitmap(bitmap: Bitmap): Bitmap? {
        val interp = interpreter ?: return null
        return try {
            val inTensor = interp.getInputTensor(0)
            val inShape = inTensor.shape()
            val (iw, ih) = resolveInputSpatialSize(inShape)

            val scaled = Bitmap.createScaledBitmap(bitmap, iw, ih, true)

            val inputBuffer = rgbBitmapToFloatBuffer(scaled)
            require(inputBuffer.capacity() == inTensor.numBytes()) {
                "Input buffer ${inputBuffer.capacity()} != tensor ${inTensor.numBytes()}"
            }

            val outTensor = interp.getOutputTensor(0)
            val outShape = outTensor.shape()
            val outputBuffer = ByteBuffer.allocateDirect(outTensor.numBytes()).apply {
                order(ByteOrder.nativeOrder())
            }

            inputBuffer.rewind()
            outputBuffer.rewind()
            interp.run(inputBuffer, outputBuffer)

            val (oh, ow) = resolveOutputSpatialSize(outShape, ih, iw)
            val oc = channelCount(outShape)

            val lowMask = floatBufferToAlphaMask(outputBuffer, oh, ow, oc)
            if (bitmap.width == lowMask.width && bitmap.height == lowMask.height) {
                lowMask
            } else {
                Bitmap.createScaledBitmap(lowMask, bitmap.width, bitmap.height, true).also {
                    if (it !== lowMask) lowMask.recycle()
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "buildMaskForBitmap failed: ${e.message}")
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
        val buffer = ByteBuffer.allocateDirect(h * w * 3 * 4).apply {
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
        oh: Int,
        ow: Int,
        channels: Int,
    ): Bitmap {
        buffer.rewind()
        val mask = Bitmap.createBitmap(ow, oh, Bitmap.Config.ALPHA_8)
        val row = IntArray(ow)
        var y = 0
        while (y < oh) {
            var x = 0
            while (x < ow) {
                val base = ((y * ow + x) * max(channels, 1))
                buffer.position(base * 4)
                val v = if (buffer.remaining() >= 4) {
                    buffer.float
                } else {
                    0f
                }
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
        private const val TAG = "NailSegmentation"
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

        /**
         * Input shapes: `[1,H,W,3]` or `[H,W,3]`; unknown dims fall back to 256.
         */
        private fun resolveInputSpatialSize(shape: IntArray): Pair<Int, Int> {
            val (h, w) = when (shape.size) {
                4 -> Pair(shape[1], shape[2])
                3 -> Pair(shape[0], shape[1])
                else -> Pair(256, 256)
            }
            return Pair(positiveOr(h, 256), positiveOr(w, 256))
        }

        /**
         * Output shapes: `[1,H,W,C]`, `[H,W,C]` with `C>=1`, or `[1,H,W]` (batch squeezed).
         */
        private fun resolveOutputSpatialSize(shape: IntArray, inputH: Int, inputW: Int): Pair<Int, Int> {
            return when (shape.size) {
                4 -> Pair(positiveOr(shape[1], inputH), positiveOr(shape[2], inputW))
                3 -> when {
                    shape[0] == 1 -> Pair(positiveOr(shape[1], inputH), positiveOr(shape[2], inputW))
                    shape[2] <= 8 -> Pair(positiveOr(shape[0], inputH), positiveOr(shape[1], inputW))
                    else -> Pair(positiveOr(shape[0], inputH), positiveOr(shape[1], inputW))
                }
                2 -> Pair(positiveOr(shape[0], inputH), positiveOr(shape[1], inputW))
                else -> Pair(inputH, inputW)
            }
        }

        private fun channelCount(shape: IntArray): Int {
            return when (shape.size) {
                4 -> max(1, shape[3])
                3 -> when {
                    shape[0] == 1 && shape[2] <= 32 -> 1
                    else -> max(1, shape[2])
                }
                else -> 1
            }
        }

        private fun positiveOr(v: Int, fallback: Int): Int =
            if (v > 0) v else fallback
    }
}
