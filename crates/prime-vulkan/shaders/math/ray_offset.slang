#language slang 2026
module "math/ray_offset.slang";

// SPDX-FileCopyrightText: Copyright (c) 2023 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: BSD-3-Clause
// Adapted from SelfIntersectionAvoidance.hlsl: return the values actually consumed
// by tracing, retain precise operation order and both transform error bounds.
// Full notice: licenses/nvidia-self-intersection-BSD-3-Clause.txt.

public struct SurfacePoint {
    public float3 position;
    public float3 normal;
    public float offset;
};

// Finite nondegenerate triangle and mutually inverse nonsingular affine transforms.
// Intersection constant c1 follows NVIDIA's RTX bound; other hardware needs validation.
public SurfacePoint reconstructSurface(float3 v0, float3 v1, float3 v2, float2 bary,
                                       float3x4 objectToWorld, float3x4 worldToObject) {
    precise float3 edge1 = v1 - v0;
    precise float3 edge2 = v2 - v0;
    precise float3 local = v0 + mad(bary.x, edge1, bary.y * edge2);
    float3 localNormal = cross(edge1, edge2);
    precise float3 world;
    world.x = objectToWorld[0][3] +
              mad(objectToWorld[0][0], local.x,
                  mad(objectToWorld[0][1], local.y, objectToWorld[0][2] * local.z));
    world.y = objectToWorld[1][3] +
              mad(objectToWorld[1][0], local.x,
                  mad(objectToWorld[1][1], local.y, objectToWorld[1][2] * local.z));
    world.z = objectToWorld[2][3] +
              mad(objectToWorld[2][0], local.x,
                  mad(objectToWorld[2][1], local.y, objectToWorld[2][2] * local.z));
    float3 normal = mul(transpose(float3x3(worldToObject)), localNormal);
    float scale = rsqrt(dot(normal, normal));
    normal *= scale;
    const float c0 = 5.9604644775390625e-8;
    const float c1 = 1.78813976958736020606e-7;
    const float c2 = 1.19209317972490680404e-7;
    float3 extent3 = abs(edge1) + abs(edge2) + abs(edge1 - edge2);
    float extent = max(max(extent3.x, extent3.y), extent3.z);
    float3 localError = mad(c0, abs(v0), c1 * extent);
    float3 translation = float3(objectToWorld[0][3], objectToWorld[1][3], objectToWorld[2][3]);
    float3 worldError =
            mad(c1, mul(abs(float3x3(objectToWorld)), abs(local)), c2 * abs(translation));
    localError = mad(c2, mul(abs(worldToObject), float4(abs(world), 1.0)), localError);
    SurfacePoint result;
    result.position = world;
    result.normal = normal;
    result.offset = mad(scale, dot(localError, abs(localNormal)), dot(worldError, abs(normal)));
    return result;
}

// Static cells have a producer-proven translation-only instance transform. Keep the same
// reconstruction order and RTX error constants, specializing the two identity matrix products
// instead of fetching and multiplying general affine transforms at every static hit.
public SurfacePoint reconstructStaticSurface(float3 v0, float3 v1, float3 v2, float2 bary,
                                             float3 translation) {
    precise float3 edge1 = v1 - v0;
    precise float3 edge2 = v2 - v0;
    precise float3 local = v0 + mad(bary.x, edge1, bary.y * edge2);
    precise float3 world = translation + local;
    float3 localNormal = cross(edge1, edge2);
    float scale = rsqrt(dot(localNormal, localNormal));
    float3 normal = localNormal * scale;
    const float c0 = 5.9604644775390625e-8;
    const float c1 = 1.78813976958736020606e-7;
    const float c2 = 1.19209317972490680404e-7;
    float3 extent3 = abs(edge1) + abs(edge2) + abs(edge1 - edge2);
    float extent = max(max(extent3.x, extent3.y), extent3.z);
    float3 localError = mad(c0, abs(v0), c1 * extent);
    float3 worldError = mad(c1, abs(local), c2 * abs(translation));
    localError = mad(c2, abs(world) + abs(translation), localError);
    SurfacePoint result;
    result.position = world;
    result.normal = normal;
    result.offset = mad(scale, dot(localError, abs(localNormal)), dot(worldError, abs(normal)));
    return result;
}
// Direction chooses the outgoing side, supporting both reflection and transmission.
public float3 spawnRay(SurfacePoint surface, float3 direction) {
    float3 normal = dot(surface.normal, direction) >= 0.0 ? surface.normal : -surface.normal;
    precise float3 result = mad(surface.offset, normal, surface.position);
    return result;
}
