# Prime NRD native bridge

Prime ships a small versioned C ABI around NRD Core. The bridge is intentionally API-agnostic: it
returns SPIR-V, texture descriptions and per-frame dispatch descriptions while Prime retains
ownership of every Vulkan object and synchronization point.

The checked-in release DLL is rebuilt only when this bridge or the pinned NRD version changes.
Prime compiles NRD with normal encoding mode 4 and binds `RGBA32_SFLOAT`: xyz are direct signed
world-normal components and w is linear roughness. Do not restore the octahedral 10:10:10:2 mode.
From the repository root on Windows:

```powershell
cmake -S native/nrd -B build/native/nrd -G "Visual Studio 18 2026" -A x64 `
  -DNRD_SOURCE_DIR="C:/path/to/NRD"
cmake --build build/native/nrd --config Release --target prime_nrd --parallel
```

The build is deliberately pinned to NRD 4.17.4 source commit
`9a3fe938a7558fd16b6c91a1c0456305cdcd9f16`. Before configuring, apply
`third_party/nrd/nrd-4.17.4-reblur-sh-transient.patch`. The pinned source accidentally declares
one extra full-resolution `RGBA16F` transient immediately before REBLUR SH's 1/16-resolution tile
surface. Its scheduler consequently binds the full-resolution surface as `gOut_Tiles`, violating
the Vulkan storage-image component contract and leaving the actual tile surface unused. The patch
removes only that orphan declaration and restores the scheduler's existing enum-to-pool mapping.

REBLUR SH1 is intentionally a three-component value, but NRD allocates its portable backing
texture as `RGBA16F`. DXC consequently emits formatless three-component `OpImageWrite`
instructions, which violate Vulkan's storage-image component contract when Prime binds the
four-component view. `NrdSpirv` repairs those writes at Prime's native-adapter boundary by adding
an unused fourth component. NRD reads SH1 as `xyz`, so this does not change denoising math or
require a source modification to the pinned SDK.

Prime builds SPIR-V only, with one NRD instance containing two
`REBLUR_DIFFUSE_SPECULAR_SH` denoisers and one `SIGMA_SHADOW`, no NRI and no quad-intrinsics extension.
The denoiser roles, input semantics, history settings and transparent guide contract are renderer behavior;
their canonical specification is [Transparency and realtime reconstruction](../../docs/透明渲染与实时重建.md).
Copy the resulting `build/native/nrd/bin/Release/prime_nrd.dll` to
`src/client/resources/prime/natives/windows-x86_64/prime_nrd.dll` and run the full Gradle build.

NRD remains subject to the NVIDIA RTX SDKs License. Its source is not part of this repository.
