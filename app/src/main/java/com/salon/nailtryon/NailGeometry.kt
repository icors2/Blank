package com.salon.nailtryon

/**
 * Holds the mathematical dimensions extracted from the TFLite mask.
 */
data class NailGeometry(
    val centerX: Float,      // The middle of the nail bed
    val centerY: Float,
    val width: Float,        // Distance from lateral fold to lateral fold
    val length: Float,       // Distance from cuticle to free edge
    val angleDegrees: Float, // The tilt of the finger in the photo
    val cuticleX: Float = 0f,
    val cuticleY: Float = 0f
)
