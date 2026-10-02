# NVIDIA DLSS SDK source

The headers, SDK license and upstream README are copied unchanged from the
official [DLSS v310.9.1 release](https://github.com/NVIDIA/DLSS/releases/tag/v310.9.1),
commit `374959484e79a640feaba44c93ac8cfb0a03f5b5`, checked on 2026-10-02.
This release explicitly provides Ray Reconstruction preset F.

`v310.9.1` is an annotated tag: tag object
`d8339a290d42bd2a80445084309aa5345f61c66b` points to the commit above. GitHub's
tag zipball uses `NVIDIA-DLSS-d8339a2/` as its root, so the archive prefix and source
commit intentionally differ. Both identities are recorded in the SDK lock.

The production integration calls DLSS RR through Streamline's `sl.dlss_d` plugin.
It does not link a second NGX implementation. The DLSS SDK's production
`lib/Windows_x86_64/rel/nvngx_dlssd.dll` matches Streamline's bundled runtime
byte-for-byte (SHA-256
`4bc7ea5fcb2f32cf86bc2cb072e8d2860914a0be511cbdcb2fc206c1d9d83b80`).
That DLL is retained once under `third_party/streamline/bin/x64`.

Acquisition, provenance and hashes for both SDKs are managed by
`scripts/fetch-streamline-sdk.ps1` and `third_party/streamline/sdk-lock.json`.
