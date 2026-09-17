# LivePortrait GPU Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current lightweight motion approximation in Kémzy's GPU adapter with the actual upstream LivePortrait human inference pipeline while keeping Kémzy's API and Kinesis deployment architecture.

**Architecture:** The Kémzy backend remains the gateway. The Kinesis/Gradio GPU service owns the PyTorch LivePortrait models and persistent source features. The adapter will use the upstream `src` implementation for source preparation, feature extraction, motion/keypoint processing, stitching, warping, and decoding, while exposing the existing `/health`, `/prepare_source`, and `/render_motion` contract to the Kémzy gateway. Models stay on the GPU host and are never downloaded to the Android device.

**Tech Stack:** Python 3, PyTorch/CUDA, upstream KlingAIResearch/LivePortrait `src`, Gradio, FastAPI, Kinesis GPU runtime, pytest.

**Spec:** Kémzy GPU render gateway design approved in conversation; upstream reference: `KlingAIResearch/LivePortrait`.

## Global Constraints

- Preserve repository: `eneokonaniebiet/Kemzy-LiveAvatar`.
- Preserve branch: `feature/backend-render-gateway`.
- Preserve Kémzy branding and API contracts.
- Do not download LivePortrait model weights to the Android phone.
- Do not require an APK rebuild until backend verification succeeds.
- Do not use a placeholder renderer or a hand-written approximation in place of the actual LivePortrait pipeline.
- Support image sources first; keep the API extensible for video sources.
- Verify GPU/CUDA/model readiness before requesting a new Kinesis deployment.

---

### Task 1: Lock the upstream pipeline contract

**Files:**
- Reference only: upstream `src/live_portrait_wrapper.py`, `src/live_portrait_pipeline.py`, `src/config/inference_config.py`, model/config utilities.
- Test: `backend/zerogpu_space/test_contract.py`

**Interfaces:**
- Consumes: upstream LivePortrait wrapper behavior.
- Produces: a Kémzy adapter contract proving the real pipeline methods are available and the source/render tensor shapes are compatible.

- [ ] Write failing tests for wrapper availability, CUDA selection, source feature extraction, and output shape.
- [ ] Run the targeted tests and confirm they fail against the current adapter where appropriate.
- [ ] Implement only the contract helpers required by the tests.
- [ ] Run targeted tests again and confirm they pass.

### Task 2: Replace the approximation with the real LivePortrait execution path

**Files:**
- Modify: `backend/zerogpu_space/liveportrait_adapter.py`
- Modify: `backend/zerogpu_space/app.py` only where the adapter contract requires it.
- Test: `backend/zerogpu_space/test_contract.py`

**Interfaces:**
- `LivePortraitAdapter.load()` loads the real upstream wrapper and configured checkpoints.
- `prepare_source(image)` returns a persistent source handle containing the actual source feature volume and keypoint information.
- `render(handle, pose, expression)` performs real keypoint transformation/stitching, `warp_decode`, and output parsing.

- [ ] Add tests that reject missing model files and verify the adapter reports a useful readiness error.
- [ ] Run tests and confirm the new checks fail before implementation.
- [ ] Implement model-root/config handling without changing the Android API.
- [ ] Implement source preparation through the upstream wrapper.
- [ ] Implement frame rendering through upstream `stitching()` and `warp_decode()` rather than the current synthetic expression delta.
- [ ] Run adapter tests and confirm they pass.

### Task 3: Make model provisioning deterministic on Kinesis

**Files:**
- Modify: `backend/zerogpu_space/requirements.txt`
- Modify: `backend/zerogpu_space/README.md`
- Modify: deployment/runtime configuration as required after inspection.
- Test: `backend/zerogpu_space/test_contract.py`

**Interfaces:**
- Consumes: `LIVEPORTRAIT_REPO` and `LIVEPORTRAIT_WEIGHTS` environment variables.
- Produces: deterministic startup checks for the required LivePortrait checkpoints.

- [ ] Add failing tests for the complete required checkpoint set and model-root resolution.
- [ ] Run tests and confirm failure when checkpoints are absent.
- [ ] Implement explicit model-root validation and fail-fast diagnostics.
- [ ] Keep optional download/bootstrap behavior off the Android path and document GPU-host provisioning.
- [ ] Run tests and confirm pass.

### Task 4: Verify the Gradio GPU service contract end-to-end

**Files:**
- Modify: `backend/zerogpu_space/app.py` if needed.
- Test: `backend/zerogpu_space/test_contract.py`
- Existing gateway: `backend/app/renderer_client.py`, `backend/app/main.py`

**Interfaces:**
- `/health` reports GPU/model readiness.
- `/prepare_source` accepts a source image and returns a source handle.
- `/render_motion` accepts the source handle plus pose/expression/landmarks and returns a rendered frame.

- [ ] Add failing integration-contract tests for each endpoint.
- [ ] Implement only the missing endpoint wiring.
- [ ] Run the service contract tests locally/in-container.
- [ ] Verify the FastAPI gateway can call the Gradio service through `gradio_client`.

### Task 5: Kinesis GPU verification before deployment

**Files:**
- No Android changes.
- Deployment configuration only if verification identifies a real requirement.

- [ ] Verify NVIDIA GPU visibility with `nvidia-smi`.
- [ ] Verify `torch.cuda.is_available()` and the selected CUDA device.
- [ ] Verify every required checkpoint exists and loads.
- [ ] Verify `/health` becomes ready only after model load succeeds.
- [ ] Verify one source image produces a source handle.
- [ ] Verify one pose/expression frame produces a non-empty PNG with the expected dimensions.
- [ ] Record deployment logs and hashes before any large client download.

### Task 6: Client integration and real-time readiness

**Files:**
- Android client files only after backend verification.
- Gateway files only if frame protocol changes are required.

- [ ] Confirm the existing Android source-image flow can create a session and upload an image.
- [ ] Confirm the camera driver sends normalized pose/expression values matching the backend contract.
- [ ] Confirm returned rendered frames are displayed instead of falling back to the raw camera preview.
- [ ] Add graceful fallback/error states for backend unavailable, source invalid, and render timeout.
- [ ] Only then prepare a new Android build.

### Task 7: Final verification and documentation

**Files:**
- Modify: `backend/zerogpu_space/README.md`
- Modify: relevant Kémzy docs/specs if needed.

- [ ] Run all backend tests.
- [ ] Run the actual GPU smoke test.
- [ ] Verify no model weights are added to the Android artifact.
- [ ] Verify the deployed image is the intended commit.
- [ ] Record exact commit, deployment status, model readiness, and smoke-test result.
- [ ] Do not claim the system is complete until the GPU smoke test and client frame path both pass.
