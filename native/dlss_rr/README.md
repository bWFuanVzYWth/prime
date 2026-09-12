# Prime DLSS Ray Reconstruction bridge

This narrow C ABI owns NGX initialization, optimal-resolution queries, feature creation,
evaluation, and release. Prime continues to own every Vulkan resource and synchronization point.

Build on Windows x86-64 from a Visual Studio developer shell:

```powershell
cmake -S native/dlss_rr -B build/native/dlss_rr -G Ninja `
  -DDLSS_SDK_DIR=C:/WorkSpace/_ref/DLSS -DCMAKE_BUILD_TYPE=Release
cmake --build build/native/dlss_rr
```

Configuration fetches the CMake-pinned Vulkan-Headers `v1.3.296`; it does not consume headers from
the development Vulkan SDK used by the Java/Slang build. Changing either the DLSS SDK ABI or this
header pin requires rebuilding the bridge and rerunning `DlssRrNativeContractTest`.

The bridge fixes every supported quality mode to Ray Reconstruction render preset F. Its private
ABI exposes that selection so the Java loader and contract test reject a mismatched bridge.
ABI v8 appends an optional input-resolution `VK_FORMAT_R16_SFLOAT` responsivity image. An all-zero
image descriptor is the explicit absent value and is forwarded to NGX as a null
`pInResponsivityMask`; every nonzero descriptor must satisfy the exact format and extent contract.
ABI v9 changes only normal/roughness to direct `VK_FORMAT_R32G32B32A32_SFLOAT`; the bridge rejects
older RGBA16F or octahedral guide inputs before calling NGX.
ABI v10 changes primary visible-surface motion to `VK_FORMAT_R32G32_SFLOAT`; reflection motion
already used the same lossless baseline format. The semantic inputs now have independent images
instead of reusing the transport scratch image across phases.

ABI v11 makes reflection motion optional, using the same all-zero descriptor convention. Hit
distance remains required; absent reflection motion is passed to NGX as a null pointer with both
camera matrices still supplied. The session comparison controls are described in
[Reconstruction input experiments](../../docs/重建输入对照实验.md).
`ctest --test-dir build/native/dlss_rr -C Release --output-on-failure` exercises the production
argument builder without initializing NGX or Vulkan.

ABI v13 enables `NVSDK_NGX_DLSS_Feature_Flags_AlphaUpscaling` with preset F. Input RGBA16F alpha
is actual foreground coverage (camera miss 0, visible surface 1); output alpha is consumed by the
native-resolution star compositor. Virtual guide validity must not replace actual visibility.
The packed image/argument layouts remain unchanged. Responsivity and transparency inputs retain
their existing defaults. Java requires v13 so older feature creation flags cannot silently omit alpha.

An optional RTX test runs 64 real NGX frames followed by the production `rr_stars` SPIR-V: moving
silhouettes in both directions, Halton jitter and history resets. It checks finite output, sky alpha,
foreground star leakage, sky radiance and antialiased silhouette composition. Preset F can undershoot
opaque alpha after a reset; the compositor uses the current fully foreground neighborhood to block
that leakage, while retaining NGX coverage at mixed boundaries. This synthetic test does not replace
game validation of foliage, fine geometry, disocclusion, glass, water or GPU frame cost.

With a complete Vulkan SDK (headers, loader library and Khronos validation layer) installed:

```powershell
.\gradlew.bat compileSlangProgramRrStars --console=plain
cmake -S native/dlss_rr -B build/native/dlss_rr -DPRIME_DLSS_RR_GPU_TESTS=ON `
  -DDLSS_SDK_DIR=C:/WorkSpace/_ref/DLSS
cmake --build build/native/dlss_rr --config Release
build/native/dlss_rr/Release/prime_dlss_rr_gpu_test.exe `
  src/client/resources/prime/natives/windows-x86_64 build/starmap-mips/ngx `
  build/shaderPrograms/rr_stars/rr_stars.comp.spv
```

The optional test links the development SDK's Vulkan loader; production bridge headers remain pinned.

Copy `prime_dlss_rr.dll` beside the release `nvngx_dlssd.dll` in
`src/client/resources/prime/natives/windows-x86_64`. The bundled runtime is `310.7.128.0` with
SHA-256 `59A005A6BEBBDE6DB27282B22D35E5E746FFB0BC91B07736B986BC658DC631FE`.
