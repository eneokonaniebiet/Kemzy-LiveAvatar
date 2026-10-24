# Kémzy on-device portrait prototype

This branch changes the Studio's **Go Live** path to local processing. It does not open a renderer session or upload camera frames.

## Intended first-device test

1. Install the CI-produced `Kémzyavatarstudio.apk`.
2. Open Studio and grant camera permission.
3. Tap **Source** and choose a clear, front-facing portrait (one face, eyes visible, good light).
4. Wait for **Portrait ready · on-device model loaded · no cloud inference**.
5. Tap **Go Live**, face the front camera, then move slowly and blink/smile.
6. Tap **Stop Live** to return to the selected portrait.

## Implementation

- MediaPipe Face Landmarker detects 478 facial landmarks locally.
- A 32 × 32 bitmap mesh deforms the selected portrait based on differences between the camera's neutral face and current landmarks.
- The source portrait is downscaled to a maximum dimension of 512 px; camera frames to 320 px; frame work is throttled to about 10 FPS to limit CPU and RAM use.
- The model bundle is fetched by Gradle from Google's official MediaPipe model storage during the build, then packaged in the APK. The installed app does not need a network connection for this tracking/warp path.

## Known limitations

This is a **lightweight geometric animation proof of concept**, not LivePortrait, not a diffusion/generative renderer, and not a photorealistic face-swap model. It can move visible face regions, but it cannot reconstruct hidden facial parts when the head turns far sideways. Expect possible warping/stretching and lower frame rates on a 3 GB physical-RAM phone. The 1 GB extended-RAM setting is storage-backed and is not equivalent to another 1 GB of physical RAM.

The phone's screenshot confirms a Mali-G57 GPU and Vulkan 1.3, but this prototype uses MediaPipe's default CPU delegate for compatibility. Vulkan presence alone does not mean the full LivePortrait neural renderer will run efficiently on the device.

The CI build must pass before installing the APK. Device-level performance still needs measurement on the Infinix Smart 10.
