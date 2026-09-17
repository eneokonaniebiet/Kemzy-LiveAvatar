FROM nvidia/cuda:12.1.1-cudnn8-runtime-ubuntu22.04

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    MODEL_DIR=/models \
    LIVEPORTRAIT_REPO=/opt/LivePortrait \
    LIVEPORTRAIT_WEIGHTS=/models \
    LIVEPORTRAIT_COMMIT=9b294b3d0536135442ea73cb01e6cb3ca7029dd3 \
    KEMZY_ALLOW_MODEL_DOWNLOAD=0

RUN apt-get update && apt-get install -y --no-install-recommends \
    git python3 python3-pip ffmpeg ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY backend/renderer/requirements.txt ./requirements.txt
RUN python3 -m pip install --break-system-packages \
    torch==2.3.0 torchvision==0.18.0 \
    --index-url https://download.pytorch.org/whl/cu121 \
    && python3 -m pip install --break-system-packages -r requirements.txt

# Materialize the complete official LivePortrait source tree at the exact
# revision pinned by Kémzy. Model weights are intentionally NOT baked into
# the image; they are provisioned server-side into LIVEPORTRAIT_WEIGHTS.
RUN git clone --filter=blob:none https://github.com/KlingAIResearch/LivePortrait.git "$LIVEPORTRAIT_REPO" \
    && git -C "$LIVEPORTRAIT_REPO" fetch --depth 1 origin "$LIVEPORTRAIT_COMMIT" \
    && git -C "$LIVEPORTRAIT_REPO" checkout --detach "$LIVEPORTRAIT_COMMIT" \
    && test "$(git -C "$LIVEPORTRAIT_REPO" rev-parse HEAD)" = "$LIVEPORTRAIT_COMMIT" \
    && rm -rf "$LIVEPORTRAIT_REPO/.git"

COPY backend/renderer ./renderer
COPY backend/zerogpu_space/liveportrait_adapter.py ./renderer/liveportrait_adapter.py

EXPOSE 8000
CMD ["python3", "-m", "uvicorn", "renderer.app:app", "--host", "0.0.0.0", "--port", "8000"]
