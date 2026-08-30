#language slang 2026
module "service/trace/surface.slang";

import glsl;
import "prime_abi.slang";
import "prime_abi_types.slang";
import "math/packed_geometry.slang";
import "policy/runtime/config.slang";
import "service/light/emission.slang";
import "model/material/defaults.slang";
import "contract/path.slang";

layout(location = 0) rayPayloadEXT TracePayload primeSurfacePayload;

public SurfaceInteraction primeMissSurfaceInteraction(uint2 sourcePrimitive) {
    SurfaceInteraction surface;
    surface.position = float3(0.0);
    surface.t = 0.0;
    surface.geometricNormal = float3(0.0, 1.0, 0.0);
    surface.hitKind = PRIME_HIT_NONE;
    surface.baseColor = float3(0.0);
    surface.materialControl = 0u;
    surface.sectionIndex = sourcePrimitive.x;
    surface.emitterIndex = PRIME_NO_LIGHT_INDEX;
    surface.textureLod = 0.0;
    surface.opacity = 0.0;
    surface.shadingNormal = float3(0.0, 1.0, 0.0);
    surface.roughness = primeConfiguredDefaultLinearRoughness();
    surface.opticalControl = 0u;
    surface.motionZFlags = sourcePrimitive.y;
    surface.mediumId = 0u;
    surface.adjacentMediumId = 0u;
    surface.adjacentBaseColor = float3(0.0);
    surface.adjacentInterfaceControl = 0u;
    return surface;
}

public void primeResetSurfacePayload(uint2 sourcePrimitive) {
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
    primeSurfacePayload.roughness = primeConfiguredDefaultLinearRoughness();
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
            uint pathClass);
}

public struct PrimeScalarSurfaceTracePolicy : IPrimeSurfaceTracePolicy {
    void trace(
            float3 origin,
            float3 direction,
            uint2 sourcePrimitive,
            uint pathClass) {
        uint rayFlags = sourcePrimitive.x == PRIME_NO_PRIMITIVE_INDEX
                ? gl_RayFlagsNoneEXT
                : gl_RayFlagsNoOpaqueEXT;
        traceRayEXT(primeScene, rayFlags, 0xff, 0, 1, 0,
                origin, 0.0, direction, 1000000.0, 0);
    }
};

public struct PrimeSerSurfaceTracePolicy : IPrimeSurfaceTracePolicy {
    void trace(
            float3 origin,
            float3 direction,
            uint2 sourcePrimitive,
            uint pathClass) {
        int payloadLocation = 0;
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
                payloadLocation);
        bool hit = hitObjectIsHitEXT(hitObject);
        uint coherenceHint = ((pathClass & 0x3u) << 10u)
                | (hit ? (uint(hitObjectGetGeometryIndexEXT(hitObject)) & 0x3u) << 8u : 0u)
                | (hit ? uint(hitObjectGetInstanceIdEXT(hitObject)) & 0xffu : 0u);
        reorderThreadEXT(hitObject, coherenceHint, 12u);
        hitObjectExecuteShaderEXT(hitObject, payloadLocation);
    }
};

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
        uint pathClass)
    where TracePolicy : IPrimeSurfaceTracePolicy
{
    primeResetSurfacePayload(sourcePrimitive);
    policy.trace(origin, direction, sourcePrimitive, pathClass);
    return primeSurfaceInteractionFromPayload();
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

public SurfaceInteraction primeTraceSurfaceWithoutReorder(
        float3 origin, float3 direction) {
    return primeTraceSurfaceClassified(
            PrimeScalarSurfaceTracePolicy(),
            origin,
            direction,
            uint2(PRIME_NO_PRIMITIVE_INDEX),
            0u);
}

public SurfaceInteraction primeTraceSurfaceWithoutReorder(
        float3 origin, float3 direction, uint2 sourcePrimitive) {
    return primeTraceSurfaceClassified(
            PrimeScalarSurfaceTracePolicy(),
            origin,
            direction,
            sourcePrimitive,
            0u);
}
