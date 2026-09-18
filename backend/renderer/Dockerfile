FROM nvidia/cuda:12.1.1-cudnn8-runtime-ubuntu22.04

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    MODEL_DIR=/models \
    PERSONALIVE_REPO=/opt/PersonaLive \
    PERSONALIVE_COMMIT=abdd112e01dcf7d89122c2e5efa29fcff0669740 \
    PORT=7860 \
    ACCELERATION=xformers \
    KEMZY_DOWNLOAD_WEIGHTS=1

RUN set -eux; \
    export DEBIAN_FRONTEND=noninteractive; \
    apt-get update; \
    apt-get install -y --no-install-recommends git python3 python3-pip ca-certificates ffmpeg; \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Materialize the complete upstream PersonaLive source tree at an exact immutable revision.
RUN git clone --filter=blob:none https://github.com/GVCLab/PersonaLive.git "$PERSONALIVE_REPO" \
    && git -C "$PERSONALIVE_REPO" fetch --depth 1 origin "$PERSONALIVE_COMMIT" \
    && git -C "$PERSONALIVE_REPO" checkout --detach "$PERSONALIVE_COMMIT" \
    && test "$(git -C "$PERSONALIVE_REPO" rev-parse HEAD)" = "$PERSONALIVE_COMMIT" \
    && rm -rf "$PERSONALIVE_REPO/.git"

# PersonaLive is the authoritative renderer runtime; keep its upstream dependency set intact.
RUN cd "$PERSONALIVE_REPO" \
    && python3 -m pip install --break-system-packages -r requirements_base.txt

COPY backend/renderer/personalive_server.py /app/renderer/personalive_server.py
COPY backend/renderer/start_personalive.sh /app/renderer/start_personalive.sh
RUN chmod +x /app/renderer/start_personalive.sh

EXPOSE 7860
CMD ["/app/renderer/start_personalive.sh"]
