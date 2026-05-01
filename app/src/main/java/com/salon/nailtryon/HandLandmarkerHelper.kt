package com.salon.nailtryon

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/**
 * MediaPipe Hand Landmarker for single photos ([RunningMode.IMAGE]).
 */
class HandLandmarkerHelper(context: Context) {

    private val landmarker: HandLandmarker = HandLandmarker.createFromOptions(
        context,
        HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET_PATH)
                    .build(),
            )
            .setRunningMode(RunningMode.IMAGE)
            .setNumHands(MAX_HANDS)
            .setMinHandDetectionConfidence(0.45f)
            .setMinHandPresenceConfidence(0.45f)
            .build(),
    )

    fun detect(bitmap: Bitmap): HandLandmarkerResult {
        val mpImage = BitmapImageBuilder(bitmap).build()
        return landmarker.detect(mpImage)
    }

    fun close() {
        landmarker.close()
    }

    companion object {
        private const val MODEL_ASSET_PATH = "hand_landmarker.task"
        private const val MAX_HANDS = 2
    }
}
