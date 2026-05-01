package com.salon.nailtryon

import android.graphics.Bitmap
import kotlin.math.max

internal fun Bitmap.scaleToMaxSide(maxSide: Int): Bitmap {
    val maxDim = max(width, height)
    if (maxDim <= maxSide) return this
    val scale = maxSide.toFloat() / maxDim
    val nw = max(1, (width * scale).toInt())
    val nh = max(1, (height * scale).toInt())
    return Bitmap.createScaledBitmap(this, nw, nh, true)
}
