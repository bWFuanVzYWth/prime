# Third-party licenses

Prime-authored code uses GPL-3.0-only with the additional permissions in
`LICENSE-EXCEPTIONS` at the source-tree and mod-JAR root. The GPL text is in
`LICENSE` in the source tree and `LICENSE_prime` in the JAR. The exception
permits the specified Minecraft and NVIDIA combinations; it does not relicense
third-party material or waive its license obligations. The terms below also
apply to third-party material incorporated into Prime source files.

- FidelityFX SDK 1.1.4 (AMD's signed Vulkan library containing the FSR 3.1.4 Upscaler): MIT
  License. See `FIDELITYFX-SDK-LICENSE.txt`. Prime calls only the upscaling API; frame
  interpolation and swapchain replacement are not included or used.

Prime releases include a compiled NVIDIA Real-time Denoisers (NRD) component.
The NVIDIA code in that component remains subject to the NVIDIA RTX SDKs
License in `NRD-LICENSE.txt`. Prime's own bridge code is covered by
GPL-3.0-only with the additional permissions in `LICENSE-EXCEPTIONS`.

NVIDIA, the NVIDIA logo, and NVIDIA Real-time Denoisers (NRD) are trademarks
and/or registered trademarks of NVIDIA Corporation in the United States and
other countries.

Prime releases also include its own `prime_dlss_rr.dll` C ABI bridge and NVIDIA's
release `nvngx_dlssd.dll` for DLSS Ray Reconstruction. Prime's own bridge code
is covered by GPL-3.0-only with the additional permissions in
`LICENSE-EXCEPTIONS`; NVIDIA's linked SDK code and redistributable remain
subject to `DLSS-SDK-LICENSE.txt`. Prime does not ship the DLSS development DLL
or standalone DLSS Super Resolution.

Windows releases also include NVIDIA Streamline and its Reflex/PCL plugins under the
Streamline MIT license in `STREAMLINE-LICENSE.txt`. The DLSS Frame Generation plugin,
`nvngx_dlssg.dll`, and `NvLowLatencyVk.dll` remain subject to the NVIDIA RTX SDK terms
in `DLSS-SDK-LICENSE.txt`. DLSS Frame Generation is exposed only as a high-risk
experiment because unresolved NVIDIA Vulkan synchronization defects can cause an
unrecoverable device-lost crash.

NVIDIA, DLSS, GeForce RTX, and their associated logos are trademarks and/or
registered trademarks of NVIDIA Corporation in the United States and other countries.

Prime's OpenPBR closure library and transmission-GGX energy data are derived
from RoboCute's Apache-2.0-licensed BSDF implementation. See
`ROBOCUTE-NOTICE.txt` and `APACHE-2.0.txt`.

Prime's epipolar sun-shadow profile is adapted from Intel's Apache-2.0-licensed
Outdoor Light Scattering Sample. See `OUTDOOR-LIGHT-SCATTERING-NOTICE.txt` and
`APACHE-2.0.txt`.

Prime's Sobol samplers adapt pbrt-v4's Owen-scrambled and hierarchical Z-Sobol
implementations. See `PBRT-V4-NOTICE.txt` and `APACHE-2.0.txt`.

Prime's realtime spatiotemporal blue-noise lookup table is optimized with EA's
BSD-3-Clause FastNoise implementation. See `FASTNOISE-NOTICE.txt`.

Prime's night sky uses NASA Scientific Visualization Studio's Deep Star Maps
2020 with Gaia DR2 data. See `NASA-DEEP-STAR-MAPS-2020-NOTICE.md` for the
source, lossless repacking details, and requested attribution.
