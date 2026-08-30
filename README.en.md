# Prime

[简体中文](README.md) | [English](README.en.md)

Prime is a client-side renderer mod for Minecraft 26.2. It redraws the game world with Vulkan
hardware ray tracing so sunlight, the sky, emissive blocks, glass, water, and ordinary materials
participate in the same light transport.

Prime primarily supports compatible NVIDIA RTX GPUs and uses DLSS Ray Reconstruction for the best
experience. Other GPUs that meet the Vulkan ray-tracing requirements are supported on a
compatibility basis and use NRD with FSR for realtime denoising and reconstruction.

> Prime is still in early development. The current version may have visual errors, missing effects,
> performance problems, crashes, and mod or resource-pack incompatibilities. Prime is a client-only
> mod and is not intended to modify saves, but keeping backups of important worlds is still
> recommended.

[Download a release](https://github.com/bWFuanVzYWth/prime/releases) ·
[Report an issue](https://github.com/bWFuanVzYWth/prime/issues) ·
[Known issues](docs/FIXME.md)

## What Prime Is

Prime takes over Minecraft's world rendering and traces rays against actual block, entity, and
material geometry. Emissive blocks can illuminate their surroundings, sunlight and the sky produce
direct and indirect lighting, and glass and water refract and absorb light over distance.

Prime is not an Iris or OptiFine shader pack and cannot be installed in the `shaderpacks` folder.
Many existing shader packs use rasterization, screen-space effects, or software voxel ray tracing,
and commonly support a wider range of platforms, versions, and effects. Prime instead uses Vulkan
hardware ray tracing; these approaches have different compatibility, performance, and visual
tradeoffs.

## Highlights

- **Hardware path-traced lighting:** sunlight, the sky, and emissive blocks can illuminate the scene
  directly and indirectly, transferring color and brightness between materials.
- **Astronomical sky:** the sun path follows the observer latitude and season, with atmospheric
  rendering, a day-night cycle, and a Gaia star map.
- **Transparent materials and volume absorption:** glass and water refract light, while colored media
  absorb different wavelengths over distance.
- **PBR materials:** partial LabPBR 1.3 support includes roughness, metals, dielectric reflectance,
  and emission.
- **Dynamic scenes:** terrain, entities, block entities, moving blocks, and text can enter the
  path-traced scene.
- **Realtime denoising and reconstruction:** NVIDIA DLSS Ray Reconstruction is the primary path, with
  NRD + FSR available for other compatible GPUs.
- **SDR and HDR output:** both use the same Reinhard-Gamut display transform. HDR queries the active
  display's brightness capabilities, extends highlights through linear scRGB, and supports automatic
  or manual reference-white calibration.
- **Offline Rendering Mode:** freezes the scene and accumulates raw, undenoised samples at native
  resolution. It preserves more detail, avoids realtime reconstruction artifacts, and targets a
  higher-quality final result, but usually looks noisier than the realtime image and needs longer
  to converge.
- **Texture displacement:** an experimental option turns material height data into real surface
  relief at additional scene-building and memory cost.
- **Vanilla fallback:** Prime can be disabled in game without exiting when compatibility or
  performance is a problem.

## Requirements

| Item | Requirement |
| --- | --- |
| Operating system | 64-bit Windows (x86-64) |
| Minecraft | 26.2 |
| Mod loader | Fabric Loader 0.19.3 or newer |
| Dependency | Fabric API for Minecraft 26.2 |
| Java | 25 |
| Primary GPU support | NVIDIA RTX GPU with Vulkan ray tracing and DLSS RR support |
| Compatibility GPU support | Other GPUs with Vulkan KHR ray tracing pipeline and acceleration structure support |

A compatible NVIDIA RTX GPU with a current stable driver is recommended. It receives Prime's most
thoroughly validated path and the best current experience. Other compatible GPUs use NRD + FSR;
functionality and visual stability may vary by device and driver.

Players installing a release do not need the Vulkan SDK, a compiler, or other development tools.
The release JAR includes the Windows native libraries needed at runtime. Prime requires
substantially more GPU time, video memory, and CPU scene streaming than vanilla Minecraft.

## Installation

1. Install and launch Minecraft 26.2 at least once.
2. Use the [official Fabric installer](https://fabricmc.net/use/installer/) to install Fabric Loader
   0.19.3 or newer for Minecraft 26.2.
3. Download [Fabric API for Minecraft 26.2](https://modrinth.com/mod/fabric-api/versions) and a
   [Prime release](https://github.com/bWFuanVzYWth/prime/releases). Both downloads should be `.jar`
   files; do not extract them.
4. Open the game folder for the Fabric instance in your launcher. Create a `mods` folder there if it
   does not already exist.
5. Put both the Fabric API and Prime JARs in that `mods` folder.
6. Select the new Fabric instance in the launcher and start the game.
7. Select the Vulkan backend in Graphics Settings, then restart when prompted.
8. Open Video Settings and look for sections whose names begin with `Prime:`.

If the Prime settings do not appear, first check that you launched the correct Fabric instance, that
its Minecraft version is 26.2, and that Fabric API and Prime are in the same instance's `mods`
folder.

If Vulkan or a required ray-tracing feature is unavailable, Prime stops taking over the world view
and leaves vanilla rendering active. The log records why Prime could not start. A fatal diagnostic
remains visible for the game session: its title includes the installed Prime version and runtime
state, while its body is limited to four lines containing the root failure and key scene context.

### First Start

The graphics driver may need several minutes to compile rendering pipelines the first time Prime is
enabled, or after a driver or Prime shader update. The game window may temporarily stop responding.
Unless the log has already reported a failure, allow compilation to finish; later starts normally
reuse the driver cache.

## Common Settings

- **Denoising & Image Reconstruction:** compatible RTX GPUs can use DLSS RR; Prime falls back to
  NRD + FSR when it is unavailable.
- **Reconstruction Quality Preset:** defaults to Performance. Raise it when the game is comfortably
  responsive, or lower it when frame rate is insufficient.
- **Additional Specular Bounces:** controls the realtime delta reflection/transmission chain before
  the primary surface, from 1–64; the default is 16.
- **Minimum Bounces:** controls the fixed no-roulette realtime Wavefront rounds, from 1–8; the
  default is 2.
- **Maximum Bounces:** controls regular transport from 1–64; the default is 16. In realtime, values
  below Minimum Bounces do not shorten the fixed Wavefront. Offline uses this total directly.
- **Terrain Worker Share:** defaults to 50% of Minecraft's maximum background workers. Lower it to
  reduce CPU contention while chunks load, or raise it to make Prime geometry appear sooner; at
  least one worker is always retained.
- **Auto Exposure Strength:** defaults to 60% and controls how strongly the image adapts when moving
  between bright and dark areas.
- **Exposure Compensation:** adjust this first when the entire image looks too bright or too dark.
- **HDR Output and Reference White:** available only when the active display and Windows expose
  usable HDR. Reference white uses the system-reported value by default and can be calibrated in
  nits to keep the main image brightness consistent when switching between SDR and HDR.
- **Offline Rendering Mode:** freezes the scene and accumulates raw samples. Press `F2` when the
  image is ready; press `Escape` or `Ctrl+Alt+F2` to exit.

Hover over a Prime setting for a short explanation, adjustment direction, and recommendation. The
complete display, material, astronomy, and diagnostic controls are not duplicated here.

## Resource Packs, Mods, and Shader Packs

Prime builds its ray-tracing scene through Minecraft's model and resource systems, so ordinary
resource packs can be used directly. Packs declaring `format=lab-pbr/1.3` can provide the PBR
material properties that Prime currently supports.

Mods that depend on special world rendering, post-processing, nonstandard model callbacks, or a
custom GPU pipeline cannot be assumed compatible with Prime. Mods limited to UI, inventory
management, or other non-world-rendering features are generally easier to support. Prime cannot use
ordinary shader packs for world rendering at the same time.

## Explicitly Accepted Limitations

The following limits are current design or resource boundaries and are not hidden as pending bug
fixes:

- NRD + FSR cannot reliably denoise transmission behind colored glass and may retain visible noise
  or temporal instability.
- Very high-resolution cutout textures are approximated within a finite subdivision limit, so very
  fine transparent edges may be missing or gain extra coverage.
- At most two non-air transparent regions can be nested reliably. Deeper nesting, open models, and
  boundaries without a meaningful inside and outside do not guarantee correct absorption or
  refraction.
- Realtime transparent lighting keeps conditional transmission and reflection slots at the first
  interface, straight shadow filtering, and bounded single-branch sampling afterward. A radiance
  branch accumulates its guide along the continuation it actually selected; motion, chain overflow,
  depth limits, or invalid state fall back to the true visible interface without an independent
  guide replay. This does not fully solve arbitrary refractive chains.
- Shadow rays connecting a surface to a light do not refract at transparent interfaces. They only
  accumulate absorption along the original direction.
- Volumetric sun shadows approximate visibility from one sun direction; they do not represent sky
  lighting, local lights, or multiple scattering in participating media.

See [Explicitly Accepted Limitations](docs/FIXME.md#明确接受的限制) for the technical boundaries.
The same document records defects that are still intended to be fixed, but does not mix them with
the limits above.

## Reporting Issues

Please include the following in a [GitHub issue](https://github.com/bWFuanVzYWth/prime/issues):

- Prime, Minecraft, Fabric Loader, and Fabric API versions;
- GPU model and driver version;
- resource packs, relevant mods, and reconstruction mode;
- repeatable steps;
- the complete log, plus a screenshot or short video for visual problems.

If a problem appears only with Prime, also check whether it disappears after switching back to
vanilla rendering. The `Prime: Diagnostics` section in Video Settings provides rendering
diagnostics and a link to the GitHub repository. If the log cannot be found, include the complete
persistent Prime error toast in a screenshot; it carries the version and concise failure text
directly, without an error code lookup.

## Building from Source

Regular players should download a release JAR. The tools below are needed only to modify the code,
test the latest commit, or create a custom build.

The build is pinned to JDK 25 and the complete Vulkan SDK 1.4.357.0. Slang and SPIR-V Tools must
come from that SDK; no separate Slang installation is required. After downloading and extracting
the Prime source, open PowerShell in the source directory, adjust these paths, and run:

```powershell
$env:JAVA_HOME = 'C:\WorkSpace\_tools\temurin-25.0.4\jdk-25.0.4+7'
$env:VULKAN_SDK = 'C:\VulkanSDK\1.4.357.0'
$env:Path = "$env:JAVA_HOME\bin;$env:VULKAN_SDK\Bin;$env:Path"
.\gradlew.bat build
```

After a successful build, the sole release JAR is in `build\libs`. Environment checks, tests,
development runs, and shader debugging are documented in
[Build and Validation](docs/构建与验证.md).

## Project Documentation

The technical documents are currently written in Chinese.

### Usage and Overview

- [Known issues and explicitly accepted limitations](docs/FIXME.md)
- [Build and validation](docs/构建与验证.md)
- [Rendering implementation](docs/渲染实现.md)
- [Architecture and data flow](docs/纯函数式架构.md)

### Scene, Resources, and Materials

- [Terrain cluster scene translation](docs/区块簇场景翻译架构.md)
- [Texture translation architecture](docs/纹理翻译架构.md)
- [Canonical material IR and closures](docs/统一材质IR与闭包.md)
- [Compact OpenPBR implementation](docs/OpenPBR紧凑模块.md)

### Light Transport and Display

- [Offline light transport contract](docs/离线光传输契约.md)
- [Transparency and realtime reconstruction](docs/透明渲染与实时重建.md)
- [HDR output](docs/HDR输出.md)

### Engineering Contracts

- [GPU geometry tracing precision contract](docs/GPU几何追踪精度契约.md)
- [Production shader compilation boundaries](docs/生产Shader编译边界契约.md)
- [Zero-cost production shader diagnostics](docs/生产Shader零诊断成本契约.md)
- [Shader property tests and numerical diagnostics](docs/着色器属性测试与数值诊断架构.md)

### Maintenance Records

- [TODO](docs/TODO.md)
- [Oblique-water fine black-line investigation and fix](docs/斜水面细密黑纹排查报告.md)

## Related Open-Source Projects

Prime is not the only open-source project exploring hardware ray tracing for Minecraft. These
projects use their own architectures, feature scopes, and hardware-support strategies and are also
worth following:

- [Caustica](https://github.com/ComfyFluffy/Caustica), a hardware ray-traced renderer for
  Minecraft's Vulkan backend;
- [Radiance / MCVR](https://github.com/Minecraft-Radiance/MCVR), a C++ Vulkan rendering framework
  used by Radiance.

The traditional shader-pack ecosystem also offers many realtime lighting and path-tracing projects,
some based on software voxel ray tracing. Their compatibility goals, hardware range, and usage model
differ from Prime's.

## License and Attribution

Prime-owned code is licensed under the [MIT License](LICENSE). NRD, DLSS, FidelityFX, and RoboCute
components retain their respective licenses; complete texts are in `THIRD_PARTY_LICENSES`.

The night-sky asset comes from
[NASA SVS Deep Star Maps 2020](https://svs.gsfc.nasa.gov/4851/): NASA/Goddard Space Flight Center
Scientific Visualization Studio. Gaia DR2: [ESA/Gaia/DPAC](https://gea.esac.esa.int/archive/documentation/GDR2/Miscellaneous/sec_credit_and_citation_instructions/).
Constellation artwork is based on the version created for the IAU by Alan MacRobert and published
by *Sky and Telescope* (Roger Sinnott and Rick Fienberg). Complete attribution and lossless
repackaging details are in
`THIRD_PARTY_LICENSES/NASA-DEEP-STAR-MAPS-2020-NOTICE.md`.
