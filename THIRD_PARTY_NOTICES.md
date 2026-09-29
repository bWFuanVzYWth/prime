# Adapted code and references

`crates/prime-vulkan/shaders/math/ray_offset.slang` adapts NVIDIA's
`SelfIntersectionAvoidance.hlsl` from
[self-intersection-avoidance](https://github.com/NVIDIA/self-intersection-avoidance).
Copyright (c) 2023 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
BSD-3-Clause; the full notice is in
[licenses/nvidia-self-intersection-BSD-3-Clause.txt](licenses/nvidia-self-intersection-BSD-3-Clause.txt).
The port exposes only the reconstructed surface and outgoing spawn operation,
preserving the reconstruction order and object/world error bounds.

`crates/prime-vulkan/shaders/math/z_sobol.slang` and its scalar test oracle adapt
the author's `z_sobol` project. The Slang port uses fixed shifts on two 32-bit
words, shares the domain hash, and evaluates the Y transform in reversed bit
order to cancel adjacent bit reversals. Its sample values follow that project's
sampler, not PBRT's original index hash. The hierarchical construction follows
Ahmed and Wonka, *Screen-Space Blue-Noise Diffusion of Monte Carlo Sampling Error
via Hierarchical Ordering of Pixels*, [ACM TOG 2020](https://doi.org/10.1145/3414685.3417881).
FastOwen scrambling derives from [PBRT-v4](https://github.com/mmp/pbrt-v4),
copyright (c) 1998–2020 Matt Pharr, Wenzel Jakob, and Greg Humphreys;
[Apache-2.0](licenses/pbrt-Apache-2.0.txt).

`crates/prime-vulkan/shaders/display/prime_drt.slang`, the curve derivation in
`src/display.rs`, and `tests/shaders/legacy_display.slang` adapt legacy Prime's
`service/reconstruct/display.slang` and `RgbReinhardOutput.java`, copyright (c)
2026 linlin. This code is part of Prime under the project's [LICENSE](LICENSE)
and [LICENSE-EXCEPTIONS](LICENSE-EXCEPTIONS).
Legacy Prime identified its DRT source as the author's `drt` project at commit
`a15e90947965f93c71e943fe27caf2ea56e08c10`. This port names the transform
`primeDRT`, narrows helper visibility, and moves output-peak encoding out of the
pixel loop. The test fixture preserves the legacy algorithm with self-contained
dependencies. These notices describe the incorporated files, not unrelated
third-party libraries or assets.

Both adapter JARs include the project LICENSE and LICENSE-EXCEPTIONS at the root,
and these notices and third-party license texts under `META-INF`.


`crates/prime-vulkan/shaders/atmosphere`, the physical assets and frozen reference
fixtures adapt legacy Prime's atmosphere (copyright 2026 linlin), incorporating
Sky Tracer `b66b16342afe38e788a5ece5371d3b3a67c5909a`, GPL-3.0-only.
See [Sky Tracer notice](licenses/atmosphere-SKY-TRACER-NOTICE.md) and [LICENSE](LICENSE).
The physical algorithm and default calibration are retained; the container,
resource ownership, prepared consumers and cache scheduling are changed.

`crates/prime-scene/src/environment.rs` adapts legacy Prime's `AstronomyState`
and `AstronomySettings` (copyright 2026 linlin), under the project's
[LICENSE](LICENSE) and [LICENSE-EXCEPTIONS](LICENSE-EXCEPTIONS).

The epipolar shadow profile derives from Intel's Outdoor Light Scattering
Sample, copyright 2017 Intel Corporation, Apache-2.0, reference commit
`3b31b3b8c1aaad8580dc7249b3b79f6c0993d7a8`.
See [notice](licenses/intel-outdoor-light-scattering-NOTICE.txt) and
[license](licenses/intel-outdoor-light-scattering-Apache-2.0.txt).
