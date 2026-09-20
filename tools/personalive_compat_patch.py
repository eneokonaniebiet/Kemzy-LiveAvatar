#!/usr/bin/env python3
"""Apply the verified Kaggle compatibility patches to a pinned PersonaLive checkout.

This intentionally patches only known upstream incompatibilities:
1. Diffusers >=0.34 expects a torch Tensor for sin/cos position embeddings,
   while PersonaLive's original code expects the returned value as NumPy.
2. PersonaLive offline inference enables xFormers by default; Kaggle's current
   Torch/CUDA environment may not provide a compatible xFormers build, so the
   proof disables it unless explicitly requested.

The script refuses to patch unexpected source so upstream changes cannot be
silently overwritten.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / ".personalive"
ENCODER = ROOT / "src/models/motion_encoder/encoder.py"
INFERENCE = ROOT / "inference_offline.py"

def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new), encoding="utf-8")
    print(f"PATCHED: {label}")

# The upstream commit uses:
# get_1d_sincos_pos_embed_from_grid(out_ch, np.arange(expr_dim//out_ch))
# Diffusers 0.37.1 requires torch positions and output_type='pt'.
# Convert the result back to NumPy because the original register_buffer line
# uses torch.from_numpy().
replace_once(
    ENCODER,
    "extra_pos_embed = get_1d_sincos_pos_embed_from_grid(out_ch, np.arange(expr_dim//out_ch))",
    """extra_pos_embed = get_1d_sincos_pos_embed_from_grid(
            out_ch,
            torch.arange(expr_dim // out_ch, dtype=torch.float32),
            output_type="pt",
        ).cpu().numpy()""",
    "MotionEncoder Diffusers compatibility",
)

# Avoid the incompatible xFormers default in the Kaggle proof.
replace_once(
    INFERENCE,
    'parser.add_argument("--use_xformers", type=bool, default=True)',
    'parser.add_argument("--use_xformers", type=bool, default=False)',
    "offline inference xFormers default",
)

print("PersonaLive compatibility patches: PASS")
