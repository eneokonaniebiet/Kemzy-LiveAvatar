#!/bin/sh
set -eu

cd /opt/PersonaLive
MODEL_DIR="${MODEL_DIR:-/models}"
mkdir -p "$MODEL_DIR/pretrained_weights"

WEIGHTS_ROOT="$MODEL_DIR/pretrained_weights"
PERSONALIVE_LINK="/opt/PersonaLive/pretrained_weights"

if [ "${KEMZY_DOWNLOAD_WEIGHTS:-1}" = "1" ]; then
  if [ ! -f "$WEIGHTS_ROOT/personalive/denoising_unet.pth" ] || \
     [ ! -f "$WEIGHTS_ROOT/personalive/temporal_module.pth" ] || \
     [ ! -f "$WEIGHTS_ROOT/sd-vae-ft-mse/diffusion_pytorch_model.bin" ] || \
     [ ! -f "$WEIGHTS_ROOT/sd-image-variations-diffusers/image_encoder/pytorch_model.bin" ] || \
     [ ! -f "$WEIGHTS_ROOT/sd-image-variations-diffusers/unet/diffusion_pytorch_model.bin" ]; then
    echo "[Kémzy] PersonaLive weights missing; downloading to $WEIGHTS_ROOT ..."
    rm -rf "$WEIGHTS_ROOT"
    mkdir -p "$WEIGHTS_ROOT"
    rm -rf "$PERSONALIVE_LINK"
    ln -s "$WEIGHTS_ROOT" "$PERSONALIVE_LINK"
    python3 tools/download_weights.py
  fi
fi

rm -rf "$PERSONALIVE_LINK"
ln -s "$WEIGHTS_ROOT" "$PERSONALIVE_LINK"

test -f /opt/PersonaLive/configs/prompts/personalive_online.yaml

for required in \
  personalive/denoising_unet.pth \
  personalive/motion_encoder.pth \
  personalive/motion_extractor.pth \
  personalive/pose_guider.pth \
  personalive/reference_unet.pth \
  personalive/temporal_module.pth \
  sd-vae-ft-mse/diffusion_pytorch_model.bin \
  sd-vae-ft-mse/config.json \
  sd-image-variations-diffusers/image_encoder/pytorch_model.bin \
  sd-image-variations-diffusers/image_encoder/config.json \
  sd-image-variations-diffusers/unet/diffusion_pytorch_model.bin \
  sd-image-variations-diffusers/unet/pytorch_model.bin \
  sd-image-variations-diffusers/unet/config.json \
  sd-image-variations-diffusers/model_index.json
do
  test -f "$WEIGHTS_ROOT/$required" || {
    echo "[Kémzy] Missing PersonaLive weight: $WEIGHTS_ROOT/$required" >&2
    exit 1
  }
done

echo "[Kémzy] PersonaLive commit: ${PERSONALIVE_COMMIT:-unknown}"
echo "[Kémzy] PersonaLive weights: ready"
echo "[Kémzy] CUDA renderer: required; Android remains a thin camera/preview client"
exec python3 /app/renderer/personalive_server.py \
  --host 0.0.0.0 \
  --port "${PORT:-7860}" \
  --acceleration "${ACCELERATION:-xformers}"

# CI trigger: hardened PersonaLive runtime checks.
