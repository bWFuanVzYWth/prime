// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/trace/surface.slang";

import glsl;
import "prime_abi.slang";
import "prime_abi_types.slang";
import "math/packed_geometry.slang";
import "policy/runtime/config.slang";
import "contract/light_sample.slang";
import "model/material/defaults.slang";
import "contract/path.slang";
import "contract/trace.slang";

layout(location = 0) rayPayloadEXT TracePayload primeSurfacePayload;
#if defined(PRIME_ENABLE_SER)
layout(location = 2) rayPayloadEXT PrimeTraceRequest primeSurfaceTrace;
#endif

public void primeResetSurfacePayload(uint2 sourcePrimitive, float defaultRoughness) {
    primeSurfacePayload.t = 0.0;
    primeSurfacePayload.geometricNormal = float3(0.0, 1.0, 0.0);
    primeSurfacePayload.hitKind = PRIME_HIT_NONE;
    primeSurfacePayload.baseColor = float3(0.0);
    primeSurfacePayload.materialControl = 0u;
    primeSurfacePayload.sectionIndex = sourcePrimitive.x;
    primeSurfacePayload.emitterIndex = PRIME_NO_LIGHT_INDEX;
    primeSurfacePayload.textureLod = 0.0;
    primeSurfacePayload.opacity = 0.0;
    primeSurfacePayload.shadingNormal = float3(0.0, 1.0, 0.0);
    primeSurfacePayload.roughness = defaultRoughness;
    primeSurfacePayload.opticalControl = 0u;
    primeSurfacePayload.mediumId = 0u;
    primeSurfacePayload.adjacentMediumId = 0u;
    primeSurfacePayload.adjacentBaseColor = float3(0.0);
    primeSurfacePayload.adjacentInterfaceControl = 0u;
    primeSurfacePayload.position = float3(0.0);
    primeSurfacePayload.positionReserved = sourcePrimitive.y;
}

public SurfaceInteraction primeSurfaceInteractionFromPayload() {
    SurfaceInteraction surface;
    uint packedHitKind = primeSurfacePayload.hitKind;
    surface.position = (packedHitKind & PRIME_HIT_KIND_MASK) == PRIME_HIT_NONE
            ? float3(0.0)
            : primeSurfacePayload.position;
    surface.t = primeSurfacePayload.t;
    surface.geometricNormal = primeSurfacePayload.geometricNormal;
    surface.hitKind = packedHitKind & PRIME_HIT_KIND_MASK;
    surface.baseColor = primeSurfacePayload.baseColor;
    surface.materialControl = primeSurfacePayload.materialControl;
    surface.sectionIndex = primeSurfacePayload.sectionIndex;
    surface.emitterIndex = primeSurfacePayload.emitterIndex;
    surface.textureLod = primeSurfacePayload.textureLod;
    surface.opacity = primeSurfacePayload.opacity;
    surface.shadingNormal = primeSurfacePayload.shadingNormal;
    surface.roughness = primeSurfacePayload.roughness;
    surface.opticalControl = primeSurfacePayload.opticalControl;
    surface.motionZFlags = primeSurfacePayload.positionReserved;
    surface.mediumId = primeSurfacePayload.mediumId;
    surface.adjacentMediumId = primeSurfacePayload.adjacentMediumId;
    surface.adjacentBaseColor = primeSurfacePayload.adjacentBaseColor;
    surface.adjacentInterfaceControl = primeSurfacePayload.adjacentInterfaceControl;
    return surface;
}

public interface IPrimeSurfaceTracePolicy {
    void trace(
            float3 origin,
            float3 direction,
            uint2 sourcePrimitive,
            uint pathClass,
            float defaultRoughness);
}

public struct PrimeScalarSurfaceTracePolicy : IPrimeSurfaceTracePolicy {
    void trace(
            float3 origin,
            float3 direction,
            uint2 sourcePrimitive,
            uint pathClass,
            float defaultRoughness) {
        primeResetSurfacePayload(sourcePrimitive, defaultRoughness);
        uint rayFlags = sourcePrimitive.x == PRIME_NO_PRIMITIVE_INDEX
                ? gl_RayFlagsNoneEXT
                : gl_RayFlagsNoOpaqueEXT;
        traceRayEXT(primeScene, rayFlags, 0xff, 0, 1, 0,
                origin, 0.0, direction, 1000000.0, 0);
    }
};

#if defined(PRIME_ENABLE_SER)
public struct PrimeSerSurfaceTracePolicy : IPrimeSurfaceTracePolicy {
    void trace(
            float3 origin,
            float3 direction,
            uint2 sourcePrimitive,
            uint pathClass,
            float defaultRoughness) {
        primeSurfaceTrace.source = sourcePrimitive;
        primeSurfaceTrace.reserved = 0u;
        HitObject hitObject;
        hitObjectTraceRayEXT(
                hitObject,
                primeScene,
                sourcePrimitive.x == PRIME_NO_PRIMITIVE_INDEX
                        ? gl_RayFlagsNoneEXT
                        : gl_RayFlagsNoOpaqueEXT,
                0xff,
                0,
                1,
                0,
                origin,
                0.0,
                direction,
                1000000.0,
                2);
        bool hit = hitObjectIsHitEXT(hitObject);
        uint coherenceHint = ((pathClass & 0x3u) << 10u)
                | (hit ? (uint(hitObjectGetGeometryIndexEXT(hitObject)) & 0x3u) << 8u : 0u)
                | (hit ? uint(hitObjectGetInstanceIdEXT(hitObject)) & 0xffu : 0u);
        reorderThreadEXT(hitObject, coherenceHint, 12u);
        // The shading result does not exist across traversal/reorder. Any-hit consumes only source.
        primeResetSurfacePayload(sourcePrimitive, defaultRoughness);
        hitObjectExecuteShaderEXT(hitObject, 0);
    }
};
#endif

#if defined(PRIME_ENABLE_SER)
public typealias PrimeSurfaceTracePolicy = PrimeSerSurfaceTracePolicy;
#else
public typealias PrimeSurfaceTracePolicy = PrimeScalarSurfaceTracePolicy;
#endif

public SurfaceInteraction primeTraceSurfaceClassified<TracePolicy>(
        TracePolicy policy,
        float3 origin,
        float3 direction,
        uint2 sourcePrimitive,
        uint pathClass,
        float defaultRoughness)
    where TracePolicy : IPrimeSurfaceTracePolicy
{
    policy.trace(origin, direction, sourcePrimitive, pathClass, defaultRoughness);
    return primeSurfaceInteractionFromPayload();
}

public SurfaceInteraction primeTraceSurfaceClassified<TracePolicy>(
        TracePolicy policy, float3 origin, float3 direction, uint2 sourcePrimitive, uint pathClass)
    where TracePolicy : IPrimeSurfaceTracePolicy
{
    return primeTraceSurfaceClassified(policy, origin, direction, sourcePrimitive, pathClass,
            primeConfiguredDefaultLinearRoughness());
}

public SurfaceInteraction primeTraceSurface(float3 origin, float3 direction) {
    return primeTraceSurfaceClassified(
            PrimeSurfaceTracePolicy(),
            origin,
            direction,
            uint2(PRIME_NO_PRIMITIVE_INDEX),
            0u);
}

public SurfaceInteraction primeTraceSurface(
        float3 origin, float3 direction, uint2 sourcePrimitive) {
    return primeTraceSurfaceClassified(
            PrimeSurfaceTracePolicy(), origin, direction, sourcePrimitive, 0u);
}
