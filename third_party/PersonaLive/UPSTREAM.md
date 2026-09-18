# PersonaLive upstream integration

Kémzy àvátâr uses the official GVCLab PersonaLive implementation as its neural live-portrait renderer.

- Upstream: https://github.com/GVCLab/PersonaLive
- Pinned revision: `abdd112e01dcf7d89122c2e5efa29fcff0669740`
- License: Apache-2.0
- Runtime location in the GPU image: `/opt/PersonaLive`
- Persistent model location: `/models`

The GPU Docker build materializes the complete upstream repository at the pinned revision. This includes the official inference pipeline, webcam streaming implementation, model code, configuration, frontend, utilities, and acceleration paths.

Large pretrained model binaries and media assets are intentionally not duplicated into the Kémzy Git history. PersonaLive's repository contains placeholders/readme files for several weight directories; the actual weights are downloaded/materialized on the GPU persistent volume according to the upstream layout.

Kémzy's API adapter exposes the same core streaming behavior through:
- POST /v1/sessions
- POST /v1/sessions/{session_id}/source
- WS /v1/stream/{session_id}
- POST /v1/sessions/{session_id}/reset

The Android client sends JPEG camera frames over the persistent WebSocket and displays PersonaLive's generated JPEG frames.

PersonaLive's upstream README describes its online mode as real-time and streamable, with image reference fusion and acceleration options including none, xFormers, and TensorRT.
