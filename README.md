# Nail Try-On (Android)

Offline-first salon nail preview: **pick or capture a hand photo**, detect hands with **MediaPipe Hand Landmarker**, build a **nail mask** from either **soft landmark ovals** or an optional bundled **TensorFlow Lite** segmenter, then **recolor and blend** on the bitmap. No cloud required.

## Flow

1. User selects gallery image or takes a photo (`CAMERA` only for capture).
2. Hand landmarks are detected ([RunningMode.IMAGE](https://ai.google.dev/edge/mediapipe/solutions/vision/hand_landmarker/android)).
3. Mask:
   - Default: blurred ellipses at fingertips ([LandmarkNailMask](app/src/main/java/com/salon/nailtryon/NailBlend.kt)).
   - **Advanced 3D Shading**: Extensions use a **cylindrical lighting model** (Shadow -> Highlight -> Shadow) and **alpha translucency** to simulate the depth and luster of real acrylics.
   - **Cuticle Feathering**: Seamless "tuck" under the skin fold using soft-edge masking for a professional finish.
   - **French, Matte, & Glitter**: Advanced blending shader handles professional designs including French tips (with procedural mask generation), Matte finishes, and Glitter effects.
   - **Save to Gallery**: Export your designs directly to the device's photo gallery.
   - **Vector Shapes**: Users can switch from **Natural** (segmentation/landmark based) to **Coffin** shape. This uses an advanced **Surveyor + Detailer** approach: MediaPipe landmarks provide the finger's rotation angle (Surveyor), while the TFLite mask provides precise nail width and length (Detailer).
4. **Color Selection & Brand Library**:
   - **Real-World Polish**: Choose from curated datasets of real brand colors.
   - **Color Extraction**: Use the eye-dropper tool to tap anywhere on the image and extract a custom color.
   - **Custom Library**: Save extracted colors to a personal brand group.
5. [blendNailPolish](app/src/main/java/com/salon/nailtryon/NailBlend.kt) tints masked pixels; light glitter/matte passes optional.

## Tech stack

- Kotlin, Jetpack Compose, Material 3  
- MediaPipe Tasks Vision (`hand_landmarker.task` in `assets/`)  
- TensorFlow Lite (optional `nail_seg_fp16.tflite`)
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

### Nail segmentation model (`nail_seg_fp16.tflite`)

Commit trained **`app/src/main/assets/nail_seg_fp16.tflite`** from Colab. On launch, [NailSegmentationHelper](app/src/main/java/com/salon/nailtryon/NailSegmentationHelper.kt) loads it; the UI shows **Use nail mask model** when `isReady`. Float32 RGB **[0,1]** input and sigmoid nail probability output (see helper KDoc). Without the file, landmark masks are used only.

## Permissions

- **Gallery**: system photo picker on Android 13+ (`READ_MEDIA_IMAGES` declared).  
- **Camera**: only when using “Take photo.”

## Notes

- **Photorealism**: This MVP targets fast local color concepts using high-performance blending shaders and on-device AI.
