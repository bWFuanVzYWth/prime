#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iterator>
#include <new>

#include <vulkan/vulkan.h>
#include "nvsdk_ngx_helpers_vk.h"
#include "nvsdk_ngx_helpers_dlssd.h"
#include "nvsdk_ngx_helpers_dlssd_vk.h"

#if defined(_WIN32)
#define PRIME_EXPORT extern "C" __declspec(dllexport)
#else
#define PRIME_EXPORT extern "C"
#endif

namespace {

constexpr std::uint32_t PRIME_DLSS_RR_ABI_VERSION = 10;
constexpr auto PRIME_DLSS_RR_RENDER_PRESET = NVSDK_NGX_RayReconstruction_Hint_Render_Preset_F;
constexpr char PROJECT_ID[] = "7bc01faf-de5e-4c7c-9936-43cb5c301232";
constexpr std::uint32_t EXTENSION_NAME_STRIDE = 256;

struct PrimeExtensionQuery {
    std::uint64_t instance;
    std::uint64_t physicalDevice;
    std::uint32_t capacity;
    std::uint32_t count;
    char* names;
    const wchar_t* featurePath;
    const wchar_t* applicationDataPath;
    const char* engineVersion;
};

struct PrimeInitDescription {
    std::uint64_t instance;
    std::uint64_t physicalDevice;
    std::uint64_t device;
    const wchar_t* featurePath;
    const wchar_t* applicationDataPath;
    const char* engineVersion;
    void** outputContext;
};

struct PrimeOptimalSettings {
    void* context;
    std::uint32_t outputWidth;
    std::uint32_t outputHeight;
    std::int32_t quality;
    std::uint32_t renderWidth;
    std::uint32_t renderHeight;
};

struct PrimeFeatureDescription {
    void* context;
    std::uint64_t commandBuffer;
    std::uint32_t renderWidth;
    std::uint32_t renderHeight;
    std::uint32_t outputWidth;
    std::uint32_t outputHeight;
    std::int32_t quality;
    std::uint32_t reserved;
    void** outputFeature;
};

struct PrimeImage {
    std::uint64_t image;
    std::uint64_t view;
    std::int32_t format;
    std::uint32_t width;
    std::uint32_t height;
    std::uint32_t reserved;
};

enum ImageIndex : std::size_t {
    DIFFUSE_ALBEDO,
    SPECULAR_ALBEDO,
    NORMAL_ROUGHNESS,
    INPUT_COLOR,
    OUTPUT_COLOR,
    LINEAR_DEPTH,
    MOTION_VECTORS,
    SPECULAR_MOTION_VECTORS,
    SPECULAR_HIT_DISTANCE,
    RESPONSIVITY,
    IMAGE_COUNT
};

struct PrimeEvaluateDescription {
    void* feature;
    std::uint64_t commandBuffer;
    std::uint32_t renderWidth;
    std::uint32_t renderHeight;
    float jitterX;
    float jitterY;
    float motionScaleX;
    float motionScaleY;
    std::int32_t reset;
    float frameTimeMilliseconds;
    float worldToView[16];
    float viewToClip[16];
    PrimeImage images[IMAGE_COUNT];
};

static_assert(sizeof(PrimeExtensionQuery) == 56);
static_assert(sizeof(PrimeInitDescription) == 56);
static_assert(sizeof(PrimeOptimalSettings) == 32);
static_assert(sizeof(PrimeFeatureDescription) == 48);
static_assert(sizeof(PrimeImage) == 32);
static_assert(sizeof(PrimeEvaluateDescription) == 496);

struct Context {
    VkDevice device{};
    NVSDK_NGX_Parameter* capabilities{};
};

struct Feature {
    Context* context{};
    NVSDK_NGX_Handle* handle{};
    NVSDK_NGX_Parameter* parameters{};
    std::uint32_t renderWidth{};
    std::uint32_t renderHeight{};
    std::uint32_t outputWidth{};
    std::uint32_t outputHeight{};
};

bool validQuality(std::int32_t quality) {
    switch (quality) {
        case NVSDK_NGX_PerfQuality_Value_MaxPerf:
        case NVSDK_NGX_PerfQuality_Value_Balanced:
        case NVSDK_NGX_PerfQuality_Value_MaxQuality:
        case NVSDK_NGX_PerfQuality_Value_UltraPerformance:
        case NVSDK_NGX_PerfQuality_Value_DLAA:
            return true;
        default:
            return false;
    }
}

bool finiteMatrix(const float (&matrix)[16]) {
    return std::all_of(std::begin(matrix), std::end(matrix), [](float value) {
        return std::isfinite(value);
    });
}

bool validImage(
        const PrimeImage& image,
        VkFormat format,
        std::uint32_t width,
        std::uint32_t height) {
    return image.image != 0
            && image.view != 0
            && image.format == format
            && image.width == width
            && image.height == height
            && image.reserved == 0;
}

bool absentImage(const PrimeImage& image) {
    // The private ABI reserves an all-zero descriptor as explicit absence for optional inputs.
    return image.image == 0
            && image.view == 0
            && image.format == 0
            && image.width == 0
            && image.height == 0
            && image.reserved == 0;
}

NVSDK_NGX_FeatureCommonInfo makeFeatureInfo(
        const wchar_t* featurePath,
        const wchar_t** pathStorage) {
    NVSDK_NGX_FeatureCommonInfo info{};
    if (featurePath != nullptr && featurePath[0] != L'\0') {
        *pathStorage = featurePath;
        info.PathListInfo.Path = pathStorage;
        info.PathListInfo.Length = 1;
    }
    return info;
}

NVSDK_NGX_FeatureDiscoveryInfo makeDiscoveryInfo(
        const PrimeExtensionQuery& query,
        const NVSDK_NGX_FeatureCommonInfo* featureInfo) {
    NVSDK_NGX_FeatureDiscoveryInfo discovery{};
    discovery.SDKVersion = NVSDK_NGX_Version_API;
    discovery.FeatureID = NVSDK_NGX_Feature_RayReconstruction;
    discovery.Identifier.IdentifierType = NVSDK_NGX_Application_Identifier_Type_Project_Id;
    discovery.Identifier.v.ProjectDesc.ProjectId = PROJECT_ID;
    discovery.Identifier.v.ProjectDesc.EngineType = NVSDK_NGX_ENGINE_TYPE_CUSTOM;
    discovery.Identifier.v.ProjectDesc.EngineVersion = query.engineVersion;
    discovery.ApplicationDataPath = query.applicationDataPath;
    discovery.FeatureInfo = featureInfo;
    return discovery;
}

int copyExtensions(
        const VkExtensionProperties* properties,
        std::uint32_t count,
        PrimeExtensionQuery& output) {
    output.count = count;
    if (count > output.capacity || (count != 0 && output.names == nullptr)) {
        return -2;
    }
    for (std::uint32_t index = 0; index < count; ++index) {
        char* destination = output.names + static_cast<std::size_t>(index) * EXTENSION_NAME_STRIDE;
        std::memset(destination, 0, EXTENSION_NAME_STRIDE);
        std::memcpy(
                destination,
                properties[index].extensionName,
                std::min<std::size_t>(
                        std::strlen(properties[index].extensionName),
                        EXTENSION_NAME_STRIDE - 1));
    }
    return 0;
}

NVSDK_NGX_Resource_VK imageResource(const PrimeImage& image, bool readWrite) {
    VkImageSubresourceRange range{};
    range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    range.baseMipLevel = 0;
    range.levelCount = 1;
    range.baseArrayLayer = 0;
    range.layerCount = 1;
    return NVSDK_NGX_Create_ImageView_Resource_VK(
            reinterpret_cast<VkImageView>(image.view),
            reinterpret_cast<VkImage>(image.image),
            range,
            static_cast<VkFormat>(image.format),
            image.width,
            image.height,
            readWrite);
}

bool succeeded(NVSDK_NGX_Result result) {
    return NVSDK_NGX_SUCCEED(result);
}

} // namespace

PRIME_EXPORT std::uint32_t primeDlssRrGetAbiVersion() {
    return PRIME_DLSS_RR_ABI_VERSION;
}

PRIME_EXPORT std::uint32_t primeDlssRrGetRenderPreset() {
    return static_cast<std::uint32_t>(PRIME_DLSS_RR_RENDER_PRESET);
}

PRIME_EXPORT int primeDlssRrGetInstanceExtensions(PrimeExtensionQuery* query) {
    if (query == nullptr) {
        return -1;
    }
    query->count = 0;
    if (query->engineVersion == nullptr || query->engineVersion[0] == '\0') {
        return -1;
    }
    const wchar_t* path = nullptr;
    NVSDK_NGX_FeatureCommonInfo featureInfo = makeFeatureInfo(query->featurePath, &path);
    NVSDK_NGX_FeatureDiscoveryInfo discovery = makeDiscoveryInfo(*query, &featureInfo);
    std::uint32_t count = 0;
    VkExtensionProperties* properties = nullptr;
    NVSDK_NGX_Result result = NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(
            &discovery, &count, &properties);
    if (!succeeded(result)) {
        return static_cast<int>(result);
    }
    return copyExtensions(properties, count, *query);
}

PRIME_EXPORT int primeDlssRrGetDeviceExtensions(PrimeExtensionQuery* query) {
    if (query == nullptr) {
        return -1;
    }
    query->count = 0;
    if (query->instance == 0 || query->physicalDevice == 0
            || query->engineVersion == nullptr || query->engineVersion[0] == '\0') {
        return -1;
    }
    const wchar_t* path = nullptr;
    NVSDK_NGX_FeatureCommonInfo featureInfo = makeFeatureInfo(query->featurePath, &path);
    NVSDK_NGX_FeatureDiscoveryInfo discovery = makeDiscoveryInfo(*query, &featureInfo);
    std::uint32_t count = 0;
    VkExtensionProperties* properties = nullptr;
    NVSDK_NGX_Result result = NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements(
            reinterpret_cast<VkInstance>(query->instance),
            reinterpret_cast<VkPhysicalDevice>(query->physicalDevice),
            &discovery,
            &count,
            &properties);
    if (!succeeded(result)) {
        return static_cast<int>(result);
    }
    return copyExtensions(properties, count, *query);
}

PRIME_EXPORT int primeDlssRrInitialize(PrimeInitDescription* description) {
    if (description == nullptr || description->outputContext == nullptr) {
        return -1;
    }
    *description->outputContext = nullptr;
    if (description->instance == 0 || description->physicalDevice == 0
            || description->device == 0 || description->engineVersion == nullptr
            || description->engineVersion[0] == '\0') {
        return -1;
    }
    const wchar_t* path = nullptr;
    NVSDK_NGX_FeatureCommonInfo featureInfo = makeFeatureInfo(description->featurePath, &path);
    VkDevice device = reinterpret_cast<VkDevice>(description->device);
    NVSDK_NGX_Result result = NVSDK_NGX_VULKAN_Init_with_ProjectID(
            PROJECT_ID,
            NVSDK_NGX_ENGINE_TYPE_CUSTOM,
            description->engineVersion,
            description->applicationDataPath,
            reinterpret_cast<VkInstance>(description->instance),
            reinterpret_cast<VkPhysicalDevice>(description->physicalDevice),
            device,
            nullptr,
            nullptr,
            &featureInfo,
            NVSDK_NGX_Version_API);
    if (!succeeded(result)) {
        return static_cast<int>(result);
    }

    auto* context = new (std::nothrow) Context();
    if (context == nullptr) {
        NVSDK_NGX_VULKAN_Shutdown1(device);
        return -3;
    }
    context->device = device;
    result = NVSDK_NGX_VULKAN_GetCapabilityParameters(&context->capabilities);
    if (!succeeded(result)) {
        delete context;
        NVSDK_NGX_VULKAN_Shutdown1(device);
        return static_cast<int>(result);
    }
    int available = 0;
    result = NVSDK_NGX_Parameter_GetI(
            context->capabilities,
            NVSDK_NGX_Parameter_SuperSamplingDenoising_Available,
            &available);
    if (!succeeded(result) || available == 0) {
        NVSDK_NGX_VULKAN_DestroyParameters(context->capabilities);
        delete context;
        NVSDK_NGX_VULKAN_Shutdown1(device);
        return succeeded(result) ? -4 : static_cast<int>(result);
    }
    *description->outputContext = context;
    return 0;
}

PRIME_EXPORT int primeDlssRrGetOptimalSettings(PrimeOptimalSettings* settings) {
    if (settings == nullptr) {
        return -1;
    }
    settings->renderWidth = 0;
    settings->renderHeight = 0;
    if (settings->context == nullptr
            || settings->outputWidth == 0 || settings->outputHeight == 0
            || !validQuality(settings->quality)) {
        return -1;
    }
    auto* context = static_cast<Context*>(settings->context);
    std::uint32_t maxWidth = 0;
    std::uint32_t maxHeight = 0;
    std::uint32_t minWidth = 0;
    std::uint32_t minHeight = 0;
    float ignoredSharpness = 0.0F;
    NVSDK_NGX_Result result = NGX_DLSSD_GET_OPTIMAL_SETTINGS(
            context->capabilities,
            settings->outputWidth,
            settings->outputHeight,
            static_cast<NVSDK_NGX_PerfQuality_Value>(settings->quality),
            &settings->renderWidth,
            &settings->renderHeight,
            &maxWidth,
            &maxHeight,
            &minWidth,
            &minHeight,
            &ignoredSharpness);
    return succeeded(result) ? 0 : static_cast<int>(result);
}

PRIME_EXPORT int primeDlssRrCreateFeature(PrimeFeatureDescription* description) {
    if (description == nullptr || description->outputFeature == nullptr) {
        return -1;
    }
    *description->outputFeature = nullptr;
    if (description->context == nullptr || description->commandBuffer == 0
            || description->renderWidth == 0 || description->renderHeight == 0
            || description->outputWidth == 0 || description->outputHeight == 0
            || description->renderWidth > description->outputWidth
            || description->renderHeight > description->outputHeight
            || description->reserved != 0
            || !validQuality(description->quality)) {
        return -1;
    }
    auto* context = static_cast<Context*>(description->context);
    auto* feature = new (std::nothrow) Feature();
    if (feature == nullptr) {
        return -3;
    }
    feature->context = context;
    NVSDK_NGX_Result result = NVSDK_NGX_VULKAN_AllocateParameters(&feature->parameters);
    if (!succeeded(result)) {
        delete feature;
        return static_cast<int>(result);
    }
    NVSDK_NGX_Parameter_SetUI(feature->parameters, NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_DLAA, PRIME_DLSS_RR_RENDER_PRESET);
    NVSDK_NGX_Parameter_SetUI(feature->parameters, NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Quality, PRIME_DLSS_RR_RENDER_PRESET);
    NVSDK_NGX_Parameter_SetUI(feature->parameters, NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Balanced, PRIME_DLSS_RR_RENDER_PRESET);
    NVSDK_NGX_Parameter_SetUI(feature->parameters, NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Performance, PRIME_DLSS_RR_RENDER_PRESET);
    NVSDK_NGX_Parameter_SetUI(feature->parameters, NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_UltraPerformance, PRIME_DLSS_RR_RENDER_PRESET);

    NVSDK_NGX_DLSSD_Create_Params create{};
    create.InDenoiseMode = NVSDK_NGX_DLSS_Denoise_Mode_DLUnified;
    create.InRoughnessMode = NVSDK_NGX_DLSS_Roughness_Mode_Packed;
    create.InUseHWDepth = NVSDK_NGX_DLSS_Depth_Type_Linear;
    create.InWidth = description->renderWidth;
    create.InHeight = description->renderHeight;
    create.InTargetWidth = description->outputWidth;
    create.InTargetHeight = description->outputHeight;
    create.InPerfQualityValue = static_cast<NVSDK_NGX_PerfQuality_Value>(description->quality);
    create.InFeatureCreateFlags = NVSDK_NGX_DLSS_Feature_Flags_IsHDR
            | NVSDK_NGX_DLSS_Feature_Flags_MVLowRes;
    create.InEnableOutputSubrects = false;
    result = NGX_VULKAN_CREATE_DLSSD_EXT1(
            context->device,
            reinterpret_cast<VkCommandBuffer>(description->commandBuffer),
            1,
            1,
            &feature->handle,
            feature->parameters,
            &create);
    if (!succeeded(result)) {
        NVSDK_NGX_VULKAN_DestroyParameters(feature->parameters);
        delete feature;
        return static_cast<int>(result);
    }
    feature->renderWidth = description->renderWidth;
    feature->renderHeight = description->renderHeight;
    feature->outputWidth = description->outputWidth;
    feature->outputHeight = description->outputHeight;
    *description->outputFeature = feature;
    return 0;
}

PRIME_EXPORT int primeDlssRrEvaluate(PrimeEvaluateDescription* description) {
    if (description == nullptr || description->feature == nullptr
            || description->commandBuffer == 0) {
        return -1;
    }
    auto* feature = static_cast<Feature*>(description->feature);
    const bool validScalars = description->renderWidth == feature->renderWidth
            && description->renderHeight == feature->renderHeight
            && std::isfinite(description->jitterX)
            && std::isfinite(description->jitterY)
            && std::abs(description->jitterX) <= 0.5F
            && std::abs(description->jitterY) <= 0.5F
            && description->motionScaleX == static_cast<float>(feature->renderWidth)
            && description->motionScaleY == static_cast<float>(feature->renderHeight)
            && std::isfinite(description->frameTimeMilliseconds)
            && description->frameTimeMilliseconds >= 0.0F
            && (description->reset == 0 || description->reset == 1)
            && finiteMatrix(description->worldToView)
            && finiteMatrix(description->viewToClip);
    const bool validImages = validImage(
                    description->images[DIFFUSE_ALBEDO],
                    VK_FORMAT_R16G16B16A16_SFLOAT,
                    feature->renderWidth,
                    feature->renderHeight)
            && validImage(
                    description->images[SPECULAR_ALBEDO],
                    VK_FORMAT_R16G16B16A16_SFLOAT,
                    feature->renderWidth,
                    feature->renderHeight)
            && validImage(
                    description->images[NORMAL_ROUGHNESS],
                    VK_FORMAT_R32G32B32A32_SFLOAT,
                    feature->renderWidth,
                    feature->renderHeight)
            && validImage(
                    description->images[INPUT_COLOR],
                    VK_FORMAT_R16G16B16A16_SFLOAT,
                    feature->renderWidth,
                    feature->renderHeight)
            && validImage(
                    description->images[OUTPUT_COLOR],
                    VK_FORMAT_R16G16B16A16_SFLOAT,
                    feature->outputWidth,
                    feature->outputHeight)
            && validImage(
                    description->images[LINEAR_DEPTH],
                    VK_FORMAT_R32_SFLOAT,
                    feature->renderWidth,
                    feature->renderHeight)
            && validImage(
                    description->images[MOTION_VECTORS],
                    VK_FORMAT_R32G32_SFLOAT,
                    feature->renderWidth,
                    feature->renderHeight)
            && validImage(
                    description->images[SPECULAR_MOTION_VECTORS],
                    VK_FORMAT_R32G32_SFLOAT,
                    feature->renderWidth,
                    feature->renderHeight)
            && validImage(
                    description->images[SPECULAR_HIT_DISTANCE],
                    VK_FORMAT_R16_SFLOAT,
                    feature->renderWidth,
                    feature->renderHeight)
            && (absentImage(description->images[RESPONSIVITY])
                    || validImage(
                            description->images[RESPONSIVITY],
                            VK_FORMAT_R16_SFLOAT,
                            feature->renderWidth,
                            feature->renderHeight));
    if (!validScalars || !validImages) {
        return -2;
    }
    std::array<NVSDK_NGX_Resource_VK, IMAGE_COUNT> resources{};
    for (std::size_t index = 0; index < RESPONSIVITY; ++index) {
        resources[index] = imageResource(
                description->images[index], index == OUTPUT_COLOR);
    }
    const bool hasResponsivity = !absentImage(description->images[RESPONSIVITY]);
    if (hasResponsivity) {
        resources[RESPONSIVITY] = imageResource(
                description->images[RESPONSIVITY], false);
    }
    NVSDK_NGX_VK_DLSSD_Eval_Params evaluate{};
    evaluate.pInDiffuseAlbedo = &resources[DIFFUSE_ALBEDO];
    evaluate.pInSpecularAlbedo = &resources[SPECULAR_ALBEDO];
    evaluate.pInNormals = &resources[NORMAL_ROUGHNESS];
    evaluate.pInRoughness = nullptr;
    evaluate.pInColor = &resources[INPUT_COLOR];
    evaluate.pInOutput = &resources[OUTPUT_COLOR];
    evaluate.pInDepth = &resources[LINEAR_DEPTH];
    evaluate.pInMotionVectors = &resources[MOTION_VECTORS];
    evaluate.pInMotionVectorsReflections = &resources[SPECULAR_MOTION_VECTORS];
    // Prime's transparent-primary split is not a raster overlay, so there is no truthful
    // pre-transparency snapshot. Reflection MV transports history while hit distance independently
    // preserves spatial separation between reflected surfaces.
    evaluate.pInColorBeforeTransparency = nullptr;
    evaluate.pInSpecularHitDistance = &resources[SPECULAR_HIT_DISTANCE];
    evaluate.pInResponsivityMask = hasResponsivity ? &resources[RESPONSIVITY] : nullptr;
    evaluate.pInWorldToViewMatrix = description->worldToView;
    evaluate.pInViewToClipMatrix = description->viewToClip;
    evaluate.InJitterOffsetX = description->jitterX;
    evaluate.InJitterOffsetY = description->jitterY;
    evaluate.InMVScaleX = description->motionScaleX;
    evaluate.InMVScaleY = description->motionScaleY;
    evaluate.InReset = description->reset;
    evaluate.InFrameTimeDeltaInMsec = description->frameTimeMilliseconds;
    evaluate.InRenderSubrectDimensions.Width = description->renderWidth;
    evaluate.InRenderSubrectDimensions.Height = description->renderHeight;
    evaluate.InPreExposure = 1.0F;
    evaluate.InExposureScale = 1.0F;
    NVSDK_NGX_Result result = NGX_VULKAN_EVALUATE_DLSSD_EXT(
            reinterpret_cast<VkCommandBuffer>(description->commandBuffer),
            feature->handle,
            feature->parameters,
            &evaluate);
    return succeeded(result) ? 0 : static_cast<int>(result);
}

PRIME_EXPORT int primeDlssRrReleaseFeature(void* pointer) {
    if (pointer == nullptr) {
        return 0;
    }
    auto* feature = static_cast<Feature*>(pointer);
    NVSDK_NGX_Result releaseResult = NVSDK_NGX_VULKAN_ReleaseFeature(feature->handle);
    NVSDK_NGX_Result parameterResult = NVSDK_NGX_VULKAN_DestroyParameters(feature->parameters);
    delete feature;
    if (!succeeded(releaseResult)) {
        return static_cast<int>(releaseResult);
    }
    return succeeded(parameterResult) ? 0 : static_cast<int>(parameterResult);
}

PRIME_EXPORT int primeDlssRrShutdown(void* pointer) {
    if (pointer == nullptr) {
        return 0;
    }
    auto* context = static_cast<Context*>(pointer);
    NVSDK_NGX_Result parameterResult = NVSDK_NGX_VULKAN_DestroyParameters(context->capabilities);
    NVSDK_NGX_Result shutdownResult = NVSDK_NGX_VULKAN_Shutdown1(context->device);
    delete context;
    if (!succeeded(parameterResult)) {
        return static_cast<int>(parameterResult);
    }
    return succeeded(shutdownResult) ? 0 : static_cast<int>(shutdownResult);
}
