# Kémzy LiveAvatar — Product and Architecture Design

## Goal
Build an Android-first Kotlin application that provides an Xpression-Camera-style live avatar experience: a user selects a face source, the app tracks the user's live facial/head motion independently from CameraX preview, and a LivePortrait-based pipeline animates the selected source face in real time while keeping the AI preview visible through transient inference errors.

## Source of truth
The implementation follows the requirements approved in conversation and the established Kémzy àvátâr project constraints. The old broken `LivePortraitEngine.kt` is not reused.

## Core constraints
- App branding: `Kémzy àvátâr`.
- Secure passcode-first unlock; biometric unlock is not required for v1.
- Android-first Kotlin implementation.
- Do not download, commit, or package the user's large ONNX models.
- Discover models at runtime from the shared `KemzyModels` location and compatible app-local locations.
- Preserve the existing #286-era branding/icon/artifact expectations where applicable.
- No billing implementation in v1.
- Design account, billing, and virtual-camera interfaces for future implementation without enabling billing in v1.
- Optimize for low-memory Android devices and avoid unbounded frame/model copies.

## Architecture

### 1. UI/application state
Use a single explicit application state model and focused screens/components:
- Lock/passcode screen.
- Home/source selection.
- Source editor with face-area adjustment.
- Avatar library with recent/favorites.
- Live studio.
- Expression controls.
- Capture/record/export.
- Saved creations.
- Settings.
- Diagnostics.

The live studio owns display state but not model implementation details.

### 2. Source subsystem
`SourceRepository` manages source images/video and metadata. Sources can arrive through Select, Upload, Gallery, Camera, Video, and Local Import. `SourceEditor` stores a normalized crop/face-area transform without modifying the original source. Face-area adjustment is persisted as source metadata.

A `SourceFeatureCache` stores reusable extracted source features keyed by source content fingerprint plus model/version contract. Cache entries are bounded and invalidated when the relevant model contract changes.

### 3. Camera and tracking
CameraX preview is a separate use case from analysis. Tracking runs on a bounded frame queue and never owns the UI preview surface.

`FaceTracker` exposes normalized head pose and expression signals. Tracking failures do not clear the last valid AI frame. Processing uses adaptive frame skipping, bounded image buffers, explicit close/release of camera frames, and memory-aware resolution limits.

### 4. Model discovery and ONNX contracts
`ModelDiscovery` searches known runtime locations, including the shared `KemzyModels` directory exposed to the application through Android storage permissions/access APIs. It never fetches model files from the network.

`ModelManifest`/`ModelContract` describes required model files, input names, ranks, element types, dimensions, and optional custom-op requirements.

`OnnxSessionFactory` validates model availability and custom-op prerequisites before inference. Tensor creation is contract-driven. A required `feature_3d` input is represented as a true 5-D tensor; the implementation must never flatten it to make a mismatched model accept the input.

### 5. Live avatar engine
Create a new engine implementation behind an interface such as `LiveAvatarEngine` rather than reusing the old `LivePortraitEngine.kt`.

The engine stages are explicit and independently testable:
1. Validate source and model contracts.
2. Load/cache source features.
3. Consume current tracking state.
4. Build contract-safe motion/expression tensors.
5. Execute LivePortrait stages.
6. Apply stitching/relative motion.
7. Composite background/effects.
8. Publish the latest valid AI frame.

Engine state is explicit: `Idle`, `Preparing`, `Ready`, `Running`, `Degraded`, `Error`, and `Stopped`. A frame-level error transitions the engine to a recoverable/degraded state instead of replacing the AI preview with the ordinary camera feed.

### 6. Xpression-Camera-style controls
The live studio provides:
- Eyes, blink, wink.
- Mouth/lips, smile, frown and related mouth controls.
- Brows.
- Yaw, pitch, roll.
- X/Y/Z positioning.
- Zoom.
- Intensity.
- Smoothing.
- Stitching/relative-motion controls.
- Background selection.
- Effects.
- Voice-driven mode.

Controls are represented as bounded parameters and transformed into engine-neutral motion state so the UI is not coupled to ONNX tensor layout.

### 7. Capture and output
Photo capture and video recording consume the same AI preview stream used by the live studio. Recording uses bounded encoded buffers and does not require keeping every raw frame in memory.

Export/share operates on completed media only. Saved creations persist metadata and file references rather than duplicating large source/model data unnecessarily.

### 8. Security
Passcode data is stored using Android Keystore-backed protection. Unlock state is session-scoped and reset after the configured lock condition. Sensitive values are never logged.

### 9. Future interfaces
Define interfaces for account, billing, and virtual-camera output. Their v1 implementations are no-op/disabled and do not expose payment flows.

## Error handling
- Missing models: diagnostics must identify exact missing model names and search locations.
- Contract mismatch: diagnostics must report model/input name, received rank/shape, and expected rank/shape.
- Custom-op failure: distinguish missing library from symbol/registration failure.
- Tracking failure: preserve the last valid tracking state for a bounded grace period and keep the AI preview visible.
- Frame inference failure: preserve the last successful AI frame and show a non-blocking diagnostic state.
- Low memory: reduce analysis resolution/frame rate, release transient buffers, and stop/restart inference safely if necessary.
- Fatal engine initialization failure: keep the application usable and expose diagnostics rather than crashing.

## Testing
Unit/instrumentation coverage must include:
- ONNX tensor contracts, including true 5-D `feature_3d` tensors.
- Model discovery and missing/complete model sets.
- Source import/normalization/editor metadata.
- Passcode behavior and protected storage.
- Tracking state transitions and bounded grace behavior.
- Engine state transitions and error recovery.
- Source-feature cache key/invalidation behavior.
- Memory/frame queue bounds.
- Future interface no-op behavior.

Where device-dependent ONNX execution is unavailable in CI, contract tests use deterministic fake sessions and fixtures; the production code path remains contract-driven.

## CI and artifact policy
GitHub Actions must run lint/unit tests and an Android build. Release artifact generation must not package `KemzyModels` or large ONNX model files. The workflow must calculate and publish the APK SHA-256 alongside the artifact. Verification must occur before the APK is presented to the user for download.

## Acceptance criteria
The application builds from a clean checkout, all automated tests pass, the APK contains no large user ONNX models, model discovery works against the runtime model directory, required tensors satisfy declared ranks, the live AI preview remains visible after recoverable frame errors, and CI produces a verified APK plus SHA-256 checksum.
