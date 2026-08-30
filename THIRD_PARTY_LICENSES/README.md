# Third-party licenses

- FidelityFX SDK 1.1.4 (AMD's signed Vulkan library containing the FSR 3.1.4 Upscaler): MIT
  License. See `FIDELITYFX-SDK-LICENSE.txt`. Prime calls only the upscaling API; frame
  interpolation and swapchain replacement are not included or used.

Prime releases include a compiled NVIDIA Real-time Denoisers (NRD) component.
That component is not covered by Prime's MIT license. It remains subject to the
NVIDIA RTX SDKs License in `NRD-LICENSE.txt`.

NVIDIA, the NVIDIA logo, and NVIDIA Real-time Denoisers (NRD) are trademarks
and/or registered trademarks of NVIDIA Corporation in the United States and
other countries.

Prime releases also include its own `prime_dlss_rr.dll` C ABI bridge and NVIDIA's
release `nvngx_dlssd.dll` for DLSS Ray Reconstruction. The SDK and redistributable
remain subject to `DLSS-SDK-LICENSE.txt`; Prime does not ship the DLSS development
DLL or standalone DLSS Super Resolution.

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

Prime's triangle projected-solid-angle light sampler is adapted from Christoph Peters'
BSD-3-Clause implementation. See `PROJECTED-SOLID-ANGLE-SAMPLING-NOTICE.txt`.

Prime's Sobol samplers adapt pbrt-v4's Owen-scrambled and hierarchical Z-Sobol
implementations. See `PBRT-V4-NOTICE.txt` and `APACHE-2.0.txt`.

Prime's realtime spatiotemporal blue-noise lookup table is optimized with EA's
BSD-3-Clause FastNoise implementation. See `FASTNOISE-NOTICE.txt`.

Prime's night sky uses NASA Scientific Visualization Studio's Deep Star Maps
2020 with Gaia DR2 data. See `NASA-DEEP-STAR-MAPS-2020-NOTICE.md` for the
source, lossless repacking details, and requested attribution.
