// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#define PRIME_TEST_INPUT_BINDING 100
#define PRIME_TEST_OUTPUT_BINDING 101
#include "core/prime_test_abi.slang"
import "prime_abi_types.slang";
import "contract/medium.slang";
import "contract/path.slang";
import "contract/material/normal.slang";
import "model/material/defaults.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/contract/components.slang";
import "service/bsdf/dispatch/sample.slang";
import "service/bsdf/dispatch/ordinary_sample.slang";
import "service/bsdf/opaque/evaluate.slang";
import "service/bsdf/foliage/evaluate.slang";
import "service/bsdf/dielectric/state.slang";
import "service/bsdf/dielectric/evaluate.slang";
import "bsdf/compact/dielectric/contract.slang";
import "service/trace/context.slang";
import "transport/scatter/contract.slang";
import "transport/surface/access.slang";
import "transport/direct/evaluate.slang";
import "contract/light_sample.slang";
import "contract/light.slang";

bool sameScatter(PrimePathScatter a, PrimePathScatter b) {
    return all(asuint(a.bsdf.direction) == asuint(b.bsdf.direction))
            && all(asuint(a.bsdf.response) == asuint(b.bsdf.response))
            && asuint(a.bsdf.pdf) == asuint(b.bsdf.pdf)
            && asuint(a.bsdf.relativeEta) == asuint(b.bsdf.relativeEta)
            && a.bsdf.eventFlags == b.bsdf.eventFlags
            && all(asuint(a.medium.extinction) == asuint(b.medium.extinction))
            && asuint(a.medium.ior) == asuint(b.medium.ior)
            && a.medium.mediumId == b.medium.mediumId
            && a.medium.iorEncoding == b.medium.iorEncoding;
}

[numthreads(64, 1, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint index;
    if (!primeTestBegin(tid, index)) return;
    uint offset = primeTestInputOffset(index, 4u);
    uint4 control = primeTestInput[offset];
    float4 color = asfloat(primeTestInput[offset + 1u]);
    float4 random = asfloat(primeTestInput[offset + 2u]);
    float4 geometry = asfloat(primeTestInput[offset + 3u]);
    SurfaceInteraction surface = {};
    surface.hitKind = PRIME_HIT_SURFACE;
    surface.baseColor = color.xyz;
    surface.roughness = color.w;
    surface.opacity = geometry.w;
    surface.materialControl = control.x;
    surface.opticalControl = control.y;
    surface.geometricNormal = float3(0.0, 0.0, 1.0);
    surface.shadingNormal = normalize(float3(geometry.y, 0.0, 1.0));
    surface.t = 2.0;
    surface.mediumId = 37u;
    surface.adjacentBaseColor = float3(0.2, 0.5, 0.8);
    surface.adjacentInterfaceControl = control.w;
    surface.adjacentMediumId = control.w != 0u ? 41u : 0u;
    float3 view = float3(sqrt(max(0.0, 1.0 - geometry.x * geometry.x)), 0.0, geometry.x);
    PrimeMedium medium = primeVacuumMedium();
    if (control.z != 0u) {
        medium.ior = 1.5;
        medium.mediumId = 37u;
        medium.extinction = float3(0.03, 0.02, 0.01);
    }
    bool airGap = geometry.z != 0.0;
    PrimeGuidedPathScatter guided = primeSamplePathSurfaceWithAlbedos(
            surface, view, random.xyz, medium, true, airGap);
    PrimeGuidedPathScatter unguided = primeSamplePathSurfaceWithAlbedos(
            surface, view, random.xyz, medium, false, airGap);
    uint mask = 0u;
    if (!sameScatter(guided.scatter, unguided.scatter)) mask |= PRIME_TEST_FAILURE_IDENTITY;
    if (any(unguided.albedos.diffuse != float3(0.0))
            || any(unguided.albedos.specular != float3(0.0))) mask |= PRIME_TEST_FAILURE_STATE;
    mask |= primeTestClassify(guided.albedos.diffuse) | primeTestClassify(guided.albedos.specular);
    if (any(guided.albedos.diffuse < float3(0.0))
            || any(guided.albedos.specular < float3(0.0))) mask |= PRIME_TEST_FAILURE_RANGE;
    if (!primeMaterialIsTransmissive(surface.materialControl)) {
        PrimeGuidedPathScatter ordinary = primeSampleOrdinaryPathSurface(
                surface, view, random.xyz, medium, true);
        if (!sameScatter(guided.scatter, ordinary.scatter)
                || any(asuint(guided.albedos.diffuse) != asuint(ordinary.albedos.diffuse))
                || any(asuint(guided.albedos.specular) != asuint(ordinary.albedos.specular)))
            mask |= PRIME_TEST_FAILURE_EVENT;
    }
    // At equal light/BSDF PDFs, NEE must contribute half the full response in both the
    // complete and primary conditional estimators. Use the production leaf evaluation
    // independently of the transport weighting; the conditional PDF must not gain an event PDF.
    float3 expected = float3(0.0), actual = float3(0.0);
    if (!primeMaterialUsesNormalMap(surface.opticalControl)) {
        float3 direction = normalize(float3(random.x - 0.5, random.y - 0.5,
                random.z < 0.5 ? -0.7 : 0.7));
        float3 normal = primeSurfaceShadingNormal(surface, view);
        for (uint conditional = 0u; conditional < 2u; ++conditional) {
            float3 response;
            float pdf;
            if (primeMaterialIsTransmissive(surface.materialControl)) {
                PrimeCompactTransmissionState state = primeMinecraftBoundaryTransmissionState(
                        surface.baseColor, surface.opacity, primeSurfaceOutwardShadingNormal(surface),
                        surface.materialControl, surface.roughness, surface.opticalControl, view,
                        surface.t, medium, surface.mediumId, surface.adjacentBaseColor,
                        surface.adjacentInterfaceControl, surface.adjacentMediumId, airGap);
                BsdfEvaluation evaluation;
                if (conditional != 0u) {
                    evaluation = dot(primeSurfaceOutwardShadingNormal(surface), view)
                            * dot(primeSurfaceOutwardShadingNormal(surface), direction) >= 0.0
                            ? primeEvaluateMinecraftTransparentReflectionFromState(state, view, direction)
                            : primeEvaluateMinecraftTransparentTransmissionFromState(state, view, direction);
                } else evaluation = primeEvaluateMinecraftTransmissionCompleteFromState(state, view, direction);
                response = evaluation.response;
                pdf = evaluation.pdf;
            } else {
                PrimeBsdfComponents components = primeMaterialIsFoliage(surface.materialControl)
                        ? primeEvaluateMinecraftFoliageComponents(surface.baseColor, normal, surface.roughness,
                                surface.opticalControl, surface.materialControl, view, direction, medium)
                        : primeEvaluateOpaqueComponents(surface.baseColor, normal, surface.roughness,
                                surface.opticalControl, surface.materialControl, view, direction, medium);
                response = components.diffuseResponse + components.specularResponse;
                pdf = components.pdf;
            }
            if (pdf > 0.0) {
                LightSample light = {};
                light.direction = direction;
                light.pdf = pdf;
                PrimeIntegratorContext context = primeIntegratorContextCreate();
                PrimeDirectLightingSplit direct = primeEvaluateVisibleDirectSplit(context, surface, view,
                        normal, light, float3(1.0), medium, airGap, conditional != 0u);
                actual = (direct.diffuse + direct.specular) * pdf;
                expected = response * 0.5;
                if (!primeTestClose(actual, expected, 1.0e-6, 2.0e-5)) mask |= PRIME_TEST_FAILURE_PDF;
            }
        }
    }
    uint outOffset = primeTestOutputOffset(index, primeTestOutputWords());
    primeTestOutput[outOffset] = uint4(mask, control.x, index, guided.scatter.bsdf.eventFlags);
    if (primeTestWitnessEnabled()) {
        primeTestOutput[outOffset + 1u] = asuint(float4(guided.scatter.bsdf.response, guided.scatter.bsdf.pdf));
        primeTestOutput[outOffset + 2u] = asuint(float4(unguided.scatter.bsdf.response, unguided.scatter.bsdf.pdf));
        primeTestOutput[outOffset + 3u] = asuint(float4(actual, 0.0));
        primeTestOutput[outOffset + 4u] = asuint(float4(expected, 0.0));
    }
}
