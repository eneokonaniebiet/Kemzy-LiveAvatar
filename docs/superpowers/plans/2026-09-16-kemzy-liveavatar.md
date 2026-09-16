# Kémzy LiveAvatar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an Android-first Kotlin Kémzy LiveAvatar app that provides an Xpression-Camera-style real-time animated face from a selected source face, with robust LivePortrait inference, expression/head controls, source management, capture/export, diagnostics, and low-memory safeguards.

**Architecture:** Jetpack Compose drives a screen/state architecture while CameraX owns the camera lifecycle and an independent face tracker supplies smoothed driver motion. A new contract-first LivePortrait engine owns model discovery and ONNX sessions; `feature_3d` is represented and validated as a true 5-D tensor and is never flattened to satisfy an input. AI frames are processed through a bounded single-in-flight pipeline and the last successful AI frame remains visible when inference fails.

**Tech Stack:** Kotlin, Android Gradle Plugin, Jetpack Compose/Material 3, CameraX, ML Kit Face Detection, Android MediaStore/MediaCodec, ONNX Runtime Android, Android Keystore/PBKDF2, Kotlin coroutines/StateFlow, JUnit/Android instrumentation tests, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-16-kemzy-liveavatar-design.md`

## Global Constraints

- Branding is `Kémzy àvátâr`.
- Unlock is passcode-first; no biometric unlock in v1.
- Source inputs include Select/Upload/Gallery/Camera/Video/Local Import, with source editing and face-area adjustment.
- CameraX preview must remain an independent preview use case from AI processing.
- Existing models are discovered at runtime from shared `KemzyModels`; large ONNX/model files must not be committed or packaged.
- `feature_3d` must remain rank 5: `[batch, channels, depth, height, width]`; never flatten or guess rank at runtime.
- Model/session loading must use bounded memory and direct file paths rather than `readBytes()` for large models.
- AI preview must remain visible on inference errors by retaining the last successful AI frame.
- Implement Xpression-Camera-style real-time source-face animation from expressions/head movement, with eyes/blink/wink, mouth/lips/smile/frown, brows, yaw/pitch/roll, X/Y/Z, zoom, intensity and smoothing.
- Include stitching/relative-motion controls, backgrounds/effects, voice-driven mode, photo capture, video recording, export/share, saved creations, settings, and diagnostics.
- Include future-ready account, billing, and virtual-camera interfaces without billing implementation in v1.
- CI must run tests, build an APK, compute SHA-256, and publish a `kemzyavatar-apk` artifact containing the APK and checksum; CI must not download or commit user model files.

---

### Task 1: Android project foundation and repository hygiene

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `app/build.gradle.kts`
- Create: `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/kemzy/liveavatar/KemzyApplication.kt`
- Create: `.gitignore`
- Create: `README.md`

**Interfaces:**
- Produces the compilable Android application module and dependency/version baseline consumed by all later tasks.

- [ ] **Step 1: Add repository hygiene rules**
  - Ignore `*.onnx`, `*.bin`, `KemzyModels/`, build outputs, local properties, IDE files, and generated signing material.
  - Add a README section explicitly stating that user ONNX files are discovered at runtime and are never committed.

- [ ] **Step 2: Add Android Gradle configuration**
  - Configure a Kotlin Android application with Compose, a modern Android SDK target, Java/Kotlin 17, and an application id owned by Kémzy LiveAvatar.
  - Keep model dependencies external to source control.

- [ ] **Step 3: Add the application class**
  - Install a coroutine/application scope and expose app-level diagnostics/model repository dependencies without loading models during process startup.

- [ ] **Step 4: Verify the project configuration**
  - Run Gradle configuration and unit-test discovery in CI-compatible mode.
  - Expected: project config succeeds and no model files are present in Git.

- [ ] **Step 5: Commit**
  - Commit as `build: scaffold Kémzy LiveAvatar Android app`.

---

### Task 2: Secure passcode-first unlock and persistent app state

**Files:**
- Create: `app/src/main/java/com/kemzy/liveavatar/security/PasscodeStore.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/security/PasscodeHasher.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/security/UnlockState.kt`
- Create: `app/src/test/java/com/kemzy/liveavatar/security/PasscodeStoreTest.kt`
- Create: `app/src/test/java/com/kemzy/liveavatar/security/PasscodeHasherTest.kt`

**Interfaces:**
- `PasscodeStore.isConfigured(): Boolean`
- `PasscodeStore.setPasscode(passcode: CharArray)`
- `PasscodeStore.verify(passcode: CharArray): Boolean`
- `PasscodeStore.clear()`

- [ ] **Step 1: Write failing passcode tests**
  - Cover first-time setup, correct verification, wrong verification, salted storage, and clearing state.
  - Assert no plaintext passcode is persisted.

- [ ] **Step 2: Implement salted PBKDF2 verification**
  - Generate a cryptographically random salt.
  - Store only salt, iteration metadata, and derived digest in private app storage.
  - Wipe temporary character/byte arrays where practical.

- [ ] **Step 3: Implement unlock state**
  - Model `Locked`, `NeedsSetup`, and `Unlocked` states.
  - Do not expose biometric authentication APIs.

- [ ] **Step 4: Run security tests**
  - Expected: all pass.

- [ ] **Step 5: Commit**
  - Commit as `feat: add secure passcode-first unlock`.

---

### Task 3: Source management, face-area editor, cache, and model discovery

**Files:**
- Create: `app/src/main/java/com/kemzy/liveavatar/source/SourceModels.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/source/SourceRepository.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/source/SourceEditorState.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/source/SourceFeatureCache.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/models/ModelManifest.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/models/ModelDiscovery.kt`
- Create: `app/src/test/java/com/kemzy/liveavatar/source/SourceRepositoryTest.kt`
- Create: `app/src/test/java/com/kemzy/liveavatar/models/ModelDiscoveryTest.kt`

**Interfaces:**
- `SourceRepository.importUri(uri: Uri): SourceAsset`
- `SourceRepository.importImage(bytes/source): SourceAsset`
- `SourceRepository.updateFaceCrop(sourceId: String, crop: FaceCrop): SourceAsset`
- `SourceFeatureCache.get(sourceId: String, modelVersion: String): CachedFeatures?`
- `SourceFeatureCache.put(sourceId: String, modelVersion: String, features: FloatArray)`
- `ModelDiscovery.discover(): ModelManifest`

- [ ] **Step 1: Write source tests**
  - Cover image/gallery/SAF URI handling, invalid source rejection, face crop bounds, recent/favorite metadata, and persistence.

- [ ] **Step 2: Write model discovery tests**
  - Cover the shared `KemzyModels` root, `liveportrait_onnx` children, missing models, wrong extension, and optional files.
  - Assert discovery only inspects metadata/path and never loads whole model files into a byte array.

- [ ] **Step 3: Implement source repository**
  - Support Gallery, Camera, Video, Local Import, and SAF URIs.
  - Store only app-owned copies/metadata required for source persistence.
  - Preserve original source aspect ratio and face crop metadata.

- [ ] **Step 4: Implement feature cache**
  - Key by source identity plus model/version contract.
  - Store compact typed feature arrays rather than source images in memory.

- [ ] **Step 5: Implement runtime model discovery**
  - Check the accessible shared-storage `KemzyModels` directory and persisted SAF tree access.
  - Recognize the LivePortrait model set and ArcFace/inswapper files without packaging them.
  - Produce diagnostics for every missing/invalid model.

- [ ] **Step 6: Run tests**
  - Expected: source and model-discovery tests pass.

- [ ] **Step 7: Commit**
  - Commit as `feat: add source handling and runtime model discovery`.

---

### Task 4: CameraX preview, independent face tracking, and bounded frame pipeline

**Files:**
- Create: `app/src/main/java/com/kemzy/liveavatar/camera/CameraController.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/camera/FaceTracker.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/camera/DriverMotion.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/camera/FrameGate.kt`
- Create: `app/src/test/java/com/kemzy/liveavatar/camera/FrameGateTest.kt`
- Create: `app/src/test/java/com/kemzy/liveavatar/camera/DriverMotionTest.kt`

**Interfaces:**
- `FaceTracker.process(image: ImageProxy): DriverMotion?`
- `FrameGate.offer(frame: DriverFrame): Boolean`
- `FrameGate.poll(): DriverFrame?`
- `CameraController.startPreview()` / `stopPreview()` / `bindAnalysis()`

- [ ] **Step 1: Write frame-gate tests**
  - Verify one in-flight AI frame, replacement/drop behavior for stale frames, and bounded queue size.

- [ ] **Step 2: Write driver-motion tests**
  - Verify yaw/pitch/roll smoothing, blink/wink thresholds, mouth openness, brow motion, and neutral fallback.

- [ ] **Step 3: Implement CameraX preview**
  - Bind `Preview` independently from `ImageAnalysis` so AI failures never turn the screen into the raw camera fallback.
  - Close `ImageProxy` promptly after tracker/analysis extraction.

- [ ] **Step 4: Implement ML Kit face tracking**
  - Use the front camera, tracking id when available, and normalized landmarks/head angles.
  - Smooth motion and produce explicit driver controls.

- [ ] **Step 5: Implement bounded frame gate**
  - Keep at most one frame being inferred and one newest queued frame.
  - Drop stale frames rather than accumulating bitmaps.

- [ ] **Step 6: Run camera tests**
  - Expected: all deterministic tracking/gating tests pass.

- [ ] **Step 7: Commit**
  - Commit as `feat: add independent camera preview and face tracking`.

---

### Task 5: Contract-safe ONNX runtime and true 5-D feature_3d handling

**Files:**
- Create: `app/src/main/java/com/kemzy/liveavatar/inference/TensorContract.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/inference/OrtSessionFactory.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/inference/CustomOpsLoader.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/inference/Feature3dTensor.kt`
- Create: `app/src/test/java/com/kemzy/liveavatar/inference/TensorContractTest.kt`
- Create: `app/src/test/java/com/kemzy/liveavatar/inference/Feature3dTensorTest.kt`

**Interfaces:**
- `TensorContract.requireRank(name: String, shape: LongArray, expectedRank: Int)`
- `Feature3dTensor.fromFloats(data: FloatArray, shape: LongArray): OnnxTensorValue`
- `OrtSessionFactory.create(modelPath: Path, options: SessionOptions): OrtSession`
- `CustomOpsLoader.ensureLoaded(context: Context): CustomOpsStatus`

- [ ] **Step 1: Write failing tensor tests**
  - Reject a rank-1 `feature_3d` input.
  - Accept only rank 5 with `[batch, channels, depth, height, width]` semantics.
  - Verify element count exactly equals the product of dimensions.
  - Assert no flattening helper exists in the feature-3D path.

- [ ] **Step 2: Implement typed feature tensor**
  - Preserve five dimensions through construction and ORT input creation.
  - Validate dimensions before inference.
  - Fail with a diagnostic contract error rather than mutating shape.

- [ ] **Step 3: Implement direct-path ORT session loading**
  - Pass model filesystem paths to ONNX Runtime instead of `readBytes()`.
  - Configure bounded CPU threads and session options for low-memory devices.

- [ ] **Step 4: Implement custom-op loading contract**
  - Load the packaged native custom-op library once and verify `RegisterCustomOps` is available before constructing the SPADE session.
  - Surface a deterministic diagnostics error when the library is absent or incompatible.

- [ ] **Step 5: Run tensor tests**
  - Expected: rank and shape tests pass.

- [ ] **Step 6: Commit**
  - Commit as `feat: enforce contract-safe ONNX tensor handling`.

---

### Task 6: New LivePortrait engine with Xpression-style animation pipeline

**Files:**
- Create: `app/src/main/java/com/kemzy/liveavatar/engine/LiveAvatarEngine.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/engine/EngineState.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/engine/LivePortraitPipeline.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/engine/MotionControls.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/engine/EngineDiagnostics.kt`
- Create: `app/src/test/java/com/kemzy/liveavatar/engine/EngineStateTest.kt`
- Create: `app/src/test/java/com/kemzy/liveavatar/engine/MotionControlsTest.kt`

**Interfaces:**
- `LiveAvatarEngine.prepare(source: SourceAsset): EngineResult`
- `LiveAvatarEngine.start(): EngineResult`
- `LiveAvatarEngine.submit(driver: DriverMotion, controls: MotionControls): Boolean`
- `LiveAvatarEngine.stop()`
- `LiveAvatarEngine.latestFrame(): AiFrame?`
- `LivePortraitPipeline.prepare(manifest: ModelManifest)`
- `LivePortraitPipeline.render(sourceFeatures: SourceFeatures, driver: DriverMotion, controls: MotionControls): AiFrame`

- [ ] **Step 1: Write engine-state tests**
  - Cover `Locked -> Ready -> SourceReady -> Preparing -> Running` and degraded/error transitions.
  - Verify an inference failure preserves `latestFrame()` rather than replacing it with a raw camera frame.

- [ ] **Step 2: Write motion-control tests**
  - Cover eyes/blink/wink, mouth/lips/smile/frown, brows, yaw/pitch/roll, X/Y/Z, zoom, intensity, smoothing, relative motion, and stitching controls.

- [ ] **Step 3: Implement engine state machine**
  - Keep preparation/inference off the main thread.
  - Expose diagnostic error codes and preserve the latest successful AI output.

- [ ] **Step 4: Implement LivePortrait source preparation**
  - Run the source face through the required appearance and motion extraction stages once.
  - Cache source features.

- [ ] **Step 5: Implement driver-to-LivePortrait motion conversion**
  - Map face-tracker motion and user controls into the model's explicit motion representation.
  - Preserve the tensor rank contracts at every stage.

- [ ] **Step 6: Implement warping/stitching/compositing**
  - Use the discovered LivePortrait ONNX files and the custom-op path for `warping_spade-fix.onnx`.
  - Apply eye/lip stitching and relative-motion controls where the discovered model set supports them.
  - Composite the generated face back into the camera frame without blocking the preview.

- [ ] **Step 7: Add backgrounds and effects as compositing stages**
  - Keep effects after avatar generation so they cannot alter the model input contract.

- [ ] **Step 8: Run engine tests**
  - Expected: state and motion tests pass without loading the large user models.

- [ ] **Step 9: Commit**
  - Commit as `feat: implement contract-safe LivePortrait avatar engine`.

---

### Task 7: Studio UI, avatar library, recent/favorites, controls, settings, and diagnostics

**Files:**
- Create: `app/src/main/java/com/kemzy/liveavatar/ui/KemzyApp.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/ui/navigation/AppRoutes.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/ui/lock/LockScreen.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/ui/studio/LiveStudioScreen.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/ui/studio/ExpressionControls.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/ui/source/SourcePickerScreen.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/ui/source/SourceEditorScreen.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/ui/library/AvatarLibraryScreen.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/ui/creations/CreationsScreen.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/ui/settings/SettingsScreen.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/ui/diagnostics/DiagnosticsScreen.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/ui/theme/Theme.kt`
- Create/update: launcher icon resources under `app/src/main/res/mipmap-*` and `drawable/`

**Interfaces:**
- UI observes `UnlockState`, `EngineState`, `DriverMotion`, `MotionControls`, `ModelManifest`, and `EngineDiagnostics`.

- [ ] **Step 1: Implement lock/setup screen**
  - Show Kémzy branding and passcode setup/unlock.
  - Do not expose biometric buttons or settings.

- [ ] **Step 2: Implement source picker/editor**
  - Provide Gallery/Camera/Video/Local Import and face-area adjustment.

- [ ] **Step 3: Implement live studio**
  - Keep AI output as the main preview surface.
  - Display a compact diagnostic/status strip without replacing the AI preview on transient inference errors.

- [ ] **Step 4: Implement expression controls**
  - Provide eyes/blink/wink, mouth/lips/smile/frown, brows, yaw/pitch/roll, X/Y/Z, zoom, intensity/smoothing, stitching/relative motion, backgrounds, and effects.

- [ ] **Step 5: Implement avatar library and saved creations**
  - Recent/favorite source/avatar metadata and saved creation cards.

- [ ] **Step 6: Implement settings and diagnostics**
  - Model discovery details, runtime capabilities, frame rate, memory safeguards, engine errors, and storage paths.

- [ ] **Step 7: Add launcher branding**
  - Preserve the established custom Kémzy launcher identity from the earlier app lineage while using the new engine implementation.

- [ ] **Step 8: Build the UI**
  - Expected: Compose compilation succeeds.

- [ ] **Step 9: Commit**
  - Commit as `feat: add Kémzy studio UI and controls`.

---

### Task 8: Voice-driven mode, photo/video capture, export/share, and future interfaces

**Files:**
- Create: `app/src/main/java/com/kemzy/liveavatar/voice/VoiceCommandController.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/capture/AiPhotoCapture.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/capture/AiVideoRecorder.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/capture/MediaExporter.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/future/AccountGateway.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/future/BillingGateway.kt`
- Create: `app/src/main/java/com/kemzy/liveavatar/future/VirtualCameraGateway.kt`
- Create: `app/src/test/java/com/kemzy/liveavatar/voice/VoiceCommandControllerTest.kt`
- Create: `app/src/test/java/com/kemzy/liveavatar/capture/MediaExporterTest.kt`

**Interfaces:**
- `VoiceCommandController.start()` / `stop()` / `commands: Flow<VoiceCommand>`
- `AiPhotoCapture.capture(frame: AiFrame): Uri`
- `AiVideoRecorder.start()` / `append(frame: AiFrame)` / `stop(): Uri`
- `MediaExporter.share(uri: Uri)`
- `AccountGateway`, `BillingGateway`, and `VirtualCameraGateway` are v1 interfaces only.

- [ ] **Step 1: Write voice tests**
  - Map recognized phrases to deterministic control changes and ignore unknown phrases.

- [ ] **Step 2: Implement voice-driven mode**
  - Use Android speech recognition where available and convert recognized commands into `MotionControls` updates.
  - Keep voice mode optional and non-blocking.

- [ ] **Step 3: Implement AI photo capture**
  - Persist the current AI frame through MediaStore without retaining large bitmap histories.

- [ ] **Step 4: Implement AI video recording**
  - Use a bounded MediaCodec encoder pipeline fed from AI frames, dropping frames rather than buffering unbounded data.
  - Ensure recording stops cleanly on lifecycle loss.

- [ ] **Step 5: Implement export/share**
  - Publish media through MediaStore and Android share intents.

- [ ] **Step 6: Add future interfaces**
  - Define account/billing/virtual-camera contracts without payment or account-network implementation in v1.

- [ ] **Step 7: Run capture/voice tests**
  - Expected: deterministic tests pass.

- [ ] **Step 8: Commit**
  - Commit as `feat: add voice mode capture export and future interfaces`.

---

### Task 9: Native custom-op library and packaging validation

**Files:**
- Create: `app/src/main/cpp/CMakeLists.txt`
- Create: `app/src/main/cpp/kemzy_ort_customops.cpp`
- Create: `app/src/main/cpp/kemzy_ort_customops.h`
- Modify: `app/build.gradle.kts`
- Create: `app/src/test/java/com/kemzy/liveavatar/inference/CustomOpsContractTest.kt`

**Interfaces:**
- Native export: `RegisterCustomOps(OrtSessionOptions*, const OrtApiBase*)`.
- Android library name: `libkemzy_ort_customops.so`.

- [ ] **Step 1: Write packaging contract test**
  - Assert the Android application package contains the arm64 native library and the expected exported registration symbol is linked.

- [ ] **Step 2: Implement native custom-op registration**
  - Register the GridSample3D implementation required by the discovered warping SPADE model using ONNX Runtime's custom-op ABI.
  - Keep the implementation CPU-safe and deterministic.

- [ ] **Step 3: Wire CMake/Gradle packaging**
  - Build arm64-v8a native output and package it in the APK.
  - Do not add any ONNX model file to resources/assets.

- [ ] **Step 4: Run native/package tests**
  - Expected: native library builds and packaging contract passes.

- [ ] **Step 5: Commit**
  - Commit as `fix: package GridSample3D custom ops for LivePortrait`.

---

### Task 10: CI, full test suite, APK build, SHA-256 verification, and artifact publication

**Files:**
- Create: `.github/workflows/android.yml`
- Create: `scripts/verify-no-models.sh`
- Create: `scripts/package-apk.sh`
- Create: `app/src/androidTest/java/com/kemzy/liveavatar/AndroidSmokeTest.kt`

**Interfaces:**
- CI accepts only repository source and builds without fetching user ONNX/model files.
- Artifact name is `kemzyavatar-apk`.

- [ ] **Step 1: Add no-model verification script**
  - Fail if tracked or workspace model files include `.onnx`, `.bin`, or user model directories.
  - Allow no model exceptions in v1.

- [ ] **Step 2: Add CI workflow**
  - Check out source, set up Java/Gradle, run no-model verification, run unit tests, run Android lint/compile checks, build the debug APK, calculate SHA-256, and package `Kémzyavatarstudio.apk` plus `Kémzyavatarstudio.apk.sha256`.
  - Upload the package under artifact name `kemzyavatar-apk`.

- [ ] **Step 3: Add Android smoke test**
  - Verify the application starts and the lock screen is the initial state.

- [ ] **Step 4: Trigger CI from a commit**
  - Do not request a user download yet.

- [ ] **Step 5: Inspect CI result**
  - Require all tests/build/package checks to pass.
  - Record the exact APK SHA-256 and artifact id.

- [ ] **Step 6: If CI fails, fix the specific failure and rerun**
  - Do not declare success from a partial build.

- [ ] **Step 7: Verify artifact contents and checksum**
  - Confirm the artifact contains the APK and checksum file and that the recorded SHA-256 matches the built APK.

- [ ] **Step 8: Commit**
  - Commit as `ci: build verify and publish Kémzy APK`.

---

### Task 11: Final verification and release handoff

**Files:**
- Modify: `README.md`
- Create: `docs/release/2026-09-16-kemzy-apk-verification.md`

- [ ] **Step 1: Record final verification**
  - Include commit, workflow run, artifact name/id, APK size, SHA-256, test result, and model-packaging check.

- [ ] **Step 2: Verify no large model files entered Git**
  - Inspect repository tree and CI no-model result.

- [ ] **Step 3: Verify the APK checksum from CI output**
  - Use the exact checksum produced by the successful run, not a locally guessed value.

- [ ] **Step 4: Commit verification record**
  - Commit as `docs: record verified Kémzy APK build`.

- [ ] **Step 5: Only after all checks pass, provide the user the verified artifact/download route**
  - State exact artifact name, checksum, size, commit, and workflow result.
  - Do not ask the user to download models or an unverified APK.
