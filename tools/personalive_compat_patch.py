#!/usr/bin/env python3
"""Apply verified compatibility patches to a pinned PersonaLive checkout.

Usage:
    python tools/personalive_compat_patch.py /path/to/PersonaLive
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: personalive_compat_patch.py /path/to/PersonaLive")

ROOT = Path(sys.argv[1]).resolve()
ENCODER = ROOT / "src/models/motion_encoder/encoder.py"
INFERENCE = ROOT / "inference_offline.py"

for required in (ENCODER, INFERENCE):
    if not required.is_file():
        raise FileNotFoundError(required)

def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new), encoding="utf-8")
    print(f"PATCHED: {label}")

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

replace_once(
    INFERENCE,
    'parser.add_argument("--use_xformers", type=bool, default=True)',
    'parser.add_argument("--use_xformers", type=bool, default=False)',
    "offline inference xFormers default",
)

print("PersonaLive compatibility patches: PASS")
