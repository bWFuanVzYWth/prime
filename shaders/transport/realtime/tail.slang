#language slang 2026
module "transport/realtime/tail.slang";

import "prime_abi.slang";
import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "contract/path.slang";
import "math/sampling.slang";
import "math/direct_sampling.slang";
import "model/atmosphere/sky.slang";
import "model/realtime/transport.slang";
import "policy/runtime/config.slang";
import "policy/transport/path.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/dispatch/sample.slang";
import "service/bsdf/dispatch/traits.slang";
import "service/reconstruct/nrd/common.slang";
import "service/trace/context.slang";
import "service/trace/surface.slang";
import "state/path.slang";
import "state/realtime/contract.slang";
import "state/realtime/path_storage.slang";
import "state/realtime/phase_scratch.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/queue.slang";
import "state/realtime/transport.slang";
import "transport/direct/contract.slang";
import "transport/direct/immediate.slang";
import "transport/scatter/contract.slang";
import "transport/scatter/ordinary.slang";
import "transport/scatter/path_update.slang";
import "transport/surface/access.slang";
import "transport/surface/environment.slang";
import "transport/surface/medium.slang";
import "transport/surface/emission.slang";
import "bsdf/compact/contract/volume.slang";

public IntegratorRecord primeRealtimeTailIntegrator() {
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    return integrator;
}

public SurfaceInteraction primeTraceRealtimeTailSurface(
        PathState path, uint pathClass) {
    return primeTraceSurfaceClassified(
            PrimeSurfaceTracePolicy(),
            path.physicalOrigin,
            path.rayDirection,
            path.sourcePrimitive,
            pathClass);
}

public struct PrimeRealtimeTailSignal {
    float3 diffuse;
    float3 specular;
    float2 auxiliary;
}

public PrimeRealtimeTailSignal primeLoadRealtimeTailSignal(
        uint2 pixel,
        bool transparent,
        bool transmission) {
    int2 coordinate = int2(pixel);
    bool reflectionSh = transparent
            && !transmission
            && primeWritesNrdShInputs();
    float4 diffuse = transparent && !transmission
            ? reflectionSh
                    ? primeImageLoad(
                            primeNrdReflectionNoisyDiffuse, coordinate)
                    : primeImageLoad(primeStableRadiance, coordinate)
            : primeImageLoad(primeNrdNoisyDiffuse, coordinate);
    float4 specular = transparent && !transmission
            ? reflectionSh
                    ? primeImageLoad(
                            primeNrdReflectionNoisySpecular, coordinate)
                    : float4(0.0)
            : primeImageLoad(primeNrdNoisySpecular, coordinate);
    PrimeRealtimeTailSignal signal;
    signal.diffuse = diffuse.rgb;
    signal.specular = specular.rgb;
    signal.auxiliary = float2(diffuse.a, specular.a);
    return signal;
}

public void primeStoreRealtimeTailSignal(
        uint2 pixel,
        bool transparent,
        bool transmission,
        PrimeRealtimeTailSignal signal) {
    int2 coordinate = int2(pixel);
    if (transparent && !transmission && !primeWritesNrdShInputs()) {
        primeImageStore(
                primeStableRadiance,
                coordinate,
                float4(
                        primeNrdSanitizeRadiance(
                                signal.diffuse + signal.specular),
                        signal.auxiliary.x));
        return;
    }
    float4 diffuse = float4(
            primeNrdSanitizeRadiance(signal.diffuse),
            primeNrdSanitizeHitDistance(signal.auxiliary.x));
    float4 specular = float4(
            primeNrdSanitizeRadiance(signal.specular),
            primeNrdSanitizeHitDistance(signal.auxiliary.y));
    if (transparent && !transmission) {
        primeImageStore(primeNrdReflectionNoisyDiffuse, coordinate, diffuse);
        primeImageStore(primeNrdReflectionNoisySpecular, coordinate, specular);
        return;
    }
    primeImageStore(primeNrdNoisyDiffuse, coordinate, diffuse);
    primeImageStore(primeNrdNoisySpecular, coordinate, specular);
}

public void primeAccumulateRealtimeTailSignal(
        inout PrimeRealtimeTailSignal signal,
        bool diffusePath,
        float3 contribution) {
    if (diffusePath) {
        signal.diffuse += contribution;
    } else {
        signal.specular += contribution;
    }
}

// Landing and the fixed prefix have completed every guide mutation before tail admission. Keeping
// that proof in a dedicated transport loop prevents immutable guide/PSR state from crossing light
// selection, both shadow traces, surface traversal and the OpenPBR scatter island.
public bool primeIntegrateRealtimeTailSurface(
        inout PrimeIntegratorContext context,
        inout PathState path,
        inout PrimeOpenPbrVolumeStack volumeStack,
        inout PrimeRealtimeTailSignal signal,
        bool diffusePath,
        SurfaceInteraction surface,
        uint pathIndex) {
    if (!primeKnownHitKind(surface)) {
        return false;
    }
    IntegratorRecord integrator = primeRealtimeTailIntegrator();
    if (surface.hitKind == PRIME_HIT_NONE) {
        primeAccumulateRealtimeTailSignal(
                signal,
                diffusePath,
                primeEvaluateEnvironmentContribution(
                        context, path, integrator));
        return false;
    }
    if (!primeApplySegmentMedium(path.throughput, surface, volumeStack)) {
        return false;
    }

    float3 viewDirection = -path.rayDirection;
    primeAccumulateRealtimeTailSignal(
            signal,
            diffusePath,
            primeEvaluateHitEmission(context, path, surface, true));
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack, primeUsesAirGap());
    bool supportsSolidAngle = primeClosureSupports(
            closureTraits, PRIME_MEASURE_SOLID_ANGLE);
    bool discreteOnlyInterface = primeClosureIsDiscreteOnly(closureTraits);
    PrimePreparedSampleBase preparedSample = primePreparePathSample(
            path.sampleSeed,
            primeSampleIndex(),
            primePathBounce(path.control) + 1u);
    if (supportsSolidAngle) {
        PrimeDirectSampleBase directSample = primeMakeDirectSampleBase(
                primeWavefrontPixel(pathIndex),
                primeSampleIndex(),
                primeSampleEpoch(),
                primePathBounce(path.control) + 1u,
                uint(pathIndex >= primeWavefrontPixelCount()));
        PrimeImmediateSecondarySunNee sunNee;
        sunNee.directSample = directSample;
        PrimeImmediateSecondaryAreaNee areaNee;
        areaNee.directSample = directSample;
        float3 direct = path.throughput * primeEstimateDirectLighting(
                sunNee,
                areaNee,
                context,
                integrator,
                surface,
                viewDirection,
                preparedSample,
                volumeStack,
                path.throughput,
                pathIndex);
        primeAccumulateRealtimeTailSignal(
                signal, diffusePath, direct);
    }

    PrimePathScatter scatter = primeSamplePathSurface(
            context,
            surface,
            viewDirection,
            primeSobolSample3D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                    PRIME_SAMPLE_DIMENSION_PRIMARY),
            volumeStack,
            primeUsesAirGap());
    BsdfSample bsdf = scatter.bsdf;
    volumeStack = scatter.volumeStack;
    if (!primeHasScatter(bsdf)
            || !primeAdvanceRealtimePath(
                    context,
                    path,
                    surface,
                    bsdf,
                    preparedSample,
                    discreteOnlyInterface,
                    false)) {
        return false;
    }
    path.control = primeIncrementPathBounce(path.control);
    return true;
}

public void primeRunRealtimeTailPath(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        uint control) {
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    path.previousLightNormal =
            primeLoadWavefrontPreviousLightNormal(recordIndex);
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    bool transparent = primeWavefrontTransparentBranch(control);
    bool transmission = primeWavefrontTransmissionBranch(control);
    uint pathClass = !transparent ? 0u : transmission ? 2u : 1u;
    bool diffusePath = transparent && !primeWavefrontGuideEnabled(control)
            ? transmission
            : (control & PRIME_WAVEFRONT_DIFFUSE_PATH) != 0u;
    PrimeRealtimeTailSignal signal = primeLoadRealtimeTailSignal(
            pixel, transparent, transmission);
    bool continuation = true;

    [loop]
    while (continuation
            && primeRealtimeCompletedBounceCount(
                    primePathBounce(path.control)) < primeMaximumBounces()) {
        SurfaceInteraction surface = primeTraceRealtimeTailSurface(
                path, pathClass);
        continuation = primeIntegrateRealtimeTailSurface(
                context,
                path,
                volumeStack,
                signal,
                diffusePath,
                surface,
                recordIndex);
    }

    PrimeDenoiserState denoiserState = {};
    denoiserState.hasPrimarySurface = true;
    denoiserState.reachedSolidAngle =
            (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
    denoiserState.diffusePath =
            (control & PRIME_WAVEFRONT_DIFFUSE_PATH) != 0u;
    denoiserState.primaryBounce =
            (control >> PRIME_WAVEFRONT_PRIMARY_BOUNCE_SHIFT)
                    & PRIME_WAVEFRONT_BYTE_MASK;
    PrimeWavefrontTransportRecord updated = primeMakeUpdatedWavefrontRecord(
            path,
            volumeStack,
            denoiserState,
            control,
            0u,
            false);
    primeStoreWavefrontTransportRecord(recordIndex, updated);
    primeStoreWavefrontPreviousLightNormal(
            recordIndex, path.previousLightNormal);
    primeStoreRealtimeTailSignal(
            pixel, transparent, transmission, signal);
}

public void primeRunRealtimeTail(
        inout PrimeIntegratorContext context, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_AREA_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    primeRunRealtimeTailPath(context, recordIndex, record, control);
}
