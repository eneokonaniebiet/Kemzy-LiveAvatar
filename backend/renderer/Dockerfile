FROM nvidia/cuda:12.8.1-cudnn-runtime-ubuntu24.04

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    MODEL_DIR=/models

RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY backend/renderer/requirements.txt ./requirements.txt
RUN python3 -m pip install --break-system-packages -r requirements.txt
COPY backend/renderer ./renderer

EXPOSE 8000
CMD ["python3", "-m", "uvicorn", "renderer.app:app", "--host", "0.0.0.0", "--port", "8000"]
