package com.salon.nailtryon

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Draws nail overlays on fingertips using MediaPipe landmark indices.
 *
 * Landmark indices follow MediaPipe Hands:
 * thumb tip 4, index 8, middle 12, ring 16, pinky 20.
 */
object NailOverlayPainter {

    private val tipIndices = intArrayOf(8, 12, 16, 20)
    private val dipIndices = intArrayOf(7, 11, 15, 19)
    private const val THUMB_TIP = 4
    private const val THUMB_IP = 3

    /**
     * Draws nails using [landmarksPx] in canvas pixel coordinates (normalized landmarks mapped to view).
     */
    fun drawNailsPixels(
        scope: DrawScope,
        landmarksPx: List<Pair<Float, Float>>,
        baseColor: Color,
        opacity: Float,
        design: NailDesign,
    ) {
        if (landmarksPx.size < 21) return

        fun pt(index: Int): Offset {
            val (x, y) = landmarksPx[index]
            return Offset(x, y)
        }

        fun drawFinger(tipIdx: Int, dipIdx: Int) {
            val tip = pt(tipIdx)
            val dip = pt(dipIdx)
            val dir = tip - dip
            val len = hypot(dir.x.toDouble(), dir.y.toDouble()).toFloat().coerceAtLeast(1f)
            val ux = dir.x / len
            val uy = dir.y / len
            val angleDeg = (atan2(uy.toDouble(), ux.toDouble()) * (180.0 / PI)).toFloat()

            val nailLen = len * 0.42f
            val nailW = len * 0.34f
            val center = Offset(
                tip.x - ux * nailLen * 0.35f,
                tip.y - uy * nailLen * 0.35f,
            )

            drawNailEllipse(
                scope = scope,
                center = center,
                width = nailW,
                height = nailLen,
                angleDeg = angleDeg,
                baseColor = baseColor,
                opacity = opacity,
                design = design,
            )
        }

        for (i in tipIndices.indices) {
            drawFinger(tipIndices[i], dipIndices[i])
        }

        run {
            val tip = pt(THUMB_TIP)
            val ip = pt(THUMB_IP)
            val dir = tip - ip
            val len = hypot(dir.x.toDouble(), dir.y.toDouble()).toFloat().coerceAtLeast(1f)
            val ux = dir.x / len
            val uy = dir.y / len
            val angleDeg = (atan2(uy.toDouble(), ux.toDouble()) * (180.0 / PI)).toFloat()
            val nailLen = len * 0.38f
            val nailW = len * 0.36f
            val center = Offset(
                tip.x - ux * nailLen * 0.28f,
                tip.y - uy * nailLen * 0.28f,
            )
            drawNailEllipse(
                scope = scope,
                center = center,
                width = nailW,
                height = nailLen,
                angleDeg = angleDeg,
                baseColor = baseColor,
                opacity = opacity,
                design = design,
            )
        }
    }

    private fun drawNailEllipse(
        scope: DrawScope,
        center: Offset,
        width: Float,
        height: Float,
        angleDeg: Float,
        baseColor: Color,
        opacity: Float,
        design: NailDesign,
    ) {
        val fill = baseColor.copy(alpha = opacity.coerceIn(0.05f, 1f))
        scope.rotate(angleDeg, center) {
            when (design) {
                NailDesign.SOLID -> {
                    drawOval(
                        color = fill,
                        topLeft = Offset(center.x - width / 2f, center.y - height / 2f),
                        size = Size(width, height),
                    )
                    drawOval(
                        color = Color.White.copy(alpha = 0.18f * opacity),
                        topLeft = Offset(center.x - width / 2f, center.y - height / 2f),
                        size = Size(width * 0.35f, height * 0.22f),
                        style = Stroke(width = 1.5f),
                    )
                }

                NailDesign.FRENCH -> {
                    drawOval(
                        color = Color(0xFFFFF5F8).copy(alpha = 0.92f * opacity),
                        topLeft = Offset(center.x - width / 2f, center.y - height / 2f),
                        size = Size(width, height),
                    )
                    val tipBand = height * 0.38f
                    drawOval(
                        color = Color.White.copy(alpha = 0.95f * opacity),
                        topLeft = Offset(center.x - width / 2f, center.y - height / 2f),
                        size = Size(width * 0.92f, tipBand),
                    )
                    drawOval(
                        color = fill.copy(alpha = 0.55f * opacity),
                        topLeft = Offset(center.x - width / 2f, center.y - height / 2f + tipBand * 0.55f),
                        size = Size(width * 0.95f, height - tipBand * 0.9f),
                    )
                }

                NailDesign.GLITTER -> {
                    drawOval(
                        color = fill,
                        topLeft = Offset(center.x - width / 2f, center.y - height / 2f),
                        size = Size(width, height),
                    )
                    val sparkleColor = Color.White.copy(alpha = 0.65f * opacity)
                    val cx = center.x
                    val cy = center.y
                    val seed = (cx * 17 + cy * 31).toLong()
                    var rng = seed xor 0x9E3779B97F4A7C15L
                    repeat(14) {
                        rng = rng * 6364136223846793005L + 1
                        val r1 = ((rng ushr 1).toDouble() / (1L shl 62).toDouble()) * 2 - 1
                        rng = rng * 6364136223846793005L + 1
                        val r2 = ((rng ushr 1).toDouble() / (1L shl 62).toDouble()) * 2 - 1
                        val rx = r1.toFloat() * width * 0.42f
                        val ry = r2.toFloat() * height * 0.42f
                        drawCircle(
                            color = sparkleColor,
                            radius = 1.4f + abs(rx + ry) % 4f,
                            center = Offset(cx + rx, cy + ry),
                        )
                    }
                }

                NailDesign.MATTE -> {
                    drawOval(
                        color = fill.copy(alpha = (opacity * 0.92f).coerceIn(0.05f, 1f)),
                        topLeft = Offset(center.x - width / 2f, center.y - height / 2f),
                        size = Size(width, height),
                    )
                    drawOval(
                        color = Color.Black.copy(alpha = 0.12f * opacity),
                        topLeft = Offset(center.x - width / 2f + width * 0.08f, center.y - height / 2f + height * 0.08f),
                        size = Size(width * 0.84f, height * 0.72f),
                    )
                }
            }

            drawArc(
                color = Color.Black.copy(alpha = 0.08f * opacity),
                startAngle = 190f,
                sweepAngle = 160f,
                useCenter = false,
                topLeft = Offset(center.x - width / 2f, center.y - height / 2f),
                size = Size(width, height),
                style = Stroke(width = 1.2f),
            )
        }
    }
}

enum class NailDesign {
    SOLID,
    FRENCH,
    GLITTER,
    MATTE,
}
