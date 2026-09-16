# Kémzy Complete Product Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn Kémzy àvátâr into a complete, testable Android-first live-avatar product with original onboarding, source management, real-time avatar generation, product pages, settings/help/legal surfaces, and a verified path toward streaming integrations and future iOS/web products.

**Architecture:** Keep the existing live inference core and repair its Android model-storage/output contracts instead of reusing the old broken `LivePortraitEngine.kt`. Add a small navigation/state layer around focused Compose screens, persistent app-private model staging, and explicit source/media abstractions for image/video. Treat external-camera/WhatsApp output as a separate integration boundary that is only marked supported after an actual Android-compatible implementation and device test.

**Tech Stack:** Kotlin 17, Jetpack Compose, CameraX, Android Storage Access Framework/DocumentFile, ML Kit face detection, ONNX Runtime Android, coroutines, existing native custom-op library, Android tests/unit tests, GitHub Actions.

**Spec:** Approved Kémzy product direction from the 2026-09-16 design discussion: original Xpression-inspired functionality without copying proprietary UI, text, assets, or implementation.

## Global Constraints

- Product name: **Kémzy àvátâr**.
- Passcode-first unlock uses `115522`; no biometric requirement.
- Existing `KemzyModels` folder is reused; never require needless model redownloads.
- ONNX models must be staged into app-private storage before inference; never pass `/storage/emulated/0/...` directly to ONNX Runtime.
- Do not reuse the old broken `LivePortraitEngine.kt` implementation.
- Image and video sources must be real source types with validation and useful failure states, not placeholder buttons.
- UI copy, visuals, icons, and layouts are original Kémzy work; Xpression is functional inspiration only.
- No claim of WhatsApp/Mochi Helper support until a supported output mechanism exists and is verified on a real device.
- Android-first; keep boundaries portable for later iOS/web implementations.
- Do not download or ask the user to download an APK/model until CI produces a verified artifact and checksum.

---

### Task 1: Baseline and test harness

**Files:**
- Inspect/modify existing `app/src/test/...` model and engine tests.
- Inspect `app/build.gradle.kts` and `.github/workflows/android.yml`.

**Interfaces:**
- Establish current branch/head, test commands, artifact naming, and existing model contracts before feature changes.

- [ ] **Step 1: Add/extend failing tests for app navigation state, source type validation, and private model path requirements.**
- [ ] **Step 2: Run the focused unit tests and record the expected failures.
- [ ] **Step 3: Commit only the test/harness baseline changes.**

---

### Task 2: Fix model import and ONNX output contracts

**Files:**
- `app/src/main/java/com/kemzy/liveavatar/models/ModelImportPlan.kt`
- Existing model import/discovery classes
- `app/src/main/java/com/kemzy/liveavatar/engine/LivePortraitPipelineCore.kt`
- `app/src/main/java/com/kemzy/liveavatar/inference/Feature3dTensor.kt`
- `app/src/main/java/com/kemzy/liveavatar/inference/TensorContract.kt`

**Interfaces:**
- `ModelImportPlan` produces only required staged files.
- Pipeline consumes private filesystem paths and accepts ONNX Runtime tensor outputs without unsafe casts, preserving rank/shape.

- [ ] **Step 1: Add regression tests for SAF-imported model paths and nested ONNX array outputs.**
- [ ] **Step 2: Run the tests and reproduce the current failures (`EACCES` and nested-array cast where applicable).**
- [ ] **Step 3: Implement copy-to-private-storage and safe output normalization.**
- [ ] **Step 4: Validate feature-3D rank exactly before creating the next tensor.**
- [ ] **Step 5: Run focused unit tests.**
- [ ] **Step 6: Commit the model/runtime contract fix.**

---

### Task 3: Build the Kémzy onboarding and passcode gate

**Files:**
- `MainActivity.kt` or split Compose screen files under `ui/`
- New onboarding/passcode state model and tests
- Existing manifest/theme resources as needed

**Interfaces:**
- Launch state: `Onboarding -> Passcode -> Studio`.
- Passcode is local and persistent only as required for unlock state; no biometric dependency.

- [ ] **Step 1: Add failing tests for first launch, onboarding completion, correct passcode, and incorrect passcode.**
- [ ] **Step 2: Implement original Kémzy onboarding pages explaining live avatar, image/video sources, facial/head-motion animation, and streaming use cases.**
- [ ] **Step 3: Add passcode screen with `115522` and clear lockout/error feedback without exposing the code in normal UI.**
- [ ] **Step 4: Run Compose/unit tests and Android compile checks.**
- [ ] **Step 5: Commit the onboarding/passcode feature.**

---

### Task 4: Create the product navigation shell and required pages

**Files:**
- New focused Compose screens/navigation/state files under `app/src/main/java/com/kemzy/liveavatar/ui/`
- Tests for route/state transitions

**Interfaces:**
- Routes: Studio, Sources, Creations, Pricing, Help Center, Settings, Privacy, Terms, About Kémzy.
- All routes render real content and navigation; no dead buttons.

- [ ] **Step 1: Add failing navigation tests covering every required route.**
- [ ] **Step 2: Implement the navigation shell with original Kémzy visual language and responsive layouts.**
- [ ] **Step 3: Implement Pricing, Help Center, Privacy, Terms, and About content as local app content so the screens work offline.**
- [ ] **Step 4: Implement Settings categories for security, camera/mic permissions, output, performance, models/storage, notifications, and app information.**
- [ ] **Step 5: Run tests and compile.**
- [ ] **Step 6: Commit the product shell/pages.**

---

### Task 5: Real source/media workflow

**Files:**
- New source repository/state classes
- Existing camera classes
- New image/video picker and private-media staging classes
- Tests

**Interfaces:**
- `SourceType = IMAGE | VIDEO`.
- Source workflow supports gallery, camera capture, local file selection, and video selection.
- Selected media is copied into app-private storage before inference.

- [ ] **Step 1: Add failing tests for image/video MIME validation, missing-face errors, inaccessible files, cancellation, and persistence.**
- [ ] **Step 2: Implement SAF pickers and private staging with progress/error states.**
- [ ] **Step 3: Implement image face validation and source preview.**
- [ ] **Step 4: Implement video metadata/frame validation and source preview; reject unsupported/corrupt media clearly.**
- [ ] **Step 5: Run tests and compile.**
- [ ] **Step 6: Commit source/media workflow.**

---

### Task 6: Real-time avatar studio integration

**Files:**
- Existing `LiveAvatarEngine.kt`, `Pipeline.kt`, `WarpRender.kt`, `MotionControls.kt`
- Existing `CameraController.kt`, `FaceTracker.kt`, `DriverMotion.kt`, `FrameGate.kt`
- New studio state/UI files
- Regression tests

**Interfaces:**
- Camera frames feed tracking; source face is prepared once; live driver motion updates avatar frames.
- Studio states: idle, preparing, ready, running, paused, error.
- Controls expose expression/head-motion parameters without pretending unsupported features exist.

- [ ] **Step 1: Add regression tests for lifecycle/state transitions and frame-gating behavior.**
- [ ] **Step 2: Connect source preparation to the existing pipeline without restoring the old engine implementation.**
- [ ] **Step 3: Ensure camera lifecycle, rotation, pause/resume, and release are safe.**
- [ ] **Step 4: Surface actionable errors for model, tensor, camera, memory, and face-detection failures.**
- [ ] **Step 5: Run tests and compile.**
- [ ] **Step 6: Commit the studio integration.**

---

### Task 7: Performance, memory, and persistence hardening

**Files:**
- Engine/model storage classes
- Settings/preferences state
- Camera/frame-gating code
- Tests

**Interfaces:**
- Avoid full-model byte-array duplication.
- Persist imported model/media locations only through safe app-private copies.
- Provide quality/performance controls appropriate to the device.

- [ ] **Step 1: Add tests for model staging idempotency and cleanup behavior.**
- [ ] **Step 2: Implement bounded frame processing and lifecycle cleanup.**
- [ ] **Step 3: Add storage accounting and clear-cache controls.**
- [ ] **Step 4: Run tests and inspect release build memory/package behavior.**
- [ ] **Step 5: Commit hardening changes.**

---

### Task 8: Original Kémzy visual identity and app icon

**Files:**
- Android launcher icon resources
- Splash/theme resources
- Compose theme/components
- Visual regression/compile checks

**Interfaces:**
- One consistent original Kémzy identity across onboarding, studio, settings, and launcher.

- [ ] **Step 1: Define the Kémzy icon geometry and theme tokens without copying another product's assets.**
- [ ] **Step 2: Replace generic/default launcher and splash presentation.**
- [ ] **Step 3: Apply consistent typography, spacing, surfaces, controls, and empty/error states.**
- [ ] **Step 4: Compile and verify resources.**
- [ ] **Step 5: Commit branding changes.**

---

### Task 9: Streaming/output boundary and WhatsApp feasibility

**Files:**
- New output abstraction under `output/`
- Android integration code only where supported
- Tests/documentation

**Interfaces:**
- `AvatarOutput` separates internal preview from external consumers.
- Supported outputs are explicitly enumerated; unsupported external-camera scenarios remain disabled rather than simulated.

- [ ] **Step 1: Add tests ensuring preview output cannot be mislabeled as a system camera.**
- [ ] **Step 2: Implement the safest Android output abstraction available to the app.**
- [ ] **Step 3: Investigate and document the actual mechanism required for WhatsApp to consume Kémzy output; do not claim universal virtual-camera support from CameraX alone.**
- [ ] **Step 4: Add Mochi Helper only if a real callable integration contract is identified; otherwise expose no fake integration.**
- [ ] **Step 5: Commit output boundary and support-status documentation.**

---

### Task 10: Store-readiness and release verification

**Files:**
- `.github/workflows/android.yml`
- Gradle/release configuration
- Privacy/terms/about resources
- Release checklist under `docs/`

**Interfaces:**
- CI produces a reproducible release APK artifact named `Kémzyavatarstudio.apk` inside `kemzyavatar-apk` packaging where the workflow supports it.

- [ ] **Step 1: Add CI checks for unit tests, lint/compile, release packaging, and artifact checksum generation.**
- [ ] **Step 2: Add release configuration and required app metadata without inventing store claims.**
- [ ] **Step 3: Verify onboarding, passcode, model import, image source, video source, live engine, settings, help/legal pages on a real Android device.**
- [ ] **Step 4: Verify no new model download is required for the user's existing `KemzyModels` inventory.**
- [ ] **Step 5: Only after CI and checks pass, publish the artifact reference and SHA-256 for user download/install.**
- [ ] **Step 6: Commit release-readiness changes.**

---

## Coverage Review

- Onboarding/passcode: Task 3.
- Studio/live avatar: Task 6.
- Image/video source: Task 5.
- Existing local models/private staging: Task 2.
- Product pages: Task 4.
- Settings: Task 4 and Task 7.
- Original icon/branding: Task 8.
- Streaming/WhatsApp/Mochi boundary: Task 9.
- Android/Play readiness and verified artifacts: Task 10.
- Future iOS/web portability: enforced through output/source/engine boundaries rather than Android-only business logic.

No task intentionally claims unsupported universal virtual-camera behavior; that capability is gated on an actual implementation and device verification.