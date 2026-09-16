# Kémzy LiveAvatar Xpression-Style Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Android app load the user's existing KemzyModels safely, run the live portrait pipeline from camera tracking, preserve the AI preview during recoverable errors, and ship corrected Kémzy àvátâr branding/icon.

**Architecture:** Keep the new `LiveAvatarEngine` contract-driven and separate from CameraX preview. Import shared-storage models into app-private storage before ONNX Runtime opens them; use bounded tracking/inference queues and retain the last valid AI frame. Keep large user models out of the APK.

**Tech Stack:** Kotlin, Android/CameraX, ONNX Runtime, LivePortrait ONNX models, Android Keystore, Gradle/GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-16-kemzy-liveavatar-design.md`

## Global Constraints

- App branding: `Kémzy àvátâr`.
- Secure passcode-first unlock; biometric unlock is not required for v1.
- Do not download, commit, or package the user's large ONNX models.
- Discover models at runtime from the shared `KemzyModels` location and compatible app-local locations.
- The old broken `LivePortraitEngine.kt` is not reused.
- Optimize for low-memory Android devices and avoid unbounded frame/model copies.
- The live AI preview remains visible after recoverable frame errors.

---

### Task 1: Model import/storage boundary

**Files:** inspect existing `app/src/main/java/...` model/runtime files; add focused model repository/importer and tests.

- [ ] Write a failing test proving a shared-storage model is copied into app-private `files/models` and that the returned path is private.
- [ ] Run the focused test and verify it fails because shared paths are currently passed directly to ORT.
- [ ] Implement a bounded streaming importer using Android file APIs; never use `readBytes()` for large ONNX files.
- [ ] Validate file existence/size before import, use temporary destination files, atomically rename after completion, and retain checksum/size metadata.
- [ ] Add tests for missing file, successful import, and replacement after source changes.
- [ ] Run unit tests and commit the storage boundary.

### Task 2: Model discovery and ONNX contracts

**Files:** model manifest/contract classes, `OnnxSessionFactory`, contract tests.

- [ ] Write failing tests for complete/missing model sets and exact input-rank validation, including true 5-D `feature_3d`.
- [ ] Verify RED.
- [ ] Implement discovery over imported private models plus Android-accessible `KemzyModels`, with import taking precedence for ORT execution.
- [ ] Implement explicit model/input contracts and diagnostics for missing files, shape/rank mismatch, and custom-op failures.
- [ ] Verify GREEN and run the complete model-contract test suite.

### Task 3: Live tracking/engine pipeline

**Files:** new `LiveAvatarEngine` implementation and focused state/tracking classes; tests.

- [ ] Write failing tests for `Idle → Preparing → Ready → Running`, bounded tracking state, and preserving the last successful AI frame after a frame-level failure.
- [ ] Verify RED.
- [ ] Implement the new engine behind an interface; do not restore or reuse the old `LivePortraitEngine.kt`.
- [ ] Connect source feature preparation, normalized tracking state, contract-safe tensors, LivePortrait stages, stitching, compositing, and latest-frame publication.
- [ ] Use bounded queues, adaptive frame skipping, explicit frame release, and memory-aware resolution limits.
- [ ] Verify GREEN and run instrumentation/unit coverage.

### Task 4: Camera/source/live-studio integration

**Files:** CameraX/live-studio/source UI and integration tests.

- [ ] Write failing integration tests for independent CameraX preview/analysis, source selection, and live preview persistence.
- [ ] Verify RED.
- [ ] Keep CameraX preview separate from analysis; route analysis to `FaceTracker` and tracker output to the engine.
- [ ] Ensure Select/Gallery/Upload/Camera/Video/Local Import all feed the same source pipeline.
- [ ] Ensure inference errors never replace the AI surface with the raw camera; expose non-blocking diagnostics.
- [ ] Verify GREEN and run device/instrumentation tests where available.

### Task 5: Branding and launcher icon

**Files:** Android manifest/resources, adaptive icon foreground/background, app theme/name resources.

- [ ] Add resource tests/build checks for application label `Kémzy àvátâr` and adaptive launcher icon presence.
- [ ] Implement an original Kémzy avatar-studio icon consistent with the app identity, without copying third-party assets.
- [ ] Verify the release manifest and merged resources contain the expected label/icon resources.

### Task 6: CI, artifact verification, and release gate

**Files:** GitHub Actions workflow and verification scripts.

- [ ] Add/adjust CI checks for unit tests, lint, Android build, APK size/model packaging guard, and SHA-256 publication.
- [ ] Verify CI fails when large `KemzyModels` files are packaged.
- [ ] Verify CI passes on the corrected implementation and records the APK checksum.
- [ ] Do not present a new APK for download until CI and checksum verification succeed.
