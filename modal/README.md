# Kémzy FasterLivePortrait — Modal GPU worker

This folder deploys the official **FasterLivePortrait** GPU renderer used by the Kémzy gateway.

## Architecture

Android → Cloudflare Worker → Render gateway → **Modal T4 GPU** → FasterLivePortrait1 → Render → Cloudflare → Android.

The Android app never needs to know the Modal URL.

## 1. Get the repo in Termux

```bash
cd ~
git clone -b feature/backend-render-gateway https://github.com/eneokonanieb/Kemzy-LiveAvatar.git
cd ~/Kemzy-LiveAvatar
```

If you already have it:

```cd ~/Kemzy-LiveAvatar
git pull origin feature/backend-render-gateway
```

## 2. Connect Termux to your Modal workspace

Install Modal:

```bash
python -m pip install -U modal
```

Then authenticate:

```bash
modal setup
```

Modal uses the active workspace/environment for `modal deploy`. If your workspace has multiple environments, select the intended one before deployment. citeturn6search6turn6search4

## 3. Create the worker secret

The Modal worker must receive the **same secret value that Render currently uses as `GPU_WORKER_SECRET`**.

Create the Modal secret:

```bash
modal secret create kemzy-worker-secret KEMZY_WORKER_SECRET='PASTE_THE_SAME_VALUE_USED_BY_RENDER'
```

Do not commit the secret to GitHub. Modal Secrets are designed for injecting environment variables into deployed containers. citeturn6search0turn6search1

## 4. Deploy

From the repository root:

```bash
modal deploy modal/kemzy_fasterlive_worker.py --stream-logs
```

Modal's official CLI deploys a persistent App with `modal deploy`. citeturn6search4

The first deployment will build the GPU image and populate the persistent Modal Volume with the FasterLivePortrait checkpoints. The model files are cached in the Volume so they are not downloaded again for every container. Modal Volumes are persistent storage for deployed functions. citeturn6search8

## 5. Get the Modal worker URL

The deployed App exposes:

```
https://<modal-worker-url>/gpu-bridge
```

The Render gateway needs the WebSocket form:

```
wss://<modal-worker-url>/gpu-bridge
```

The Modal app dashboard and logs can be inspected with:

```bash
modal app list
modal app logs kemzy-fasterliveportrait --tail 100
modal app dashboard kemzy-fasterliveportrait
```

Modal supports WebSockets through ASGI apps, including persistent GPU-backed classes. citeturn3search0turn3search2

## 6. Connect Render to Modal

Set this Render environment variable on `kemzy-api-production`:

```
MODAL_GPU_WS_URL=wss://<modal-worker-url>/gpu-bridge
```

The existing Render gateway already contains the Modal WebSocket client path. Once this variable is present, Render connects to the Modal worker and the gateway's `/ready` endpoint should report a connected GPU worker.

## 7. What success looks like

Modal logs should contain:

```
KEMZY_MODAL_READY ...
KEMZY_MODAL_WORKER_CONNECTED ...
```

Render logs should contain:

```
MODAL_GPU_WORKER_CONNECTED ...
MODAL_GPU_WORKER_REGISTERED
```

Then:

```
https://kemzy-api-production.onrender.com/ready
```

should show:

```json
{
  "status": "ready",
  "renderer": "FasterLivePortrait",
  "backend": "FasterLivePortrait1",
  "workers_connected": 1
}
```

**Important:** seeing a deployed Modal App is not the same as proving the complete camera → GPU → rendered-frame round trip. The final proof is a real source image plus a real driving camera frame producing a returned rendered frame.

## Why this uses FasterLivePortrait

The worker clones `eneokonaniebiet/FasterLivePortrait1` and runs its `configs/onnx_infer.yaml` pipeline. The official FasterLivePortrait project documents its GPU Docker image and ONNX/TensorRT inference path. citeturn2search0turn2search4
