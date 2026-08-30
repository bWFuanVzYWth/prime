#include <NRD.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <new>
#include <vector>

#if defined(_WIN32)
#define PRIME_NRD_EXPORT extern "C" __declspec(dllexport)
#else
#define PRIME_NRD_EXPORT extern "C" __attribute__((visibility("default")))
#endif

namespace
{
    constexpr uint32_t PRIME_NRD_ABI_VERSION = 11;
    constexpr nrd::Identifier PRIME_NRD_PRIMARY_REBLUR_ID = 0;
    constexpr nrd::Identifier PRIME_NRD_SIGMA_SUN_ID = 1;
    constexpr nrd::Identifier PRIME_NRD_REFLECTION_REBLUR_ID = 2;

    struct PrimeNrdCreateDesc
    {
        uint32_t width;
        uint32_t height;
        uint32_t reserved;
    };

    struct PrimeNrdTextureInfo
    {
        uint32_t format;
        uint32_t downsampleFactor;
    };

    struct PrimeNrdPipelineRangeInfo
    {
        uint32_t descriptorType;
        uint32_t descriptorsNum;
    };

    struct PrimeNrdPipelineInfo
    {
        uint64_t spirvAddress;
        uint64_t spirvSize;
        uint64_t rangesAddress;
        uint32_t rangesNum;
        uint32_t hasConstantData;
        char shaderIdentifier[256];
    };

    struct PrimeNrdDescription
    {
        uint32_t abiVersion;
        uint32_t nrdVersion;
        uint32_t samplerOffset;
        uint32_t textureOffset;
        uint32_t constantBufferOffset;
        uint32_t storageTextureOffset;
        uint32_t constantBufferRegisterIndex;
        uint32_t samplersBaseRegisterIndex;
        uint32_t resourcesBaseRegisterIndex;
        uint32_t constantBufferMaxDataSize;
        uint64_t samplersAddress;
        uint32_t samplersNum;
        uint32_t pipelinesNum;
        uint64_t pipelinesAddress;
        uint32_t permanentPoolSize;
        uint32_t transientPoolSize;
        uint64_t permanentPoolAddress;
        uint64_t transientPoolAddress;
        uint32_t setsMaxNum;
        uint32_t constantBufferAndSamplersSpaceIndex;
        uint32_t resourcesSpaceIndex;
        uint32_t normalEncoding;
        char shaderEntryPoint[32];
    };

    struct PrimeNrdFrameSettings
    {
        float viewToClip[16];
        float viewToClipPrev[16];
        float worldToView[16];
        float worldToViewPrev[16];
        float cameraJitter[2];
        float cameraJitterPrev[2];
        uint32_t width;
        uint32_t height;
        uint32_t previousWidth;
        uint32_t previousHeight;
        uint32_t frameIndex;
        uint32_t restart;
        float timeDeltaMilliseconds;
        float denoisingRange;
        uint32_t enableValidation;
        float sunDirection[3];
    };

    struct PrimeNrdResourceInfo
    {
        uint32_t descriptorType;
        uint32_t resourceType;
        uint32_t indexInPool;
        uint32_t reserved;
    };

    struct PrimeNrdDispatchInfo
    {
        uint64_t nameAddress;
        uint64_t resourcesAddress;
        uint64_t constantDataAddress;
        uint32_t resourcesNum;
        uint32_t constantDataSize;
        uint32_t pipelineIndex;
        uint32_t gridWidth;
        uint32_t gridHeight;
        uint32_t reserved;
    };

    struct PrimeNrdDispatchList
    {
        uint64_t dispatchesAddress;
        uint32_t dispatchesNum;
        uint32_t reserved;
    };

    struct PrimeNrdContext
    {
        nrd::Instance* instance = nullptr;
        PrimeNrdDescription description = {};
        std::vector<uint32_t> samplers;
        std::vector<PrimeNrdTextureInfo> permanentPool;
        std::vector<PrimeNrdTextureInfo> transientPool;
        std::vector<std::vector<PrimeNrdPipelineRangeInfo>> pipelineRanges;
        std::vector<PrimeNrdPipelineInfo> pipelines;
        std::vector<std::vector<PrimeNrdResourceInfo>> dispatchResources;
        std::vector<PrimeNrdDispatchInfo> dispatches;

        ~PrimeNrdContext()
        {
            if (instance != nullptr)
                nrd::DestroyInstance(*instance);
        }
    };

    uint32_t PackVersion(uint32_t major, uint32_t minor, uint32_t build)
    {
        return (major << 24) | (minor << 16) | build;
    }

    void CopyTexturePool(
        const nrd::TextureDesc* source,
        uint32_t count,
        std::vector<PrimeNrdTextureInfo>& destination)
    {
        destination.resize(count);
        for (uint32_t i = 0; i < count; i++)
        {
            destination[i].format = static_cast<uint32_t>(source[i].format);
            destination[i].downsampleFactor = source[i].downsampleFactor;
        }
    }

    bool BuildDescription(PrimeNrdContext& context)
    {
        const nrd::LibraryDesc* library = nrd::GetLibraryDesc();
        const nrd::InstanceDesc* instance = nrd::GetInstanceDesc(*context.instance);
        if (library == nullptr || instance == nullptr || instance->shaderEntryPoint == nullptr)
            return false;

        context.samplers.resize(instance->samplersNum);
        for (uint32_t i = 0; i < instance->samplersNum; i++)
            context.samplers[i] = static_cast<uint32_t>(instance->samplers[i]);

        CopyTexturePool(instance->permanentPool, instance->permanentPoolSize, context.permanentPool);
        CopyTexturePool(instance->transientPool, instance->transientPoolSize, context.transientPool);

        context.pipelineRanges.resize(instance->pipelinesNum);
        context.pipelines.resize(instance->pipelinesNum);
        for (uint32_t pipelineIndex = 0; pipelineIndex < instance->pipelinesNum; pipelineIndex++)
        {
            const nrd::PipelineDesc& source = instance->pipelines[pipelineIndex];
            if (source.computeShaderSPIRV.bytecode == nullptr || source.computeShaderSPIRV.size == 0)
                return false;

            std::vector<PrimeNrdPipelineRangeInfo>& ranges = context.pipelineRanges[pipelineIndex];
            ranges.resize(source.resourceRangesNum);
            for (uint32_t rangeIndex = 0; rangeIndex < source.resourceRangesNum; rangeIndex++)
            {
                ranges[rangeIndex].descriptorType =
                    static_cast<uint32_t>(source.resourceRanges[rangeIndex].descriptorType);
                ranges[rangeIndex].descriptorsNum = source.resourceRanges[rangeIndex].descriptorsNum;
            }

            PrimeNrdPipelineInfo& destination = context.pipelines[pipelineIndex];
            destination.spirvAddress = reinterpret_cast<uint64_t>(source.computeShaderSPIRV.bytecode);
            destination.spirvSize = source.computeShaderSPIRV.size;
            destination.rangesAddress = reinterpret_cast<uint64_t>(ranges.data());
            destination.rangesNum = static_cast<uint32_t>(ranges.size());
            destination.hasConstantData = source.hasConstantData ? 1u : 0u;
            std::memcpy(destination.shaderIdentifier, source.shaderIdentifier, sizeof(destination.shaderIdentifier));
            destination.shaderIdentifier[sizeof(destination.shaderIdentifier) - 1] = '\0';
        }

        PrimeNrdDescription& description = context.description;
        description.abiVersion = PRIME_NRD_ABI_VERSION;
        description.nrdVersion = PackVersion(library->versionMajor, library->versionMinor, library->versionBuild);
        description.samplerOffset = library->spirvBindingOffsets.samplerOffset;
        description.textureOffset = library->spirvBindingOffsets.textureOffset;
        description.constantBufferOffset = library->spirvBindingOffsets.constantBufferOffset;
        description.storageTextureOffset = library->spirvBindingOffsets.storageTextureAndBufferOffset;
        description.constantBufferRegisterIndex = instance->constantBufferRegisterIndex;
        description.samplersBaseRegisterIndex = instance->samplersBaseRegisterIndex;
        description.resourcesBaseRegisterIndex = instance->resourcesBaseRegisterIndex;
        description.constantBufferMaxDataSize = instance->constantBufferMaxDataSize;
        description.samplersAddress = reinterpret_cast<uint64_t>(context.samplers.data());
        description.samplersNum = static_cast<uint32_t>(context.samplers.size());
        description.pipelinesNum = static_cast<uint32_t>(context.pipelines.size());
        description.pipelinesAddress = reinterpret_cast<uint64_t>(context.pipelines.data());
        description.permanentPoolSize = static_cast<uint32_t>(context.permanentPool.size());
        description.transientPoolSize = static_cast<uint32_t>(context.transientPool.size());
        description.permanentPoolAddress = reinterpret_cast<uint64_t>(context.permanentPool.data());
        description.transientPoolAddress = reinterpret_cast<uint64_t>(context.transientPool.data());
        description.setsMaxNum = instance->descriptorPoolDesc.setsMaxNum;
        description.constantBufferAndSamplersSpaceIndex =
            instance->constantBufferAndSamplersSpaceIndex;
        description.resourcesSpaceIndex = instance->resourcesSpaceIndex;
        description.normalEncoding = static_cast<uint32_t>(library->normalEncoding);
        const size_t entryPointLength = std::min(
            std::strlen(instance->shaderEntryPoint),
            sizeof(description.shaderEntryPoint) - 1);
        std::memcpy(
            description.shaderEntryPoint,
            instance->shaderEntryPoint,
            entryPointLength);
        description.shaderEntryPoint[entryPointLength] = '\0';
        return true;
    }
}

static_assert(sizeof(PrimeNrdCreateDesc) == 12);
static_assert(sizeof(PrimeNrdTextureInfo) == 8);
static_assert(sizeof(PrimeNrdPipelineRangeInfo) == 8);
static_assert(sizeof(PrimeNrdPipelineInfo) == 288);
static_assert(sizeof(PrimeNrdDescription) == 136);
static_assert(sizeof(PrimeNrdFrameSettings) == 320);
static_assert(sizeof(PrimeNrdResourceInfo) == 16);
static_assert(sizeof(PrimeNrdDispatchInfo) == 48);
static_assert(sizeof(PrimeNrdDispatchList) == 16);

PRIME_NRD_EXPORT uint32_t primeNrdGetAbiVersion()
{
    return PRIME_NRD_ABI_VERSION;
}

PRIME_NRD_EXPORT int32_t primeNrdCreate(
    const PrimeNrdCreateDesc* createDesc,
    PrimeNrdContext** output)
{
    if (createDesc == nullptr || output == nullptr || createDesc->width == 0
        || createDesc->height == 0 || createDesc->reserved != 0)
        return -1;

    *output = nullptr;
    PrimeNrdContext* context = new (std::nothrow) PrimeNrdContext();
    if (context == nullptr)
        return -2;

    const std::array<nrd::DenoiserDesc, 3> denoisers = {{
        {PRIME_NRD_PRIMARY_REBLUR_ID, nrd::Denoiser::REBLUR_DIFFUSE_SPECULAR_SH},
        {PRIME_NRD_SIGMA_SUN_ID, nrd::Denoiser::SIGMA_SHADOW},
        {PRIME_NRD_REFLECTION_REBLUR_ID, nrd::Denoiser::REBLUR_DIFFUSE_SPECULAR_SH},
    }};
    const nrd::InstanceCreationDesc creation = {
        {}, denoisers.data(), static_cast<uint32_t>(denoisers.size())};
    nrd::Result result = nrd::CreateInstance(creation, context->instance);
    if (result != nrd::Result::SUCCESS || !BuildDescription(*context))
    {
        delete context;
        return static_cast<int32_t>(result == nrd::Result::SUCCESS ? nrd::Result::FAILURE : result);
    }

    nrd::ReblurSettings settings = {};
    settings.hitDistanceReconstructionMode = nrd::HitDistanceReconstructionMode::AREA_5X5;
    settings.diffusePrepassBlurRadius = 30.0f;
    settings.specularPrepassBlurRadius = 50.0f;
    settings.maxAccumulatedFrameNum = 63;
    settings.maxFastAccumulatedFrameNum = 10;
    settings.maxStabilizedFrameNum = 63;
    settings.historyFixFrameNum = 4;
    // Tighten REBLUR's documented sporadic-outlier rejection for Prime's 1 spp continuation
    // signal. This remains a denoiser-only bias and never feeds the reference integrator.
    settings.fireflySuppressorMinRelativeScale = 1.5f;
    settings.minMaterialForDiffuse = 0.0f;
    settings.minMaterialForSpecular = 0.0f;
    result = nrd::SetDenoiserSettings(
        *context->instance, PRIME_NRD_PRIMARY_REBLUR_ID, &settings);
    if (result == nrd::Result::SUCCESS)
    {
        nrd::ReblurSettings reflectionSettings = settings;
        // The second REBLUR contains only transparent-interface reflection. Its smooth water and
        // glass highlights are view dependent, so retaining the general 63-frame stabilization
        // produces visible trails even with correct virtual motion. NRD 4.17 explicitly provides
        // responsive accumulation for this case; rough reflections retain the long main history.
        reflectionSettings.responsiveAccumulationSettings.roughnessThreshold = 0.1f;
        reflectionSettings.responsiveAccumulationSettings.minAccumulatedFrameNum = 3;
        reflectionSettings.maxStabilizedFrameNum = 10;
        result = nrd::SetDenoiserSettings(
            *context->instance, PRIME_NRD_REFLECTION_REBLUR_ID, &reflectionSettings);
    }
    if (result != nrd::Result::SUCCESS)
    {
        delete context;
        return static_cast<int32_t>(result);
    }

    *output = context;
    return 0;
}

PRIME_NRD_EXPORT int32_t primeNrdGetDescription(
    const PrimeNrdContext* context,
    PrimeNrdDescription* output)
{
    if (context == nullptr || output == nullptr)
        return -1;
    *output = context->description;
    return 0;
}

PRIME_NRD_EXPORT int32_t primeNrdSetFrameSettings(
    PrimeNrdContext* context,
    const PrimeNrdFrameSettings* input)
{
    if (context == nullptr || input == nullptr || input->width == 0 || input->height == 0)
        return -1;

    nrd::CommonSettings settings = {};
    std::memcpy(settings.viewToClipMatrix, input->viewToClip, sizeof(input->viewToClip));
    std::memcpy(settings.viewToClipMatrixPrev, input->viewToClipPrev, sizeof(input->viewToClipPrev));
    std::memcpy(settings.worldToViewMatrix, input->worldToView, sizeof(input->worldToView));
    std::memcpy(settings.worldToViewMatrixPrev, input->worldToViewPrev, sizeof(input->worldToViewPrev));
    std::memcpy(settings.cameraJitter, input->cameraJitter, sizeof(input->cameraJitter));
    std::memcpy(settings.cameraJitterPrev, input->cameraJitterPrev, sizeof(input->cameraJitterPrev));
    settings.motionVectorScale[0] = 1.0f / static_cast<float>(input->width);
    settings.motionVectorScale[1] = 1.0f / static_cast<float>(input->height);
    settings.motionVectorScale[2] = 1.0f;
    settings.resourceSize[0] = static_cast<uint16_t>(input->width);
    settings.resourceSize[1] = static_cast<uint16_t>(input->height);
    settings.resourceSizePrev[0] = static_cast<uint16_t>(input->previousWidth);
    settings.resourceSizePrev[1] = static_cast<uint16_t>(input->previousHeight);
    settings.rectSize[0] = static_cast<uint16_t>(input->width);
    settings.rectSize[1] = static_cast<uint16_t>(input->height);
    settings.rectSizePrev[0] = static_cast<uint16_t>(input->previousWidth);
    settings.rectSizePrev[1] = static_cast<uint16_t>(input->previousHeight);
    settings.timeDeltaBetweenFrames = std::max(input->timeDeltaMilliseconds, 0.0f);
    settings.denoisingRange = std::max(input->denoisingRange, 1.0f);
    settings.frameIndex = input->frameIndex;
    settings.accumulationMode = input->restart != 0
        ? nrd::AccumulationMode::RESTART
        : nrd::AccumulationMode::CONTINUE;
    settings.isMotionVectorInWorldSpace = false;
    settings.enableValidation = input->enableValidation != 0;

    nrd::SigmaSettings sigmaSettings = {};
    std::memcpy(
        sigmaSettings.lightDirection,
        input->sunDirection,
        sizeof(sigmaSettings.lightDirection));
    const nrd::Result sigmaResult = nrd::SetDenoiserSettings(
        *context->instance, PRIME_NRD_SIGMA_SUN_ID, &sigmaSettings);
    if (sigmaResult != nrd::Result::SUCCESS)
        return static_cast<int32_t>(sigmaResult);

    return static_cast<int32_t>(nrd::SetCommonSettings(*context->instance, settings));
}

PRIME_NRD_EXPORT int32_t primeNrdGetDispatches(
    PrimeNrdContext* context,
    PrimeNrdDispatchList* output)
{
    if (context == nullptr || output == nullptr)
        return -1;

    // Keep the primary/transmission REBLUR last so validation follows the main beauty history.
    const std::array<nrd::Identifier, 3> identifiers = {
        PRIME_NRD_SIGMA_SUN_ID,
        PRIME_NRD_REFLECTION_REBLUR_ID,
        PRIME_NRD_PRIMARY_REBLUR_ID,
    };
    const nrd::DispatchDesc* sourceDispatches = nullptr;
    uint32_t dispatchCount = 0;
    const nrd::Result result = nrd::GetComputeDispatches(
        *context->instance,
        identifiers.data(),
        static_cast<uint32_t>(identifiers.size()),
        sourceDispatches,
        dispatchCount);
    if (result != nrd::Result::SUCCESS)
        return static_cast<int32_t>(result);

    // Dispatch topology is stable; resizing in place preserves inner resource capacity per frame.
    context->dispatchResources.resize(dispatchCount);
    context->dispatches.resize(dispatchCount);
    for (uint32_t dispatchIndex = 0; dispatchIndex < dispatchCount; dispatchIndex++)
    {
        const nrd::DispatchDesc& source = sourceDispatches[dispatchIndex];
        std::vector<PrimeNrdResourceInfo>& resources = context->dispatchResources[dispatchIndex];
        resources.resize(source.resourcesNum);
        for (uint32_t resourceIndex = 0; resourceIndex < source.resourcesNum; resourceIndex++)
        {
            resources[resourceIndex].descriptorType =
                static_cast<uint32_t>(source.resources[resourceIndex].descriptorType);
            resources[resourceIndex].resourceType =
                static_cast<uint32_t>(source.resources[resourceIndex].type);
            resources[resourceIndex].indexInPool = source.resources[resourceIndex].indexInPool;
        }

        PrimeNrdDispatchInfo& destination = context->dispatches[dispatchIndex];
        destination.nameAddress = reinterpret_cast<uint64_t>(source.name);
        destination.resourcesAddress = reinterpret_cast<uint64_t>(resources.data());
        destination.constantDataAddress = reinterpret_cast<uint64_t>(source.constantBufferData);
        destination.resourcesNum = static_cast<uint32_t>(resources.size());
        destination.constantDataSize = source.constantBufferDataSize;
        destination.pipelineIndex = source.pipelineIndex;
        destination.gridWidth = source.gridWidth;
        destination.gridHeight = source.gridHeight;
        destination.reserved = source.identifier;
    }

    output->dispatchesAddress = reinterpret_cast<uint64_t>(context->dispatches.data());
    output->dispatchesNum = static_cast<uint32_t>(context->dispatches.size());
    output->reserved = 0;
    return 0;
}

PRIME_NRD_EXPORT void primeNrdDestroy(PrimeNrdContext* context)
{
    delete context;
}
