#ifndef PRIME_PT_H
#define PRIME_PT_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
// All functions on a handle must run on the same OS thread as prime_create.
// Input storage is borrowed only until return. Output RGBA8 rows start at top left.
// Zero status means success. On -1 call prime_last_error on the same thread.
uint32_t prime_abi_version(void);
uint64_t prime_create(uint32_t abi_version);
// ABI v7. Production terrain uses prime_mc_plan/prime_mc_sections.
// op12/op13 remain detached source/geometry fixtures; op6 supports parametric billboards.
// op8: header[24], section/sequence:u64[2], origin:f64[3], layer_count/reserved:u32[2],
// then each layer's layer/texture/flags/topology/count/stride/position/color/uv/reserved:u32[10]
// and raw vertices. Empty complete sections count toward terrain readiness.
// op9: header[24], count/reserved:u32[2], texture IDs:u32[count]. Owner retirement
// waits for current scene references; IDs 0/1/UINT32_MAX cannot retire.
// op10: header[24], completed_sequence:u64. All earlier producers are finished or
// cancelled and their accepted results already submitted. Old source packets reject.
// op11: header[24], count/reserved:u32[2], then section/sequence:u64[2] per removal.
// Section sequences, raw/instance sequences and GPU completion serials are distinct.
// Scene op6: complete dynamic snapshot in one prime_submit; empty spans clear it.
// Common LE packet header[24], then sequence:u64, origin:f64[3], span_count:u32,
// reserved:u32=0 (64 bytes total). Sequence strictly increases within each epoch.
// Each span: texture_id/flags/topology/count/stride/position/color/uv u32[8],
// immediately followed by count*stride raw vertex bytes, with no padding.
// Position=f32[3], color=RGBA8 bytes, uv=f32[2]; topology=3 triangles or 4 quads.
// Flags for static/dynamic: 0 opaque, 1 alpha cutout, 2 stochastic alpha coverage.
// Flags cannot be combined. Coverage is diffuse surface/transmission selection,
// not refraction or medium absorption. Coincident surfaces share coverage events.
// Capture every referenced nonzero texture with op4 before submitting op6.
// Scene op7: atomic prototype/instance delta. Header[24], sequence:u64, then
// prototype_upserts/prototype_removes/instance_upserts/instance_removes:u32[4].
// Four corresponding groups follow. Prototype upsert: id/revision:u64[2],
// span_count/reserved:u32[2], then op6 spans with local geometry. Remove: id/revision:u64[2].
// Instance upsert[128]: id/revision/prototype:u64[3], world_origin:f64[3],
// row_major_affine:f32[12], texture/flags:u32[2], tint:RGBA8, reserved:u32,
// uv_scale_u/uv_scale_v/uv_offset_u/uv_offset_v:f32[4]. Texture/flags UINT32_MAX inherit.
// Geometry identity is independent of instance identity. Sequence strictly increases;
// every record revision equals its batch sequence. No permanent dead-ID history is needed.
// Empty delta is not submitted. Borrow ends on CPU return; GPU retirement remains timeline-based.
int32_t prime_submit(uint64_t handle, const uint8_t *packet, uint64_t length);
// Native-layout x64 descriptor. Each page is at most 256 MiB; the table has no
// scene-size limit. Argument descriptors and their bytes are borrowed until return.
typedef struct prime_source_page {
    const uint8_t *data;
    uint64_t length;
} prime_source_page;
// One request batch per source frame. Result points to session-owned read-only bytes,
// valid until the next plan/sections/destroy. No Java object or callback crosses here.
int32_t prime_mc_plan(uint64_t handle, const prime_source_page *pages, uint64_t count,
                     prime_source_page *requests);
// Accept section data, color source results or biome samples. A nonempty output
// requests the next explicit source stage; length zero proves publication is complete.
// Each call joins its workers before return; no input pointers survive.
int32_t prime_mc_sections(uint64_t handle, const prime_source_page *pages, uint64_t count, prime_source_page *output);
int32_t prime_render(uint64_t handle, const uint8_t *frame, uint64_t length,
                     uint8_t *rgba, uint64_t capacity);
// Production: borrow Minecraft's device and timeline, then record into its command buffer.
// host[48] LE: instance/physical/device/queue/timeline u64, family u32, reserved u32=0.
// Caller enables AS/rayQuery/BDA and timeline features; host objects remain caller-owned.
int32_t prime_attach_vulkan(uint64_t handle, const uint8_t *host, uint64_t length);
// Settings[56] LE: version=2/mode/bounces/offline_samples u32[4],
// exposure/hue/saturation f32[3], diagnostic_view u32, sun/sky/depth_range f32[3], seed u32.
// latitude_degrees i32 (-90..90), solar_longitude_degrees u32 (0..359).
// mode=0 realtime, 1 frozen offline; view=0 output, 1 noisy, 2 depth, 3 normal.
// Mode changes occur outside recording: submit the host encoder first, then call.
// Native waits for completion and destroys old mode resources before creating new ones.
// Offline requires a recorded frame with no subsequent scene mutation. prime_submit
// is rejected while frozen. Only incoming extent/sequence affect a frozen frame.
int32_t prime_configure(uint64_t handle, const uint8_t *settings, uint64_t length);
// Target is RGBA8_UNORM with STORAGE usage, GENERAL layout, bottom-up host coordinates.
// Caller ends/enqueues command, signals the attached timeline at submit_value, and
// retains target objects until completion. This call does not submit or read pixels;
// descriptor-slot exhaustion can wait for an older serial, never the new frame.
int32_t prime_record(uint64_t handle, const uint8_t *frame, uint64_t length,
                     uint64_t command, uint64_t image, uint64_t view, uint64_t submit_value);
uint64_t prime_gpu_time(uint64_t handle); // last completed PT time in ns, 0 if unavailable
uint64_t prime_cpu_diagnostics(uint64_t handle, uint8_t *output, uint64_t capacity);
// Before destroy the caller must flush its host encoder. Failure retains the session.
int32_t prime_destroy(uint64_t handle);
uint64_t prime_last_error(uint8_t *output, uint64_t capacity);
#ifdef __cplusplus
}
#endif
#endif
