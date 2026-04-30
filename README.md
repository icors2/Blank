# Nail Try-On (Android)

AR-assisted nail color preview for salons: live front-camera preview with fingertip overlays, optional photo from gallery, capture to gallery, and polish color plus simple design styles (French tip, glitter, matte).

## Product plan

1. **Core loop**: Detect hands (MediaPipe Hand Landmarker), map fingertip landmarks to nail-shaped overlays, tint with selected polish color.
2. **Live mode**: CameraX preview + analysis stream (RGBA frames) → overlay drawn on top with Compose `Canvas`.
3. **Still photos**: Pick from gallery or capture; run the same detector once and paint overlays on the bitmap view (fit-center math matches live preview).
4. **Polish UX**: Horizontal palette, design chips, opacity (“sheen”) slider.
5. **Next iterations** (not in this MVP): segmented nail masks, 3D nail meshes, catalog SKUs, booking integration, ML segmentation for cleaner edges.

## Tech stack

- Kotlin, Jetpack Compose, Material 3  
- CameraX (preview, image analysis, capture)  
- MediaPipe Tasks Vision (`hand_landmarker.task` bundled under `assets/`)  
- Min SDK 26, target/compile SDK 35  

## Build

Requires Android Studio Koala+ or a machine with **Android SDK 35** and JDK 17.

```bash
./gradlew :app:assembleDebug
```

Install the generated APK on a device with a front camera (`app/build/outputs/apk/debug/`).

### Hand model

The file `app/src/main/assets/hand_landmarker.task` is the official MediaPipe float16 hand landmarker. To refresh:

https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task

## Permissions

- `CAMERA` — live preview and capture  
- Photo picker uses the system UI on Android 13+ (no broad storage read when using “Pick photo”).

## Notes

- Front-camera preview is mirrored for the customer; landmark X coordinates are mirrored in the overlay so nails align with what they see.
- Overlays are stylized ellipses at fingertips—good for color concepts, not pixel-perfect manicure simulation.
