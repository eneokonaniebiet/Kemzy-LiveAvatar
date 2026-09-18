#!/bin/sh
set -eu

cd /opt/PersonaLive

mkdir -p "$MODEL_DIR/pretrained_weights"

# Keep the large PersonaLive weights on the persistent GPU volume, not in the
# Kémzy source repository and never on the Android device.
if [ "${KEMZY_DOWNLOAD_WEIGHTS:-1}" = "1" ]; then
  if [ ! -f "$MODEL_DIR/pretrained_weights/personalive/denoising_unet.pth" ] || \
     [ ! -f "$MODEL_DIR/pretrained_weights/sd-vae-ft-mse/diffusion_pytorch_model.bin" ] || \
     [ ! -f "$MODEL_DIR/pretrained_weights/sd-image-variations-diffusers/image_encoder/pytorch_model.bin" ]; then
    echo "[Kémzy] PersonaLive weights missing; downloading to $MODEL_DIR ..."
    rm -rf "$MODEL_DIR/pretrained_weights"
    mkdir -p "$MODEL_DIR/pretrained_weights"
    ln -s "$MODEL_DIR/pretrained_weights" /opt/PersonaLive/pretrained_weights
    python3 tools/download_weights.py
    rm -f /opt/PersonaLive/pretrained_weights
  fi
fi

# Always run the exact pinned upstream source tree.
if [ -d "$MODEL_DIR/pretrained_weights" ]; then
  rm -rf /opt/PersonaLive/pretrained_weights
  ln -s "$MODEL_DIR/pretrained_weights" /opt/PersonaLive/pretrained_weights
fi

test -f /opt/PersonaLive/configs/prompts/personalive_online.yaml
test -f "$MODEL_DIR/pretrained_weights/personalive/denoising_unet.pth"
test -f "$MODEL_DIR/pretrained_weights/personalive/motion_encoder.pth"
test -f "$MODEL_DIR/pretrained_weights/personalive/motion_extractor.pth"
test -f "$MODEL_DIR/pretrained_weights/personalive/pose_guider.pth"
test -f "$MODEL_DIR/pretrained_weights/personalive/reference_unet.pth"
test -f "$MODEL_DIR/pretrained_weights/personalive/temporal_module.pth"

echo "[Kémzy] PersonaLive commit: $PERSONALIVE_COMMIT"
echo "[Kémzy] PersonaLive weights: ready"
exec python3 /app/renderer/personalive_server.py \
  --host 0.0.0.0 \
  --port "${PORT:-7860}" \
  --acceleration "${ACCELERATION:-xformers}"

# CI trigger: PersonaLive runtime verification follows upstream webcam Pipeline semantics.
