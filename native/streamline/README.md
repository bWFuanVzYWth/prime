# Streamline Vulkan bridge

`prime_streamline.cpp` builds as a Windows x86-64 C++17 static library linked into
`prime_engine.dll`. Include `third_party/streamline/include` and
`third_party/streamline/vulkan-headers/include`; SL, NGX and Vulkan import libraries
are unnecessary. Runtime loading is restricted to the engine DLL's directory.
OTA and downloaded plugins are disabled. The lock pins Streamline 2.14.1 and
DLSS 310.9.1, with hashes and NVIDIA signatures checked by
`scripts/fetch-streamline-sdk.ps1 -VerifyOnly`.

Nine runtime DLLs are packaged: `sl.interposer.dll`, `sl.common.dll`,
`sl.dlss_d.dll`, `nvngx_dlssd.dll`, `sl.dlss_g.dll`, `sl.pcl.dll`, `sl.reflex.dll`,
`nvngx_dlssg.dll` and `NvLowLatencyVk.dll`, alongside `prime_engine.dll`.

## Initialization and requirements

Production initializes the process-owned SDK before Minecraft creates Vulkan
instances, devices and Win32 surfaces. RR, FG, PCL and Reflex are requested at
bootstrap; actual adapter support is checked later. LWJGL loads the interposer
for creation, queue and presentation calls. This path requires Vulkan 1.3 and
explicitly enables core `privateData` for SDK object tracking. Surface creation
uses the real HWND through the proxy; FG swapchains require transfer-source and
color-attachment usage. The loaded proxy remains alive through device destruction.

Proxy device creation initializes plugins and the additional FG queues. It must
not be followed by a second `slSetVulkanInfo`: the pinned `sl_helpers_vk.h`
declares that function for manual hooking only. The separate late-attachment
path supports RR alone on an existing Vulkan 1.2 device, calls `slSetVulkanInfo`,
and invokes common Present callbacks manually. It cannot supply FG's early
creation and queue hooks.

Both paths enable `eUseFrameBasedResourceTagging` during `slInit`. The pinned
SDK requires it for frame-scoped tags; a successful `slSetTagForFrame` call
alone does not establish this mode. RR evaluate tags and FG Present tags use
their real frame token and viewport. Clearing the latest FG frame must not
clear another token's resources. Manual hooking remains exclusive to late RR.

Both RR paths require `VK_NVX_binary_import`, `VK_NVX_image_view_handle`,
`VK_KHR_push_descriptor`, core/KHR buffer device address, Vulkan 1.2
`timelineSemaphore`, `descriptorIndexing`, `bufferDeviceAddress`,
`shaderStorageImageExtendedFormats` for RG16F/R16F guides, and
`shaderStorageImageWriteWithoutFormat` for the SDK clear shader. Enable
`synchronization2` through KHR on Vulkan 1.2 or core support on 1.3. Exclude the
obsolete `VK_EXT_buffer_device_address`. Required instance properties and
external-memory/semaphore capabilities are core in 1.1. RR alone needs no extra
queue. Missing capabilities and initialization failures are explicit errors.

## RR inputs and history

`configure` queries the optimal input extent for DLAA, Quality, Balanced,
Performance or UltraPerformance, with all six preset slots explicitly F. Previous
evaluations must be complete. It frees the old RR feature before changing extent,
avoiding the SDK's fixed-Present-count resize retirement path.

The private bridge ABI remains version 2. `PrimeSlFrame` is 904 bytes, with nine
48-byte image descriptors starting at byte 472. It consumes noisy linear HDR
BT.709 RGBA16F, positive view-space R32F depth, primary/specular RG16F motion,
world-space normal plus linear roughness RGBA16F, diffuse/specular albedo RGBA16F,
and RGBA16F output. Input matches the queried extent; output matches display
extent. Noisy alpha stores actual initial-camera foreground coverage and
`enableAlphaUpscaling` is enabled. Required specular motion is tagged as
`kBufferTypeSpecularMotionVectors`; optional R16F hit-distance is engine post
input and is never tagged alongside that path.

Motion is top-left previous-minus-current displacement in input pixels,
including camera motion and excluding jitter. Reciprocal render dimensions
produce NGX unit pixel scale. Matrices are row-major, multiply row vectors and
exclude jitter. Jitter is projection displacement in input pixels, the negative
of the tracer's pixel-center sample offset; Rust converts it once.

Stable instances with exact ordered local positions use their last accepted
3x4 placement and the same triangle/barycentric point. Baked local translations
reuse the old first-vertex normalization: normalized positions must match bitwise
and adding each mesh's first vertex back must recover its source positions
bitwise. The resulting offset is merged with the accepted affine pose and scene
anchor in f64 before writing the existing previous 3x4 matrix. No extra GPU field
or geometry copy is needed; FP32 transform/projection precision still applies.
History advances after real queue acceptance, rather than recording or
`encoder.execute`. Source-owned CustomGeometry instances use the actual complete
submit schedule and the ranges from one real callback emission; their baked
camera-relative geometry uses the capture camera origin and identity affine.
See the [source contract](../../docs/capture-boundaries.md#具名-customgeometry-来源)
for duplicate scheduling, flush and typed-domain fallback. New identities,
failed normalization, anonymous raw captures, standard particle batches and
local deformation remain motion-unknown, using finite placeholders and the
engine's raw fallback mask. Anonymous raw batches lack real owner/submission/part
correspondence. Moving optical interfaces remain unsupported. This does not
establish that all moving content has been denoising-validated in the game.

Sampling sequence numbers are independent of SDK logical frames. Manual RR
asks `slGetNewFrameToken` to advance its internal frame counter for every
evaluation; repeating zero, a sequence gap or sampling-counter wrap does not
reuse an SDK token or request history reset. The interposed path continues to
share the token created by the real host `prime_sl_frame` boundary with RR/FG.

Descriptor memory is borrowed through `evaluate`; images and views remain alive
through host GPU completion. Tagged resources enter and leave GENERAL. Tags are
valid through evaluation and require no extra volatile-tag copy. SDK
`ScopedTasks` restores input states on ordinary returns. Result `-100` means an
SDK exception: do not submit or sample the partial recording. Ordinary errors
can display unchanged noisy input for that frame. Synchronous SDK error logs
are checked because not every NGX failure reaches `sl::Result`.

## Frame generation and Present

FG is opt-in, defaults off, and requires Realtime RR, a supported adapter and
compatible actual swapchain metadata. Raw and Offline modes do not silently
enable RR. The SDK default of one generated frame and UI recomposition is used.
`PrimeSlFgFrame` is 216 bytes and references RR's camera constants.
`slSetConstants` runs once per logical frame/viewport; differing RR/FG constants
are rejected because the SDK rejects duplicate constants.

FG tags four distinct GENERAL images: first-visible normalized device R32F
depth, first-visible RG16F pixel motion, actual postprocessed HUD-less color,
and R8_UNORM UI coverage. Visible guides come from the physical camera hit,
independently of RR's virtual PSR terminal guides. HDR HUD-less color is linear
FP16 scRGB; SDR HUD-less color is RGBA8 with SDR postprocessing and Present
orientation. Actual backbuffer format may be RGBA8, BGRA8 or FP16: the SDK exposes
separate color-buffer and HUD-less format fields. Missing support or too-small
extent returns `+1` before new tags; SDK failures remain errors. SDK `eErrorIO`
(also numerically 1) maps to `-101`, including errors after partial tags or during
unsupported-extent retirement. Requested, capable and per-frame prepared state
are distinct engine diagnostics.

FG uses `eValidUntilPresent` and default `eBlockPresentingClientQueue` on the
graphics/presenting queue. Ordered steady reuse relies on that SDK contract.
Resize, quality/mode changes, HDR calibration changes, world reset and shutdown
first wait for the SDK's published
`lastPresentInputsProcessingCompletionFenceValue`. Its Vulkan fence is a timeline
semaphore. Then FG turns off, its four tags are cleared and feature resources are
freed. Host world completion alone does not prove the private Present consumer
is finished. Failed SDK waits or uncertain submission retain the native owner
and quarantine images. The engine never accesses or manages NGX private images.

Host actions delimit simulation, Reflex pacing, render submission and actual
Present. Interposed presentation invokes hooks once, including when FG is
inactive. Java binds the process-owned native Present entry during bootstrap,
so title/loading frames do not require a world or RR owner. Native caches the
interposer's exported Present function; FG frames also use PCL Present markers. Late RR attachment
uses resolved common before/after callbacks around one driver call. No synthetic
Present advances SDK bookkeeping.

The pinned interposer overwrites the driver's aggregate result in after hooks.
Loaded DLSS-G can take over Present asynchronously even when FG is off. That
path receives the original borrowed descriptor: synchronous `pResults` writes
are not required, and no stack scratch result is injected. After host device
creation, the first Present registers `DLSSGOptions::onErrorCallback` once in
OFF mode; subsequent ON/OFF options preserve the callback. It stores reported
underlying Present/acquire results in a process-owned atomic without taking the
API mutex or referencing a world context. Native merges SDK status with pending
errors received before/during the call. Later callbacks are delivered on a later
Present; without frame identity, they are not attributed to a particular frame.
Device loss has highest priority, then the first negative result, then a reported
nonzero status. Each accepted call invokes the interposer once and adds no GPU wait.
When the caller already supplies `pResults`, a written synchronous result also
participates in that merge. If the SDK leaves it unwritten, the bridge publishes
the merged SDK acceptance/error status there, without claiming physical completion.

Only `eErrorFeatureMissing` establishes absence of FG hooks in the pinned SDK.
That synchronous branch uses one local descriptor copy with scratch `pResults`
to recover the driver result; an unwritten result remains an explicit failure.
Other callback-registration failures do not imply synchronous presentation and
are reported as initialization failure. A successful asynchronous SDK return
does not prove that the physical Present has completed. Actual FG Present, UI quality,
pacing and resize remain manual game acceptance; no-window mocks do not prove
these outcomes.

## Verification and unresolved synchronization

Run production argument/dispatch contracts without a GPU or window:

```powershell
.\scripts\test-streamline-bridge.ps1
```

Tests cover RR mode/preset/alpha and guides, one-time shared constants, FG
formats/tags/options, Reflex/PCL markers, public-fence timeout retention, tag
clearing, unavailable extents, all seven SDK I/O failure boundaries, host shutdown
and actual Present result precedence. Production bridge dispatch additionally
checks that repeated/gapped/wrapping sample indices generate fresh manual SDK
tokens, while interposed RR reuses its real host token without duplicate
constants. They do not prove runtime quality or game
performance. Startup tests additionally cover the title path without a world,
unwritten asynchronous results, cross-thread delayed errors, callback retention
through ON/OFF, and the strictly gated synchronous fallback. The interposed
initialization-only fixture registers the real SDK callback before RR creation;
it creates no window and does not execute the DLSS model or actual Present.

The real SDK fixture requires NVIDIA RTX and Khronos validation. It evaluates
960×540 to native 1920×1080 Performance/F, then checks every RGB half after actual
GPU completion. It creates no window or fake Present:

```powershell
.\scripts\test-streamline-gpu.ps1 -Interposed -ApiVersion 1.3
.\scripts\test-streamline-gpu.ps1
.\scripts\test-streamline-gpu.ps1 -InitializationOnly
```

Synchronization validation uses the documented `VK_LAYER_VALIDATE_SYNC=1`
setting in the interposed child process; manual hooking uses
`VkValidationFeaturesEXT`. The interposer rejects layer-only
`VK_EXT_validation_features` during driver-extension filtering. Errors always
fail the executable. The default callback returns `VK_TRUE` for errors, matching
the host; `-ReportOnlyValidation` returns `VK_FALSE` for comparison but still
fails on every error. Other probes include `-OmitWriteWithoutFormat`,
`-OmitSynchronization2` and the optional-input `-OmitSpecularDistance`.
Logs, runtime hashes and configuration go to `artifacts/streamline-gpu` or a fresh
`-Output` directory.

The locked runtime still reports **two WRITE_AFTER_WRITE errors** from private
`nv.ngx.dlssd.resource` image layout transitions to `vkCmdClearColorImage`.
Its barrier lacks CLEAR/TRANSFER_WRITE scope. Late manual attachment, the old
signed 310.7.128 runtime, and early interposed creation reproduce the pair.
The final interposed comparison has no other validation warnings, successful
initialization/evaluate/submit/completion/cleanup and all 6,220,800 RGB halves
finite and nonzero, but exits 6 and remains a strict failure. Vulkan 1.2 KHR sync2
and Vulkan 1.3 core sync2 remove separate same-layout errors, not these hazards.
Do not suppress reports or treat successful readback as complete validation.
A validated SDK/driver or official integration correction remains required.
