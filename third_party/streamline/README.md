# NVIDIA Streamline SDK

The checked-in subset comes from the official NVIDIA GitHub release
[v2.14.1](https://github.com/NVIDIA-RTX/Streamline/releases/tag/v2.14.1), verified on
2026-10-02. Header/source commit: `2122257e0fce486f91b385aa63b9a09b0a34b363`.
The three Streamline DLLs have file version `2.14.1.0`; `nvngx_dlssd.dll` is
`310.9.1.0`. All four production DLL signatures were verified as NVIDIA Corporation.
Development DLLs and unrelated features are excluded.

`sdk-lock.json` records the official download URLs, release/commit identities,
archive SHA-256 values, and the archive entry plus SHA-256 for every vendored file.
The RR runtime from the separately downloaded official DLSS v310.9.1 SDK has
identical bytes; its archive entry and hash are recorded in the same lock. Only
one runtime copy is retained, in `bin/x64`.

From the repository root:

```powershell
.\scripts\fetch-streamline-sdk.ps1 -VerifyOnly
.\scripts\fetch-streamline-sdk.ps1 -CheckLatest
```

Without `-VerifyOnly`, the script downloads missing pinned archives from official
GitHub locations into ignored `artifacts/streamline-sdk`, verifies each archive,
and restores exactly the locked subset. `-CheckLatest` also compares NVIDIA's
latest release metadata with the lock; it never silently upgrades the ABI or DLLs.
GitHub-generated source archives are byte-pinned too: a changed archive is rejected
and requires review, even if its extracted sources might be equivalent.

`vulkan-headers` contains the C headers and licenses from KhronosGroup's official
Vulkan-Headers `v1.4.350`. They supply compile-time declarations only; no Vulkan
loader is bundled, and the installed driver remains authoritative.

The NVIDIA runtime terms, Streamline license, third-party notices and Khronos
licenses are also copied to the root `licenses` directory for JAR packaging.
`licenses/NVIDIA-DLSS.txt` is an exact byte copy of the runtime's
`bin/x64/nvngx_dlss.license.txt` (26,727 bytes, SHA-256
`3027f23ca5a46dd9cb8183fbd522983a86f64d7daac5982912bf9f214671f294`).
The separate DLSS SDK's `LICENSE.txt` has identical text with LF instead of CRLF;
the 107-byte size difference is solely line endings, not additional runtime terms.
