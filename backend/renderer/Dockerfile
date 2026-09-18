FROM nvidia/cuda:12.1.1-cudnn8-runtime-ubuntu22.04

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    MODEL_DIR=/models \
    LIVEPORTRAIT_REPO=/opt/LivePortrait \
    LIVEPORTRAIT_WEIGHTS=/models/pretrained_weights \
    LIVEPORTRAIT_COMMIT=9b294b3d0536135442ea73cb01e6cb3ca7029dd3 \
    KEMZY_ALLOW_MODEL_DOWNLOAD=0 \
    PORT=7860

# Install the small build/runtime toolchain first so failures are isolated and the\n# package manager can release its metadata before the multimedia stack is added.\nRUN set -eux; \\
    export DEBIAN_FRONTEND=noninteractive; \\
    apt-get update; \\
    apt-get install -y --no-install-recommends \\
      git python3 python3-pip ca-certificates; \\
    rm -rf /var/lib/apt/lists/*\n\n# ffmpeg is required for video-source decoding/encoding. Keep it in its own layer\n# because it has a much larger Ubuntu dependency tree than the core toolchain.\nRUN set -eux; \\
    export DEBIAN_FRONTEND=noninteractive; \\
    apt-get update; \\
    apt-get install -y --no-install-recommends ffmpeg; \\
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY backend/renderer/requirements.txt ./requirements.txt

# Keep the PyTorch versions aligned with the pinned official LivePortrait release.
RUN python3 -m pip install --break-system-packages \
    torch==2.3.0 torchvision==0.18.0 torchaudio==2.3.0 \
    --index-url https://download.pytorch.org/whl/cu121 \
    && python3 -m pip install --break-system-packages -r requirements.txt

# Materialize the COMPLETE official LivePortrait source tree at one exact revision.
RUN git clone --filter=blob:none https://github.com/KlingAIResearch/LivePortrait.git "$LIVEPORTRAIT_REPO" \
    && git -C "$LIVEPORTRAIT_REPO" fetch --depth 1 origin "$LIVEPORTRAIT_COMMIT" \
    && git -C "$LIVEPORTRAIT_REPO" checkout --detach "$LIVEPORTRAIT_COMMIT" \
    && test "$(git -C "$LIVEPORTRAIT_REPO" rev-parse HEAD)" = "$LIVEPORTRAIT_COMMIT" \
    && rm -rf "$LIVEPORTRAIT_REPO/.git"

COPY backend/renderer ./renderer
COPY backend/zerogpu_space/liveportrait_adapter.py ./renderer/liveportrait_adapter.py

EXPOSE 7860
CMD ["sh", "-c", "python3 -m uvicorn renderer.app:app --host 0.0.0.0 --port ${PORT:-7860}"]
