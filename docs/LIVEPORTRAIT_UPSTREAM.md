# Kémzy LivePortrait upstream

Pinned upstream source: `KlingAIResearch/LivePortrait`

Commit: `9b294b3d0536135442ea73cb01e6cb3ca7029dd3`

The repository keeps the complete upstream tree as a git submodule at `third_party/LivePortrait`. The GPU image also materializes the same exact commit during its build, so deployment does not depend on a developer remembering to initialize submodules.

Kémzy-specific code remains outside the upstream tree and wraps the official LivePortrait pipeline for source sessions and real-time motion streaming.
