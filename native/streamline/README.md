# Streamline Vulkan bridge

`prime_streamline.cpp` builds as a Windows x86-64 C++17 static library, linked into
`prime_engine.dll`. Include `third_party/streamline/include` and
`third_party/streamline/vulkan-headers/include`; no SL import library, NGX static
library or Vulkan loader import library is required. The four production runtime
DLLs are loaded only from the engine DLL's directory. OTA and downloaded plugin
loading are disabled, preserving the checked-in SDK lock.

The C ABI in `prime_streamline.h` owns one process-global Streamline context and
viewport. Rust owns every tagged image and all command submission/completion.
The bridge does not create or submit command buffers, wait for the GPU, or replace
the host's Vulkan device. All bridge operations, including presentation, are
serialized by one mutex. GPU execution remains asynchronous.

Before attachment, the host enables `VK_NVX_binary_import`,
`VK_NVX_image_view_handle`, `VK_KHR_push_descriptor` and the core/KHR buffer device
address capability. Vulkan 1.2 `timelineSemaphore`, `descriptorIndexing` and
`bufferDeviceAddress`, plus core `shaderStorageImageExtendedFormats` for the
RG16F/R16F storage-image guides and `shaderStorageImageWriteWithoutFormat` for
Streamline's own clear shader, must be enabled. The Vulkan 1.2 host also enables
`VK_KHR_synchronization2` and its `synchronization2` feature. The real SDK fixture
demonstrates that NGX's same-layout barriers require its layout semantics to avoid
two internal image layout errors; no internal image is modified by the bridge.
The obsolete `VK_EXT_buffer_device_address`
is excluded, matching Streamline's own conflict removal. The required instance
capabilities (`get_physical_device_properties2`, external memory and semaphore
capabilities) are core in Vulkan 1.1. RR requires no additional queues. Unsupported
hardware and initialization failures are reported before RR resources are used.

`configure` queries the SDK's optimal input resolution for DLAA, Quality, Balanced,
Performance or UltraPerformance. All six preset slots are explicitly F. It must
run after GPU completion of previous evaluations; it explicitly frees the old RR
feature before configuring a new extent, avoiding the SDK's resize path that
otherwise retires the previous NGX feature after a fixed number of presents.

ABI v2 `evaluate` consumes noisy linear HDR BT.709 RGBA16F, positive view-space depth
R32F, dense primary and specular motion RG16F, world-space normal plus linear
roughness RGBA16F, diffuse/specular albedo RGBA16F and output RGBA16F. Input guides
match the queried input extent; output matches the requested display extent.
Both motion fields store top-left previous-minus-current displacement in input
pixels. The bridge supplies reciprocal render dimensions as Streamline's scale,
which the plugin multiplies by that extent to give NGX unit scale. Specular motion
is tagged as `kBufferTypeSpecularMotionVectors`, the DLSSD `GBuffer.SpecularMvec`
input. The hit-distance R16F descriptor is retained for the engine's post input
and can be absent; it is never tagged alongside the explicit specular-motion path.
The ABI frame is 904 bytes and appends specular motion at image index 8. Matrices use
Streamline's row-major row-vector convention and omit jitter. ABI jitter is the
projection displacement in input pixel units: each component is the negative of
the tracer's pixel-center sample offset. Rust performs this conversion once;
the bridge forwards it unchanged to Streamline/NGX. Motion vectors include camera
motion and exclude jitter. Unknown motion uses finite placeholders and the engine's
raw fallback mask; the unused DLSSD `motionVectorsInvalidValue` constant does not
establish per-pixel SDK history rejection.

All image states enter and leave as GENERAL. Local tags are valid through the
evaluate call and cause no extra volatile-tag copies. GPU resources remain alive
until host completion, including after a returned evaluation failure. The pinned
SDK wraps its input transitions in `ScopedTasks` and restores them on ordinary
returns. Result `-100` identifies an SDK exception: the partial recording must not
be submitted or sampled. Ordinary errors can use the unchanged noisy input for
the current frame. Synchronous SDK error logs are also checked because this SDK's
DLSS plugin does not propagate every NGX evaluation failure through `sl::Result`.

The host routes each **real** `vkQueuePresentKHR` call through `prime_sl_present`.
Only the attached queue invokes `sl.common`'s before/after-present callbacks,
resolved with `slGetFeatureFunction`; the native loader performs the actual
present exactly once. The callback signatures are pinned to Streamline 2.14.1.
The bridge preserves the native VkResult, including suboptimal/out-of-date;
it deliberately does not use this SDK's interposer wrapper, whose after-hook result
overwrites the original present result. No fake presents advance bookkeeping.
Present is required by the SDK even with direct evaluate tags.

Run the production argument/dispatch contract tests without a GPU or window:

```powershell
.\scripts\test-streamline-bridge.ps1
```

These tests cover mode/preset selection, guide tagging and validation, error
retention and cleanup, and present result preservation. They do not establish
runtime image quality, GPU synchronization correctness or game performance.

The separate real SDK test requires NVIDIA RTX and the Khronos validation layer,
creates an isolated Vulkan 1.2 device with the RR feature prerequisites, and
evaluates preset F from 960×540 to native 1920×1080 without a window:

```powershell
.\scripts\test-streamline-gpu.ps1
.\scripts\test-streamline-gpu.ps1 -InitializationOnly
# Negative regression reproducing the missing feature in the original host setup:
.\scripts\test-streamline-gpu.ps1 -InitializationOnly -OmitWriteWithoutFormat -AbortOnValidationError
```

The output is prefilled with NaNs; after GPU completion every RGB half must be
finite and nonzero for the constant lit-plane fixture. Shader submission, readback
and teardown statuses are checked, and any validation error fails the executable.
Synchronization validation is enabled explicitly with `VkValidationFeaturesEXT`.
The callback returns `VK_TRUE` for errors by default, matching the Minecraft host;
`-ReportOnlyValidation` is a diagnostic comparison that returns `VK_FALSE` but
still fails the test on any validation error. `-OmitSpecularDistance` tests the
documented optional-input path. `synchronization2` is enabled by default via KHR
on Vulkan 1.2 or as a core feature on 1.3; `-OmitSynchronization2` retains the
negative layout regression.
No fake Present is issued: this is a single-evaluation test, not a temporal or
presentation lifecycle test. `-ApiVersion 1.3` adds the SDK's core `privateData`
requirement for a separate API comparison. All logs and runtime hashes go to a
new directory under `artifacts/streamline-gpu`.

The pinned SDK currently reproduces two WRITE_AFTER_WRITE synchronization errors
while clearing its internal `nv.ngx.dlssd.resource` images, despite successful
API results and finite, fully written RGB output. Without `synchronization2`,
two more internal RG16F image layout errors appear. Vulkan 1.2 plus KHR sync2
and Vulkan 1.3 plus core sync2 both eliminate those layout reports; neither fixes
the clear-image synchronization errors. A direct NGX comparison with the same
runtime reproduced all four errors without sync2, with both real and null Vulkan
procedure callbacks. The strict test remains failing until the two remaining
errors are resolved. Do not suppress errors or treat the successful readback
as complete Vulkan validation.
