// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

import "contract/path.slang";
import "prime_abi_types.slang";
import "contract/realtime/path.slang";
import "contract/light_receiver.slang";
import "math/realtime_control.slang";
import "state/light_receiver.slang";
import "state/surface_storage.slang";
import "transport/direct/selection.slang";

[[vk::binding(0, 0)]] StructuredBuffer<uint4> inputData;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint> outputData;

[numthreads(64, 1, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    if (tid.x >= inputData[0].x) return;
    uint input = 1u + tid.x * 8u;
    uint output = tid.x * 32u;
    PrimeSurfaceStorageRecord packed;
    packed.geometry = inputData[input];
    packed.identityBaseX = inputData[input + 1u];
    packed.baseYzOpacityRoughness = inputData[input + 2u];
    packed.opticalShadingNormal = inputData[input + 3u];
    packed.adjacentBasePositionX = inputData[input + 4u];
    packed.positionYzMotionAdjacent = inputData[input + 5u];
    for (uint lane = 0u; lane < 6u; ++lane) {
        uint4 value = inputData[input + lane];
        for (uint component = 0u; component < 4u; ++component)
            outputData[output + lane * 4u + component] = value[component];
    }
    PrimeLightReceiver receiver = primeLoadLightReceiver(outputData, output);
    SurfaceInteraction surface = primeUnpackSurfaceStorageRecord(packed);
    bool exact = receiver.hitKind == surface.hitKind;
    if (receiver.hitKind != PRIME_HIT_NONE) {
        exact = exact && all(asuint(receiver.position) == asuint(surface.position))
                && all(asuint(receiver.geometricNormal) == asuint(surface.geometricNormal))
                && all(asuint(receiver.shadingNormal) == asuint(surface.shadingNormal))
                && receiver.classification.materialControl == surface.materialControl
                && asuint(receiver.classification.roughness) == asuint(surface.roughness)
                && receiver.classification.opticalControl == surface.opticalControl
                && receiver.classification.adjacentInterfaceControl == surface.adjacentInterfaceControl;
        float3 view = asfloat(inputData[input + 7u].xyz);
        exact = exact && all(asuint(primeAreaLightReceiverNormal(receiver, view))
                == asuint(primeAreaLightReceiverNormal(surface, view)));
    } else {
        // A miss may follow any previous occupant; unpublished fields have no authority.
        exact = exact && all(asuint(receiver.position) == uint3(0u))
                && all(asuint(receiver.geometricNormal) == uint3(0u))
                && receiver.classification.materialControl == 0u;
    }
    uint4 control = inputData[input + 6u];
    PrimeWavefrontContinuationControl state;
    state.guide = control.x;
    state.path = control.y;
    state.denoiser = control.z;
    PrimeWavefrontContinuationControl next = primeEnterSecondaryControl(state);
    PrimeWavefrontContinuationControl twice = primeEnterSecondaryControl(next);
    outputData[output + 24u] = uint(exact);
    outputData[output + 25u] = next.guide;
    outputData[output + 26u] = next.path;
    outputData[output + 27u] = next.denoiser;
    outputData[output + 28u] = uint(next.guide == twice.guide && next.path == twice.path && next.denoiser == twice.denoiser);
}
