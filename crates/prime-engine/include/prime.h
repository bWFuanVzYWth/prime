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
int32_t prime_submit(uint64_t handle, const uint8_t *packet, uint64_t length);
int32_t prime_render(uint64_t handle, const uint8_t *frame, uint64_t length,
                     uint8_t *rgba, uint64_t capacity);
// Production: borrow Minecraft's device and timeline, then record into its command buffer.
// host[48] LE: instance/physical/device/queue/timeline u64, family u32, reserved u32=0.
// Caller enables AS/rayQuery/BDA and timeline features; host objects remain caller-owned.
int32_t prime_attach_vulkan(uint64_t handle, const uint8_t *host, uint64_t length);
// Target is RGBA8_UNORM with STORAGE usage, GENERAL layout, bottom-up host coordinates.
// Caller ends/enqueues command, signals the attached timeline at submit_value, and
// retains target objects until completion. This call does not submit or read pixels;
// descriptor-slot exhaustion can wait for an older serial, never the new frame.
int32_t prime_record(uint64_t handle, const uint8_t *frame, uint64_t length,
                     uint64_t command, uint64_t image, uint64_t view, uint64_t submit_value);
uint64_t prime_gpu_time(uint64_t handle); // last completed PT time in ns, 0 if unavailable
// Before destroy the caller must flush its host encoder. Failure retains the session.
int32_t prime_destroy(uint64_t handle);
uint64_t prime_last_error(uint8_t *output, uint64_t capacity);
#ifdef __cplusplus
}
#endif
#endif
