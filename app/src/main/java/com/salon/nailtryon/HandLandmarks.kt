package com.salon.nailtryon

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/**
 * Returns normalized (x,y) landmarks for the highest-confidence detected hand, or null.
 */
fun extractPrimaryHandLandmarks(result: HandLandmarkerResult): List<Pair<Float, Float>>? {
    if (result.landmarks().isEmpty()) return null
    val landmarks: List<NormalizedLandmark> = result.landmarks()[0]
    if (landmarks.size < 21) return null
    return landmarks.map { lm -> Pair(lm.x(), lm.y()) }
}
