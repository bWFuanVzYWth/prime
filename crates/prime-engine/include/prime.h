#ifndef PRIME_PT_H
#define PRIME_PT_H
#include <stdint.h>
#define PRIME_ABI_VERSION 8
#define PRIME_MAX_BATCH_BYTES 268435456
#define PRIME_HOST_OMM 1
#define PRIME_HOST_STREAMLINE 2
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
int32_t prime_attach_vulkan(uint64_t handle, const PrimeVulkanHost *host);
int32_t prime_configure(uint64_t handle, const PrimeSettings *settings);
int32_t prime_prepare_resources(uint64_t handle, const PrimePrepareResources *prepare);
int32_t prime_record(uint64_t handle, const PrimeFrame *frame, const PrimeRecordTarget *target);
int32_t prime_render(uint64_t handle, const PrimeFrame *frame, uint8_t *rgba, uint64_t capacity);
uint64_t prime_gpu_time(uint64_t handle);
uint64_t prime_cpu_diagnostics(uint64_t handle, uint8_t *output, uint64_t capacity);
int32_t prime_destroy(uint64_t handle);
uint64_t prime_last_error(uint8_t *output, uint64_t capacity);
/* Present may run on the host present thread; bridge serializes SDK access. */
int32_t prime_streamline_present(uint64_t queue, uint64_t present_info);
#include "prime_mc.h"
#ifdef __cplusplus
}
#endif
#endif
