# Nail Try-On (Android)

Offline-first salon nail preview: **pick or capture a hand photo**, detect hands with **MediaPipe Hand Landmarker**, build a **nail mask** from either **soft landmark ovals** or an optional bundled **TensorFlow Lite** segmenter, then **recolor and blend** on the bitmap. No cloud required.

## Flow

1. User selects gallery image or takes a photo (`CAMERA` only for capture).
2. Hand landmarks are detected ([RunningMode.IMAGE](https://ai.google.dev/edge/mediapipe/solutions/vision/hand_landmarker/android)).
3. Mask:
   - Default: blurred ellipses at fingertips ([LandmarkNailMask](app/src/main/java/com/salon/nailtryon/NailBlend.kt)).
   - Optional: place `nail_seg.tflite` in `app/src/main/assets/` and enable **Use nail mask model** (adjust [NailSegmentationHelper](app/src/main/java/com/salon/nailtryon/NailSegmentationHelper.kt) I/O to match your export).
   - **Isolation**: When using the TFLite model, users can toggle **Select Single Nail** to isolate and color a specific nail via a screen tap (powered by a Flood Fill algorithm in `NailSelector.kt`).
4. [blendNailPolish](app/src/main/java/com/salon/nailtryon/NailBlend.kt) tints masked pixels; light glitter/matte passes optional.

## Tech stack

- Kotlin, Jetpack Compose, Material 3  
- MediaPipe Tasks Vision (`hand_landmarker.task` in `assets/`)  
- TensorFlow Lite (optional `nail_seg.tflite`)  
- Min SDK 26, compile/target SDK 35  

## Build

Requires **Android SDK 35** (set `ANDROID_HOME` or `sdk.dir` in `local.properties`) and JDK 17.

```bash
./gradlew :app:assembleDebug
```

### Hand model

Bundled file: `app/src/main/assets/hand_landmarker.task`  
Refresh from: https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task  

Training outline (TensorFlow / Colab): see **[docs/nail_segmentation_colab_outline.ipynb](docs/nail_segmentation_colab_outline.ipynb)** — includes optional download for the Kaggle dataset [muhammadhammad261/nail-segmentation-dataset](https://www.kaggle.com/datasets/muhammadhammad261/nail-segmentation-dataset).

### Nail segmentation model (`nail_seg.tflite`)

Commit trained **`app/src/main/assets/nail_seg.tflite`** from Colab. On launch, [NailSegmentationHelper](app/src/main/java/com/salon/nailtryon/NailSegmentationHelper.kt) loads it; the UI shows **Use nail mask model** when `isReady`. Float32 RGB **[0,1]** input and sigmoid nail probability output (see helper KDoc). Without the file, landmark masks are used only.

## Permissions

- **Gallery**: system photo picker on Android 13+ (`READ_MEDIA_IMAGES` declared).  
- **Camera**: only when using “Take photo.”

## Notes

- **French** design chip currently maps to the same polish tint as solid (no separate free-edge band in bitmap mode yet); extend `applyDesignToBitmap` if needed.
- Photorealism is limited without a trained nail segmenter and careful blending—this MVP targets **fast local color concepts**.
