# Kémzy Cloudflare workers.dev Gateway

Stable Cloudflare workers.dev front door without requiring a purchased domain.

Flow:
Android/client -> Worker -> Render /ready discovery -> current Kaggle Quick Tunnel -> Kémzy LivePortrait GPU worker.

The Worker discovers the current Kaggle Quick Tunnel from the Render API /ready response on every request, so the Android app does not contain a temporary trycloudflare.com hostname.

Endpoints:
- GET /health
- GET /ready
- All other HTTP paths are proxied to the current GPU worker.
- WebSocket /v1/stream/{session_id} is passed through to the GPU worker.

Deploy from this directory with:
npx wrangler deploy

No Cloudflare token, API key, or tunnel token is stored in GitHub.

The workers.dev hostname is stable, but Kaggle GPU availability remains temporary. If Kaggle stops, /ready reports degraded until a new GPU worker is running.
