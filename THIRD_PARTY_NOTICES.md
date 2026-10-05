# Adapted code and references

`crates/prime-vulkan/shaders/restir/`, the `restir_*.slang` entry points,
and the paired-neighbor assets adapt NVIDIA Falcor 9.0's ReSTIR PT Enhanced
at commit `759aad033ff610fb0d82c74f7e0a508d0096d5f2`.
Copyright (c) 2015-26 NVIDIA CORPORATION. All rights reserved.
BSD-3-Clause; the full notice is in
[licenses/nvidia-falcor-BSD-3-Clause.txt](licenses/nvidia-falcor-BSD-3-Clause.txt).
The point profile retains Hybrid shift, mixed-measure reservoirs, reciprocal
paired neighbors and pairwise MIS. Scene, material, light and GPU ownership
bindings adapt it to Prime; see [ReSTIR PT](docs/restir-pt.md).

The RR output-decorrelation modules and their display/storage glue are
Prime-authored implementations of the public algorithm description, under
the project license. They are not copies of the proprietary RTXDI-Library
sources; fixed provenance and behavioral differences are documented in
[RA-016](docs/restir-adaptations.md).

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

`third_party/streamline` and `third_party/dlss` contain the official NVIDIA
Streamline v2.14.1 and DLSS v310.9.1 SDK subsets. Windows adapter JARs redistribute
the production Streamline interposer, common, DLSS-D, DLSS-G, PCL and Reflex
plugins, the DLSS RR and frame-generation runtimes, and NVIDIA's Vulkan
low-latency runtime. Source identities and exact file hashes are in
`third_party/streamline/sdk-lock.json`; the SDKs retain their own terms in
[Streamline license](licenses/NVIDIA-Streamline.txt),
[Streamline third-party notices](licenses/NVIDIA-Streamline-Third-Party.md), and
[DLSS runtime license](licenses/NVIDIA-DLSS.txt), copied byte-for-byte from the
Streamline runtime's `nvngx_dlss.license.txt` and included in each native JAR.

`third_party/streamline/vulkan-headers` contains Khronos Vulkan-Headers v1.4.350
for bridge compilation, with its upstream
[Apache-2.0](licenses/Khronos-Vulkan-Headers-Apache-2.0.txt) and
[MIT](licenses/Khronos-Vulkan-Headers-MIT.txt) notices. These compile-time headers
do not replace the user's Vulkan loader or GPU driver.


`crates/prime-vulkan/shaders/atmosphere`, the physical assets and frozen reference
fixtures adapt legacy Prime's atmosphere (copyright 2026 linlin), incorporating
Sky Tracer `b66b16342afe38e788a5ece5371d3b3a67c5909a`, GPL-3.0-only.
See [Sky Tracer notice](licenses/atmosphere-SKY-TRACER-NOTICE.md) and [LICENSE](LICENSE).
The physical algorithm and default calibration are retained; the container,
resource ownership, prepared consumers and cache scheduling are changed.

`crates/prime-scene/src/environment.rs` adapts legacy Prime's `AstronomyState`
and `AstronomySettings` (copyright 2026 linlin), under the project's
[LICENSE](LICENSE) and [LICENSE-EXCEPTIONS](LICENSE-EXCEPTIONS).

`crates/prime-vulkan/assets/starmap/` incorporates the NASA SVS
[Deep Star Maps 2020](https://svs.gsfc.nasa.gov/4851/) celestial 16K EXR as a
preprocessed BC6H unsigned-float texture with 15 solid-angle-weighted mip levels.
The asset retains the source image's attribution and
[NASA media usage guidelines](https://www.nasa.gov/nasa-brand-center/images-and-media/),
not the license of Prime-authored code. See the
[NASA/ESA/Gaia credit and processing notice](licenses/NASA-DEEP-STAR-MAPS-2020-NOTICE.md).
Source, encoder, compressed and decoded hashes are in the adjacent asset manifest.
Its KTX2 container uses lossless Zstd supercompression; upload and GPU storage
total 178,957,008 bytes. It is compiled into the shared native engine, with no
second copy in the JAR.

Fixed asset decoding links Zstandard 1.5.7 through the Rust `zstd` 0.14.0,
`zstd-safe` 8.0.0 and `zstd-sys` 2.1.0 crates. Zstandard and generated bindings
are distributed under their BSD-3-Clause terms; the Rust wrappers are copyright
2026 Alexandre Bury, BSD-3-Clause. See the unmodified
[Zstandard license](licenses/zstd-BSD-3-Clause.txt),
[bindings license](licenses/zstd-bindings-BSD-3-Clause.txt) and
[Rust wrapper license](licenses/zstd-rust-BSD-3-Clause.txt).

The celestial projection/filter, automatic exposure, extended-sRGB HDR/UI
composition and Windows display probe adapt legacy Prime's corresponding
atmosphere and reconstruct modules and `HdrOutput`/`WindowsHdrDisplay`,
copyright (c) 2026 linlin, under [LICENSE](LICENSE) and
[LICENSE-EXCEPTIONS](LICENSE-EXCEPTIONS). Their resource bindings, recording,
completion ownership and Minecraft-specific GLFW/SDL routes are adapted for
Prime PT. This attribution does not replace the NASA image's separate notice.

The epipolar shadow profile derives from Intel's Outdoor Light Scattering
Sample, copyright 2017 Intel Corporation, Apache-2.0, reference commit
`3b31b3b8c1aaad8580dc7249b3b79f6c0993d7a8`.
See [notice](licenses/intel-outdoor-light-scattering-NOTICE.txt) and
[license](licenses/intel-outdoor-light-scattering-Apache-2.0.txt).

`crates/prime-vulkan/shaders/bsdf/common/common.slang` and `material.slang`
adapt the common value types, event flags, scalar/frame/Fresnel/roughness and
volume utilities carried by legacy Prime. Its BSDF mathematical basis identifies
[RoboCute](https://github.com/RoboCute/RoboCute), base source revision
`5985e989254b4685e3885d876b33f4874d233dcd`, copyright RoboCute contributors,
Apache-2.0. The incorporated scope is these shared foundations and default
initialization, as used by LitePBR. See the [RoboCute notice](licenses/robocute-NOTICE.txt)
and unmodified [Apache-2.0 license](licenses/robocute-Apache-2.0.txt).

`crates/prime-vulkan/shaders/bsdf/lite/bsdf.slang` ports the formal LitePBR
implementation from legacy Prime revision
`ca364b25c4b4c7de5c8eae82115b401bf1932b3e`, copyright (c) 2026 linlin, under this
project's [LICENSE](LICENSE) and [LICENSE-EXCEPTIONS](LICENSE-EXCEPTIONS), with
the shared foundations attributed above. Module imports and resource boundaries
are adapted; the low-order BSDF remains as a historical reference API and the
thick-wall SSS approximation shared by realtime and offline rendering. The current
production Full OpenPBR supported domain is attributed separately below.
The thin-wall Snell TIR endpoint explicitly returns full reflection and zero
transmission. Refractive sampling removes a redundant incident-side sign factor
from its initial reflection-candidate guard so exit reflection and TIR are not
discarded. Half-vector orientation, subsequent support checks, relative eta and
medium state are preserved. It uses scalar directional-energy fits without
transmission-GGX energy assets.

`crates/prime-minecraft/src/labpbr.rs`, Java's `LabPbrSources`, the canonical
material modules, numerical services and `pbr.slang` adapt legacy Prime's
LabPBR source translation and material consumers, copyright (c) 2026 linlin,
under this project's [LICENSE](LICENSE) and [LICENSE-EXCEPTIONS](LICENSE-EXCEPTIONS).
The port moves resource decoding, filtering and animation into Rust, changes
texture metadata and resource ownership, and preserves the source numerical
contracts. Legacy PBR presets are outside the incorporated scope.

`crates/prime-vulkan/shaders/bsdf/full/` adapts the exact supported-domain
OpenPBR kernels from legacy Prime revision
`b35438684203200b6ab8c0b19977bd06625faaea`, rooted in RoboCute reference
`0d982c77b3fd26c2c5a3c0852be3bd05e5860bd8` and the separately locked author
2026-07-24 dielectric-highlight overlay. The HALF4 transmission energy asset
is included under RoboCute's Apache-2.0 permission in
`crates/prime-vulkan/assets/openpbr/`; its hashes and reference-approved
differences are recorded in the adjacent lock JSON and author notice.
Prime's Slang ports and narrow source adapters remain under the project's
LICENSE and LICENSE-EXCEPTIONS. Existing RoboCute license/notice above apply.
The incorporated production scope excludes presets and arbitrary nonzero
coat/fuzz/film/diffraction/dispersion parameters. No full arbitrary material
API or GPU performance improvement is implied by this mathematical reference.
