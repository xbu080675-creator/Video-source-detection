# v0.1 detection model

The first detector is intentionally interpretable and lightweight enough to run on-device while another video app is active.

## Inputs

The MediaProjection service samples three 320×180 pixel regions every ~650 ms and keeps the region with the highest usable texture. A rolling window of 24 samples is aggregated for the HUD.

## Features

- Luma standard deviation: rejects flat/black frames.
- Gradient and edge density: measures spatial structure.
- Laplacian energy normalized by local contrast: a proxy for surviving high-frequency detail.
- 2× phase imbalance: compares horizontal and vertical second derivatives on odd/even pixel phases. A large periodic difference is consistent with one pixel phase being interpolated from neighbors, a common fingerprint of exact 2× scaling.

## Interpretation

`upscaleRisk` is a heuristic probability-like score, not a forensic probability. It is suppressed when the frame has too little texture. `confidence` describes whether the current sampled region contains enough useful structure to interpret the detector.

## Why signal resolution is not enough

A 1080p master can be upscaled and encoded as a genuine 3840×2160 bitstream. MediaCodec, DASH/HLS manifests, and container metadata will all truthfully report 2160p. They cannot identify the master resolution by themselves.

## Why screen capture is not enough

MediaProjection observes the post-composition output. On a ~1080p phone, both native-4K content and 1080p-upscaled-to-4K content are eventually reduced to the display resolution. Some quality differences survive, but the app must not claim proof of native 4K from that signal alone. This is why v0.1 combines render analysis with optional codec/manifest evidence and surfaces limitations explicitly.
