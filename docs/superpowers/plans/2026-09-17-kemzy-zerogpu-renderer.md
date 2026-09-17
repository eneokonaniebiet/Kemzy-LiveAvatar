# Kémzy ZeroGPU Renderer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a provider-independent Hugging Face ZeroGPU adapter for real portrait animation without Runpod billing or neural weights in the Android APK.

**Architecture:** Render remains the public gateway. A Gradio ZeroGPU Space is the first GPU adapter; the renderer is isolated behind a provider-neutral contract so another GPU provider can replace it later. ZeroGPU currently supports free hosted Spaces within finite daily quotas.

**Tech Stack:** Python, Gradio, Hugging Face Spaces ZeroGPU, PyTorch, LivePortrait weights, PIL/NumPy, FastAPI.

**Spec:** `docs/superpowers/specs/2026-09-16-kemzy-liveavatar-design.md`

## Global Constraints
- No Runpod billing.
- No neural model files in the Android APK or Git repository.
- Do not use restricted InsightFace/inswapper weights for the commercial Kémzy path without licensing.
- ZeroGPU is Gradio-only and quota-limited; tolerate cold starts and queueing.
- Never return fake frames.
- Render remains provider-independent through `GPU_RENDERER_URL`.

### Task 1: ZeroGPU contract
**Files:** `backend/zerogpu_space/app.py`, `backend/zerogpu_space/requirements.txt`, `backend/zerogpu_space/README.md`, `backend/zerogpu_space/test_contract.py`
- [ ] Write failing tests for unavailable renderer state and motion validation.
- [ ] Verify the intended failures.
- [ ] Implement explicit provider-neutral contracts and errors.
- [ ] Run tests to green.
- [ ] Commit `feat: add ZeroGPU renderer contract`.

### Task 2: Real neural adapter
**Files:** `backend/zerogpu_space/liveportrait_adapter.py`, `backend/zerogpu_space/app.py`, `backend/zerogpu_space/requirements.txt`
- [ ] Write failing adapter tests.
- [ ] Implement real PyTorch portrait-animation stages using remote weights.
- [ ] Keep restricted InsightFace/inswapper weights out of the commercial path.
- [ ] Wrap GPU inference with `@spaces.GPU`.
- [ ] Run deterministic adapter tests.
- [ ] Commit `feat: add neural portrait renderer adapter`.

### Task 3: Render gateway integration
**Files:** `backend/app/main.py`, `backend/app/renderer_client.py`, `backend/app/test_renderer_client.py`
- [ ] Write failing health/error/timeout client tests.
- [ ] Implement typed renderer client.
- [ ] Update `/ready` to report actual renderer health.
- [ ] Run gateway tests.
- [ ] Commit `feat: integrate provider-neutral renderer client`.

### Task 4: Real GPU acceptance
**Files:** `backend/zerogpu_space/smoke_test.py`, `backend/zerogpu_space/README.md`
- [ ] Prepare one source portrait.
- [ ] Send two different motion states.
- [ ] Verify returned rendered frames differ.
- [ ] Run all local tests.
- [ ] Do not mark the renderer ready until a real external GPU request succeeds.
