#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// ABI 1. All entry points run serially on the renderer thread. Zero means success;
// negative values are bridge errors, positive values are sl::Result errors.
// Configure/destroy require completion of every prior evaluate on this context.
// Streamline is process global: only one live Prime context is supported.
// Evaluate result -100 means SDK exception: do not consume or submit the partial
// recording. Other evaluation errors restore borrowed images to GENERAL.
typedef struct PrimeSlInit {
    uint64_t instance;
    uint64_t physical_device;
    uint64_t device;
    uint32_t queue_family;
    uint32_t queue_index;
} PrimeSlInit;

typedef struct PrimeSlSize {
    uint32_t render_width;
    uint32_t render_height;
} PrimeSlSize;

typedef struct PrimeSlImage {
    uint64_t image;
    uint64_t view;
    uint64_t memory;
    uint32_t width;
    uint32_t height;
    uint32_t format;
    uint32_t layout;
    uint32_t usage;
    uint32_t reserved;
} PrimeSlImage;

enum {
    PRIME_SL_NOISY = 0,
    PRIME_SL_DEPTH = 1,
    PRIME_SL_MOTION = 2,
    PRIME_SL_NORMAL_ROUGHNESS = 3,
    PRIME_SL_DIFFUSE_ALBEDO = 4,
    PRIME_SL_SPECULAR_ALBEDO = 5,
    PRIME_SL_OUTPUT = 6,
    PRIME_SL_SPECULAR_HIT_DISTANCE = 7,
    PRIME_SL_IMAGE_COUNT = 8
};

typedef struct PrimeSlFrame {
    uint64_t command_buffer;
    uint32_t frame_index;
    uint32_t reset;
    float jitter[2];
    float camera_near;
    float camera_far;
    float camera_fov;
    float camera_aspect;
    float camera_position[3];
    float camera_up[3];
    float camera_right[3];
    float camera_forward[3];
    // Row-major matrices multiplying row vectors, without jitter.
    float world_to_view[16];
    float view_to_world[16];
    float view_to_clip[16];
    float clip_to_view[16];
    float clip_to_previous_clip[16];
    float previous_clip_to_clip[16];
    // Images and descriptors are borrowed until evaluate returns. Vulkan images
    // and views remain alive until the host's GPU completion value is reached.
    // All resources are single-mip single-layer color images in GENERAL layout.
    // Hit-distance may be an all-zero descriptor; no fabricated reflection guide.
    PrimeSlImage images[PRIME_SL_IMAGE_COUNT];
} PrimeSlFrame;

uint32_t prime_sl_abi_version(void);
int32_t prime_sl_create(const PrimeSlInit *init, void **output);
// Quality: 0=DLAA, 1=Quality, 2=Balanced, 3=Performance, 4=UltraPerformance.
// Every mode is explicitly configured to DLSS Ray Reconstruction preset F.
int32_t prime_sl_configure(void *context, uint32_t output_width, uint32_t output_height,
                           uint32_t quality, PrimeSlSize *render_size);
int32_t prime_sl_evaluate(void *context, const PrimeSlFrame *frame);
int32_t prime_sl_destroy(void *context);
// Replaces one real vkQueuePresentKHR invocation and preserves its VkResult.
// Safe on the host present thread; serialized with context operations.
int32_t prime_sl_present(uint64_t queue, uint64_t present_info);
// Thread-local diagnostic text; copy before the next bridge call on this thread.
const char *prime_sl_last_error(void);

#ifdef __cplusplus
}
#endif
