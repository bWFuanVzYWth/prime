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

Copy `prime_dlss_rr.dll` beside the release `nvngx_dlssd.dll` in
`src/client/resources/prime/natives/windows-x86_64`. The bundled runtime is `310.7.128.0` with
SHA-256 `59A005A6BEBBDE6DB27282B22D35E5E746FFB0BC91B07736B986BC658DC631FE`.
