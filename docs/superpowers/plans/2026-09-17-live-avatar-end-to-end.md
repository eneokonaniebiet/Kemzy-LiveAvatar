# Kémzy Live Avatar End-to-End Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Kémzy Android client drive the official LivePortrait neural renderer through a persistent backend stream using real camera motion, with image/video sources and reliable readiness/error handling, before Kinesis deployment.

**Architecture:** Android performs lightweight camera face tracking and sends compact motion packets over one persistent WebSocket. The API gateway owns session/source state and forwards each packet to the GPU LivePortrait renderer; the renderer keeps the official LivePortrait models resident in GPU memory and returns rendered frames. The implementation reuses official LivePortrait source preparation, keypoint transformation, stitching, and retargeting primitives instead of inventing a second animation engine.

**Tech Stack:** Kotlin/Android CameraX + ML Kit; FastAPI/WebSocket; Python; official KlingAIResearch LivePortrait PyTorch pipeline; Gradio GPU renderer; Docker/NVIDIA CUDA; GitHub Actions.

**Spec:** User-approved Kémzy àvátâr personal-use live-avatar requirements and the existing `feature/backend-render-gateway` implementation.

## Global Constraints

- Personal/private use for now; commercial-release licensing changes are deferred until a future release decision.
- Keep neural models off the Android phone; models stay on the GPU renderer.
- Preserve Kémzy àvátâr branding and the existing Android application identity.
- Use the official LivePortrait implementation and its native retargeting/stitching path where available. citeturn0search0
- Do not claim LivePortrait's 21 keypoints are 50,000 points; any denser tracker must be implemented and verified separately.
- Do not move to Kinesis or ask the user to download an APK until CI/build and renderer contracts are verified.

---

### Task 1: Establish failing gateway-stream tests

**Files:**
- Create: `backend/tests/test_stream_contract.py`
- Create: `backend/tests/test_motion_contract.py`

**Interfaces:**
- `POST /v1/sessions` returns `session_id`.
- `POST /v1/sessions/{session_id}/source` prepares a source.
- `WS /v1/stream/{session_id}` accepts JSON motion packets and returns JSON rendered-frame packets.

- [ ] **Step 1: Write the failing tests**

Test that a WebSocket stream rejects unknown sessions, accepts a valid packet, and returns the same timestamp with an `image_base64` field when the renderer is stubbed. Test packet validation for exactly 3 pose values and exactly 63 expression values.

- [ ] **Step 2: Run the backend tests**

Run `pytest -q backend/tests/test_stream_contract.py backend/tests/test_motion_contract.py` and verify the stream tests fail because `/v1/stream/{session_id}` does not yet exist.

- [ ] **Step 3: Keep the tests as the contract**

Do not weaken the expected WebSocket behavior to match the current implementation.

---

### Task 2: Implement persistent API WebSocket streaming

**Files:**
- Modify: `backend/app/main.py`
- Modify: `backend/app/renderer_client.py`
- Create: `backend/app/motion.py`

**Interfaces:**
- `parse_motion_packet(payload: dict) -> MotionFrame`
- `RendererClient.render_frame(...) -> dict`
- `WS /v1/stream/{session_id}` -> receives motion packets and returns rendered-frame packets.

- [ ] **Step 1: Add strict motion parsing**

Require `session_id`, non-negative `timestamp_ms`, 3 pose values, and 63 expression values. Landmarks are optional but, when present, must be triplets.

- [ ] **Step 2: Add the WebSocket endpoint**

Use a persistent WebSocket connection, reject an unknown session with a structured error, reject packets that do not match the contract, and keep the connection alive until the client disconnects.

- [ ] **Step 3: Add bounded backpressure**

Keep only the newest pending motion packet per session. If a new packet arrives while a render is running, replace the pending packet rather than building an unbounded queue.

- [ ] **Step 4: Return rendered frames**

Return `{status, session_id, timestamp_ms, mime_type, image_base64}` and never return a stale frame timestamp for a newer motion packet.

- [ ] **Step 5: Run the stream tests**

Run the two Task 1 test files and verify they pass.

---

### Task 3: Replace approximate LivePortrait motion math with official retargeting primitives

**Files:**
- Modify: `backend/zerogpu_space/liveportrait_adapter.py`
- Create: `backend/tests/test_liveportrait_motion.py`

**Interfaces:**
- `LivePortraitAdapter.render(handle, pose, expression, landmarks, eye_ratios=None, lip_ratio=None, eyebrow_ratios=None) -> PIL.Image`

- [ ] **Step 1: Write failing unit tests**

Test that neutral motion produces a valid 21x3 expression tensor, pose is converted to the expected shape, eye/lip controls are bounded, and malformed driver packets are rejected before GPU inference.

- [ ] **Step 2: Inspect and use upstream retargeting functions**

Use the pinned official LivePortrait source tree's retargeting/stitching methods rather than replacing them with custom 21-keypoint arithmetic. The official project explicitly provides stitching and retargeting controls. citeturn0search0turn0search9

- [ ] **Step 3: Wire the official transformation path**

Preserve source canonical features and invoke the wrapper's official keypoint transformation/stitching/warp-decode sequence. Apply eye/lip retargeting only through the upstream helpers when the corresponding driver ratios are supplied.

- [ ] **Step 4: Run the motion tests**

Run `pytest -q backend/tests/test_liveportrait_motion.py`.

---

### Task 4: Improve Android driver packet generation

**Files:**
- Modify: `android/app/src/main/java/com/kemzy/liveavatar/MotionPacketMapper.kt`
- Modify: `android/app/src/main/java/com/kemzy/liveavatar/MainActivity.kt`
- Modify: `android/app/src/test/java/com/kemzy/liveavatar/MotionPacketMapperTest.kt`

**Interfaces:**
- Motion packet contains pose, expression, and normalized facial driver ratios/landmarks without pretending ML Kit provides unsupported signals.

- [ ] **Step 1: Write failing mapper tests**

Cover neutral face, smile, eye-open/closed, mouth movement, head pose, clamping, and stable packet length.

- [ ] **Step 2: Configure ML Kit for the needed facial signals**

Use face landmarks/classification/contours available from the configured detector. Keep front-camera mirroring and image rotation correct.

- [ ] **Step 3: Map real observations into the packet**

Populate the driver values from measured camera observations. Do not manufacture 63 independent expression values from three numbers; use a deterministic neutral base plus measured controls and let the server's LivePortrait retargeting path interpret them.

- [ ] **Step 4: Add adaptive send pacing**

Start around 15 fps and allow the client to skip packets when the previous render is still pending, avoiding network/render queue growth.

- [ ] **Step 5: Run Android unit tests**

Run `./gradlew testDebugUnitTest` in the Android project and verify mapper tests pass.

---

### Task 5: Add source video support without putting neural models on the phone

**Files:**
- Modify: `backend/app/main.py`
- Modify: `backend/app/renderer_client.py`
- Modify: `backend/zerogpu_space/app.py`
- Modify: Android source-selection UI and API client files.
- Create: `backend/tests/test_source_contract.py`

**Interfaces:**
- Source upload accepts image and video MIME types within an explicit size limit.
- Image source prepares a persistent LivePortrait source handle.
- Video source is retained for video-to-video workflows and does not silently pretend to be an image source.

- [ ] **Step 1: Write failing source tests**

Test accepted image/video types, rejection of unsupported MIME types, empty files, and size limits.

- [ ] **Step 2: Implement source validation and storage**

Use temporary files with deterministic cleanup and pass video sources to an explicit video preparation/render path.

- [ ] **Step 3: Keep live-avatar semantics separate from video editing**

A live camera session uses an image source plus live motion packets; video-to-video uses the official LivePortrait video workflow. The official repository documents both image-driven animation and source-video editing. citeturn0search0

- [ ] **Step 4: Run source tests**

Run `pytest -q backend/tests/test_source_contract.py`.

---

### Task 6: Make renderer readiness and model provisioning deterministic

**Files:**
- Modify: `backend/zerogpu_space/app.py`
- Modify: `backend/zerogpu_space/liveportrait_adapter.py`
- Modify: `backend/renderer/Dockerfile`
- Modify: `backend/renderer/requirements.txt`
- Modify: `deploy/compose.gpu.yml`
- Create: `backend/tests/test_renderer_readiness.py`

**Interfaces:**
- `/health` reports `ready` only when the LivePortrait wrapper is loaded on CUDA.
- Missing model files produce a deterministic degraded state and actionable error.
- Model download remains opt-in via `KEMZY_ALLOW_MODEL_DOWNLOAD=1`.

- [ ] **Step 1: Write failing readiness tests**

Verify missing weights cannot report `ready`, CPU-only execution cannot report `ready`, and successful model initialization reports the exact backend identifier.

- [ ] **Step 2: Align dependencies with the official LivePortrait runtime**

Add only dependencies actually imported by the pinned official tree and renderer. The official repository documents its Python/FFmpeg runtime and pretrained-weight requirements. citeturn0search0turn0search9

- [ ] **Step 3: Make model provisioning explicit**

Keep automatic model download disabled by default; the GPU deployment must mount persistent model storage and fail readiness until all required checkpoints exist.

- [ ] **Step 4: Run readiness tests**

Run `pytest -q backend/tests/test_renderer_readiness.py`.

---

### Task 7: Android manifest/UI/network reliability pass

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `android/app/src/main/java/com/kemzy/liveavatar/KemzyApi.kt`
- Modify: `android/app/src/main/java/com/kemzy/liveavatar/MainActivity.kt`
- Modify: `android/app/src/main/res/layout/activity_main.xml`

- [ ] **Step 1: Verify permissions and network security**

Require camera and network permissions and use the HTTPS/WSS production base URL configuration.

- [ ] **Step 2: Fix WebSocket frame decoding**

Decode the returned Base64 payload exactly once and validate MIME/type before displaying the bitmap.

- [ ] **Step 3: Add reconnect/error states**

Handle source preparation failure, renderer-not-ready, socket disconnect, no-face detection, and stale frame timeouts without crashing the camera preview.

- [ ] **Step 4: Preserve the Kémzy UI**

Keep source selection and live output visible while the real rendered frame replaces the placeholder/avatar image only after a successful GPU response.

---

### Task 8: CI verification and final pre-Kinesis gate

**Files:**
- Modify: `.github/workflows/android-live.yml`
- Create: `backend/tests/test_contracts.py` if needed for a single backend test entry point.

- [ ] **Step 1: Run backend tests**

Run `pytest -q backend/tests`.

- [ ] **Step 2: Run Android tests/build**

Run `./gradlew testDebugUnitTest assembleDebug --no-daemon`.

- [ ] **Step 3: Inspect GitHub Actions**

Require the Android workflow to finish successfully before requesting any APK download.

- [ ] **Step 4: Verify artifact metadata**

Record the APK size and SHA-256 from the verified CI artifact. Do not ask the user to download an unverified build.

- [ ] **Step 5: Final renderer contract check**

Confirm `/health`, `/ready`, `/v1/sessions`, source preparation, and `/v1/stream/{session_id}` contracts are internally consistent. Only after these checks pass should Kinesis deployment be configured.

---

## Self-Review

Coverage check: persistent streaming, official LivePortrait rendering, real camera motion, source image/video, GPU readiness, Android networking, CI, and pre-Kinesis verification each have a dedicated task. No task treats an unimplemented placeholder as success. The 50,000-point requirement is not falsely attributed to LivePortrait's native 21-keypoint representation; a dense tracker can be added later only when its actual implementation is verified.
