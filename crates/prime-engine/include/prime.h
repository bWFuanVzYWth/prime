#ifndef PRIME_PT_H
#define PRIME_PT_H
#include <stdint.h>
#define PRIME_ABI_VERSION 12
#define PRIME_MAX_BATCH_BYTES 268435456
#define PRIME_DIAGNOSTICS_ENABLED 1
#define PRIME_DIAGNOSTICS_CAPTURE 2
#define PRIME_HOST_OMM 1
#define PRIME_HOST_STREAMLINE 2
#define PRIME_STREAMLINE_BEGIN_FRAME 0
#define PRIME_STREAMLINE_RENDER_START 1
#define PRIME_STREAMLINE_RENDER_END 2
#define PRIME_STREAMLINE_SUSPEND 3
#define PRIME_STREAMLINE_HOST_SHUTDOWN 4
#ifdef __cplusplus
extern "C" {
#endif

/* 64-bit naturally aligned C POD. All handle operations run on the creating OS
 * thread. Input spans are borrowed until synchronous return; retained data is
 * native-owned. A structure's padding is never part of content identity. */
typedef struct PrimeHeader {
    uint32_t struct_size;
    uint32_t abi_version;
} PrimeHeader;

typedef struct PrimeByteSpan {
    const uint8_t *data;
    uint64_t count;
} PrimeByteSpan;

typedef struct PrimeU32Span {
    const uint32_t *data;
    uint64_t count;
} PrimeU32Span;

typedef struct PrimeU64Span {
    const uint64_t *data;
    uint64_t count;
} PrimeU64Span;

typedef struct PrimeReset {
    PrimeHeader header;
    uint64_t epoch;
} PrimeReset;

typedef struct PrimeFrame {
    PrimeHeader header;
    uint64_t epoch;
    double position[3];
    float forward[3];
    float right[3];
    float up[3];
    float fov_y;
    uint32_t width;
    uint32_t height;
    uint32_t sample_index;
    float solar_hour_angle;
} PrimeFrame;

typedef struct PrimeSettings {
    PrimeHeader header;
    uint32_t mode;
    uint32_t bounces;
    uint32_t offline_samples;
    float exposure;
    float hue;
    float saturation;
    uint32_t view;
    float sun;
    float sky;
    float depth_range;
    uint32_t seed;
    int32_t latitude_degrees;
    uint32_t solar_longitude_degrees;
    uint32_t opacity_micromap;
    uint32_t ray_reconstruction;
    uint32_t reconstruction_quality;
    uint32_t terrain_batches_per_frame;
    float stars;
    float auto_exposure_compensation;
    uint32_t hdr;
    uint32_t hdr_reference_white;
    uint32_t frame_generation;
    /* Frame-boundary choice: 0 = grid, 1 = power tree, 2 = bounds-sphere tree. */
    uint32_t light_sampling;
    /* Independent integrator: 0 = path trace, 1 = ReSTIR PT Enhanced. */
    uint32_t integrator;
} PrimeSettings;

typedef struct PrimeVulkanHost {
    PrimeHeader header;
    uint64_t instance;
    uint64_t physical_device;
    uint64_t device;
    uint64_t queue;
    uint64_t timeline;
    uint32_t queue_family;
    uint32_t capabilities;
} PrimeVulkanHost;

typedef struct PrimeRecordTarget {
    PrimeHeader header;
    uint64_t command;
    uint64_t image;
    uint64_t view;
    uint64_t serial;
} PrimeRecordTarget;

/* Actual selected HDR surface and absolute display calibration. Inactive ignores
 * nits. The host supplies valid Windows HDR + FP16 extended-linear-sRGB evidence. */
typedef struct PrimeDisplayOutput {
    PrimeHeader header;
    uint32_t active;
    float peak_nits;
    float system_white_nits;
    uint32_t reserved;
} PrimeDisplayOutput;

/* Record final HDR UI composition on the attached graphics queue. Both borrowed
 * images are GENERAL; output is RGBA16_SFLOAT STORAGE, UI is sampled RGBA8_UNORM.
 * Host retains both through serial completion and restores present layout itself. */
typedef struct PrimeHdrTarget {
    PrimeHeader header;
    uint64_t command;
    uint64_t ui_image;
    uint64_t ui_view;
    uint64_t output_image;
    uint64_t output_view;
    uint64_t serial;
    uint32_t width;
    uint32_t height;
} PrimeHdrTarget;

/* Record resource-only work into an already active host encoder. The caller
 * submits it in queue order and signals this real completion serial. */
typedef struct PrimePrepareResources {
    PrimeHeader header;
    uint64_t command;
    uint64_t serial;
} PrimePrepareResources;

typedef struct PrimeTextureSource {
    uint32_t id;
    uint32_t width;
    uint32_t height;
    uint32_t reserved;
    PrimeByteSpan rgba;
} PrimeTextureSource;

typedef struct PrimeTextureBatch {
    PrimeHeader header;
    uint64_t epoch;
    const PrimeTextureSource *textures;
    uint64_t count;
} PrimeTextureBatch;

typedef struct PrimeTextureRetire {
    PrimeHeader header;
    uint64_t epoch;
    PrimeU32Span ids;
} PrimeTextureRetire;

/* Source primitive layout, never expanded by Java. Vertex bytes are the actual
 * caller-authored layout including declared stride; no C padding is compared. */
typedef struct PrimeMeshSpan {
    uint32_t texture_id;
    uint32_t flags;
    uint32_t topology;
    uint32_t vertex_count;
    uint32_t stride;
    uint32_t position_offset;
    uint32_t color_offset;
    uint32_t uv_offset;
    PrimeByteSpan vertices;
} PrimeMeshSpan;

/* topology=1 has an array of these exact source parameter records, stride52. */
typedef struct PrimeBillboard {
    float position[3];
    float quaternion[4];
    float scale;
    float uv[4];
    uint32_t rgba;
} PrimeBillboard;

typedef struct PrimeDynamicBatch {
    PrimeHeader header;
    uint64_t epoch;
    uint64_t sequence;
    double origin[3];
    const PrimeMeshSpan *spans;
    uint64_t count;
} PrimeDynamicBatch;

typedef struct PrimePrototypeSource {
    uint64_t id;
    uint64_t revision;
    const PrimeMeshSpan *spans;
    uint64_t count;
} PrimePrototypeSource;

typedef struct PrimeRemoval {
    uint64_t id;
    uint64_t revision;
} PrimeRemoval;

typedef struct PrimeInstanceSource {
    uint64_t id;
    uint64_t revision;
    uint64_t prototype_id;
    double origin[3];
    float transform[12];
    uint32_t texture_id;
    uint32_t flags;
    uint32_t rgba;
    uint32_t reserved;
    float uv_transform[4];
} PrimeInstanceSource;

typedef struct PrimeInstanceBatch {
    PrimeHeader header;
    uint64_t epoch;
    uint64_t sequence;
    const PrimePrototypeSource *prototypes;
    uint64_t prototype_count;
    const PrimeRemoval *prototype_removals;
    uint64_t prototype_removal_count;
    const PrimeInstanceSource *instances;
    uint64_t instance_count;
    const PrimeRemoval *instance_removals;
    uint64_t instance_removal_count;
} PrimeInstanceBatch;

uint32_t prime_abi_version(void);
uint64_t prime_create(uint32_t abi_version);
/* World boundary: thaw offline state and submit/complete recorded host work first.
 * Retires world geometry/history while retaining resource-generation and device assets. */
int32_t prime_reset(uint64_t handle, const PrimeReset *reset);
int32_t prime_textures(uint64_t handle, const PrimeTextureBatch *batch);
int32_t prime_retire_textures(uint64_t handle, const PrimeTextureRetire *batch);
int32_t prime_dynamic(uint64_t handle, const PrimeDynamicBatch *batch);
int32_t prime_instances(uint64_t handle, const PrimeInstanceBatch *batch);
/* The host must prove accelerationStructure, rayQuery, bufferDeviceAddress,
 * scalarBlockLayout and timelineSemaphore were enabled on this logical device.
 * Physical-device support alone is insufficient; native cannot enable borrowed features. */
int32_t prime_attach_vulkan(uint64_t handle, const PrimeVulkanHost *host);
int32_t prime_configure(uint64_t handle, const PrimeSettings *settings);
int32_t prime_prepare_resources(uint64_t handle, const PrimePrepareResources *prepare);
int32_t prime_record(uint64_t handle, const PrimeFrame *frame, const PrimeRecordTarget *target);
/* Only after the host queue accepted the matching recorded submission. This
 * advances CPU temporal identities; it does not prove GPU completion. */
int32_t prime_submission_accepted(uint64_t handle, uint64_t serial);
int32_t prime_display_output(uint64_t handle, const PrimeDisplayOutput *display);
int32_t prime_present_hdr(uint64_t handle, const PrimeHdrTarget *target);
/* After HUD drawing, and after prime_present_hdr when HDR is active. Returns 0
 * when prepared, 1 when unavailable/disabled, -1 on failure. Target uses the same
 * submission and actual present extent; formats are Vulkan VkFormat values. */
int32_t prime_prepare_frame_generation(uint64_t handle, const PrimeHdrTarget *target,
                                       uint32_t back_buffer_count, uint32_t back_buffer_format);
/* Lightweight fallback owner for SDR UI/vanilla -> calibrated scRGB, no PT assets. */
uint64_t prime_hdr_surface_create(const PrimeVulkanHost *host);
int32_t prime_hdr_surface_record(uint64_t handle, const PrimeHdrTarget *target,
                                 const PrimeDisplayOutput *display);
int32_t prime_hdr_surface_destroy(uint64_t handle);
int32_t prime_render(uint64_t handle, const PrimeFrame *frame, uint8_t *rgba, uint64_t capacity);
uint64_t prime_gpu_time(uint64_t handle);
uint64_t prime_cpu_diagnostics(uint64_t handle, uint8_t *output, uint64_t capacity);
/* Owner-thread controls. Capture implies diagnostics. Stopping preserves a final
 * data chunk; it never waits for unsubmitted GPU work. Unknown flag bits fail. */
int32_t prime_diagnostics_configure(uint64_t handle, uint32_t flags);
/* Correlates subsequent source/CPU tasks with the logical host frame. */
int32_t prime_diagnostics_frame(uint64_t handle, uint64_t frame_id);
/* Native monotonic ns relative to the capture origin, retained through final read.
 * Java brackets this call with nanoTime; GPU tick clocks stay independent. */
uint64_t prime_diagnostics_clock(uint64_t handle);
/* Drains a compact UTF-8 JSON chunk. Zero capacity prepares/caches a chunk and
 * returns length excluding NUL. Short copies retain it; a full NUL-terminated
 * copy consumes it. Zero length means no capture data; u64::MAX means error.
 * Pointers are borrowed only through return. Call once per captured frame and
 * after stopping; never treat a pending GPU event as a measured zero duration. */
uint64_t prime_diagnostics_read(uint64_t handle, uint8_t *output, uint64_t capacity);
int32_t prime_destroy(uint64_t handle);
uint64_t prime_last_error(uint8_t *output, uint64_t capacity);
/* Present may run on the host present thread; bridge serializes SDK access. */
int32_t prime_streamline_present(uint64_t queue, uint64_t present_info);
/* Before any Vulkan loader/instance; installs the process Streamline interposer. */
int32_t prime_streamline_bootstrap(void);
/* Logical-frame markers, render-thread-only. Suspend also proves the SDK's
 * published FG input-consumer completion before guide reconfiguration/release. */
int32_t prime_streamline_frame(uint32_t action, uint32_t enabled);
#include "prime_mc.h"
#ifdef __cplusplus
}
#endif
#endif
