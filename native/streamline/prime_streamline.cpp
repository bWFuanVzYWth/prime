#include "prime_streamline.h"

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>

#include "sl.h"
#include "sl_dlss_d.h"
#include "sl_dlss_g.h"
#include "sl_reflex.h"
#include "sl_pcl.h"
#include "sl_helpers_vk.h"

namespace {
static_assert(sizeof(PrimeSlInit) == 32);
static_assert(sizeof(PrimeSlImage) == 48);
static_assert(sizeof(PrimeSlFrame) == 904);
static_assert(offsetof(PrimeSlFrame, images) == 472);
static_assert(sizeof(PrimeSlFgFrame) == 216);

thread_local char error_text[512]{};
thread_local bool evaluating{};
thread_local bool evaluation_logged_error{};
std::mutex api_mutex;

void log_message(sl::LogType type, const char *message) {
    if (evaluating && type == sl::LogType::eError) {
        evaluation_logged_error = true;
        std::snprintf(error_text, sizeof(error_text), "Streamline: %s",
                      message ? message : "error");
    }
}

int32_t fail(const char *message, int32_t code = -1) {
    std::snprintf(error_text, sizeof(error_text), "%s (%d)", message, code);
    return code;
}

int32_t check(sl::Result result, const char *operation) {
    return result == sl::Result::eOk ? 0 : fail(operation, static_cast<int32_t>(result));
}

template <typename T> bool symbol(HMODULE module, const char *name, T *&result) {
    result = reinterpret_cast<T *>(GetProcAddress(module, name));
    return result != nullptr;
}

using BeforePresent = VkResult(VkQueue, const VkPresentInfoKHR *, bool &);
using AfterPresent = VkResult();

struct Context {
    HMODULE module{};
    bool initialized{};
    bool evaluated{};
    bool configured{};
    bool interposed{};
    PFN_vkQueuePresentKHR interposed_present{};
    VkDevice device{};
    PFN_vkWaitSemaphores wait_semaphores{};
    bool fg_supported{}, fg_enabled{}, reflex_enabled{};
    uint32_t logical_frame{};
    sl::FrameToken *frame_token{};
    sl::FrameToken *fg_tag_token{};
    bool frame_constants_set{};
    std::array<unsigned char, offsetof(PrimeSlFrame, images) - offsetof(PrimeSlFrame, reset)>
            frame_camera{};
    PFun_slSetTagForFrame *set_tags{};
    PFun_slDLSSGSetOptions *fg_options{};
    PFun_slDLSSGGetState *fg_state{};
    PFun_slReflexSetOptions *reflex_options{};
    PFun_slReflexSleep *reflex_sleep{};
    PFun_slPCLSetMarker *marker{};
    VkQueue queue{};
    std::wstring directory;
    const wchar_t *plugin_path{};
    sl::Feature feature = sl::kFeatureDLSS_RR;
    sl::ViewportHandle viewport{0};
    sl::DLSSDOptions options;
    PrimeSlSize size{};
    PFun_slInit *init{};
    PFun_slShutdown *shutdown{};
    PFun_slSetVulkanInfo *set_vulkan{};
    PFun_slIsFeatureSupported *supported{};
    PFun_slGetFeatureFunction *get_function{};
    PFun_slGetNewFrameToken *get_token{};
    PFun_slSetConstants *set_constants{};
    PFun_slEvaluateFeature *evaluate{};
    PFun_slFreeResources *free_resources{};
    PFun_slDLSSDSetOptions *set_options{};
    PFun_slDLSSDGetOptimalSettings *optimal{};
    BeforePresent *before_present{};
    AfterPresent *after_present{};

    ~Context() {
        if (initialized)
            shutdown();
        if (module)
            FreeLibrary(module);
    }
};

Context *active{};
// Host Vulkan lifetime outlives any selected world renderer. Early SDK initialization is
// process-owned; renderer retirement frees feature resources without shutting the interposer.
Context *bootstrapped{};

// The loader is process-owned. It must remain available when RR is disabled or
// its context has already been retired but the host continues presenting.
HMODULE vulkan_loader() {
    static HMODULE module = LoadLibraryExW(L"vulkan-1.dll", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
    return module;
}

PFN_vkQueuePresentKHR native_present() {
    static auto function = reinterpret_cast<PFN_vkQueuePresentKHR>(
            GetProcAddress(vulkan_loader(), "vkQueuePresentKHR"));
    return function;
}

bool module_directory(std::wstring &result) {
    HMODULE module{};
    if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                                    GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                            reinterpret_cast<LPCWSTR>(&prime_sl_create), &module))
        return false;
    wchar_t path[32768];
    DWORD count = GetModuleFileNameW(module, path, static_cast<DWORD>(std::size(path)));
    if (!count || count >= std::size(path))
        return false;
    result.assign(path, count);
    auto separator = result.find_last_of(L"\\/");
    if (separator == std::wstring::npos)
        return false;
    result.resize(separator);
    return true;
}

template <typename T>
int32_t feature_function(Context &ctx, sl::Feature feature, const char *name, T *&function) {
    void *address{};
    char operation[160];
    std::snprintf(operation, sizeof(operation), "slGetFeatureFunction(%s)", name);
    auto result = check(ctx.get_function(feature, name, address), operation);
    function = reinterpret_cast<T *>(address);
    return result ? result : (function ? 0 : fail(name));
}

int32_t initialize_context(Context &ctx, bool interposed) {
    if (!module_directory(ctx.directory))
        return fail("Cannot find engine DLL directory");
    ctx.module = LoadLibraryExW((ctx.directory + L"\\sl.interposer.dll").c_str(), nullptr,
                                LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (!ctx.module)
        return fail("Cannot load bundled sl.interposer.dll", -3);
#define LOAD(member, name)                                                                         \
    if (!symbol(ctx.module, #name, ctx.member))                                                    \
    return fail("Missing Streamline export " #name)
    LOAD(init, slInit);
    LOAD(shutdown, slShutdown);
    LOAD(set_vulkan, slSetVulkanInfo);
    LOAD(supported, slIsFeatureSupported);
    LOAD(get_function, slGetFeatureFunction);
    LOAD(get_token, slGetNewFrameToken);
    LOAD(set_constants, slSetConstants);
    LOAD(evaluate, slEvaluateFeature);
    LOAD(free_resources, slFreeResources);
#undef LOAD
    sl::Preferences preferences;
    ctx.plugin_path = ctx.directory.c_str();
    preferences.pathsToPlugins = &ctx.plugin_path;
    preferences.numPathsToPlugins = 1;
    const sl::Feature features[] = {sl::kFeatureDLSS_RR, sl::kFeatureDLSS_G, sl::kFeaturePCL,
                                    sl::kFeatureReflex};
    preferences.featuresToLoad = interposed ? features : &ctx.feature;
    preferences.numFeaturesToLoad = interposed ? 4 : 1;
    preferences.flags =
            sl::PreferenceFlags::eDisableCLStateTracking | sl::PreferenceFlags::eDisableDebugText;
    if (!interposed)
        preferences.flags |= sl::PreferenceFlags::eUseManualHooking;
    preferences.engine = sl::EngineType::eCustom;
    preferences.engineVersion = "prime_pt";
    preferences.projectId = "7bc01faf-de5e-4c7c-9936-43cb5c301232";
    preferences.renderAPI = sl::RenderAPI::eVulkan;
    preferences.logMessageCallback = log_message;
    int32_t result = check(ctx.init(preferences, sl::kSDKVersion), "slInit");
    if (result)
        return result;
    ctx.initialized = true;
    ctx.interposed = interposed;
    return 0;
}

void copy_matrix(sl::float4x4 &destination, const float *source) {
    std::memcpy(&destination, source, sizeof(destination));
}

void identity(sl::float4x4 &destination) {
    for (uint32_t row = 0; row < 4; ++row)
        for (uint32_t col = 0; col < 4; ++col)
            (&destination[row].x)[col] = row == col ? 1.0f : 0.0f;
}

sl::DLSSDOptions make_options(uint32_t width, uint32_t height, uint32_t quality) {
    static constexpr sl::DLSSMode modes[] = {sl::DLSSMode::eDLAA, sl::DLSSMode::eMaxQuality,
                                             sl::DLSSMode::eBalanced, sl::DLSSMode::eMaxPerformance,
                                             sl::DLSSMode::eUltraPerformance};
    sl::DLSSDOptions options;
    options.mode = modes[quality];
    options.outputWidth = width;
    options.outputHeight = height;
    options.normalRoughnessMode = sl::DLSSDNormalRoughnessMode::ePacked;
    options.colorBuffersHDR = sl::Boolean::eTrue;
    options.alphaUpscalingEnabled = sl::Boolean::eTrue;
    options.dlaaPreset = options.qualityPreset = options.balancedPreset =
            options.performancePreset = options.ultraPerformancePreset =
                    options.ultraQualityPreset = sl::DLSSDPreset::ePresetF;
    identity(options.worldToCameraView);
    identity(options.cameraViewToWorld);
    return options;
}

bool absent(const PrimeSlImage &image) {
    const PrimeSlImage empty{};
    return std::memcmp(&image, &empty, sizeof(image)) == 0;
}

bool valid_image(const PrimeSlImage &image, uint32_t format, uint32_t width, uint32_t height) {
    return image.image && image.view && image.width == width && image.height == height &&
           image.format == format && image.layout == VK_IMAGE_LAYOUT_GENERAL && !image.reserved &&
           (image.usage & VK_IMAGE_USAGE_SAMPLED_BIT) && (image.usage & VK_IMAGE_USAGE_STORAGE_BIT);
}

bool valid_frame(const Context &ctx, const PrimeSlFrame &frame) {
    if (!frame.command_buffer || frame.reset > 1)
        return false;
    constexpr size_t count =
            (offsetof(PrimeSlFrame, images) - offsetof(PrimeSlFrame, jitter)) / sizeof(float);
    const auto *values = reinterpret_cast<const char *>(&frame) + offsetof(PrimeSlFrame, jitter);
    for (size_t index = 0; index < count; ++index) {
        float value;
        std::memcpy(&value, values + index * sizeof(float), sizeof(float));
        if (!std::isfinite(value))
            return false;
    }
    if (frame.camera_near <= 0 || frame.camera_far <= frame.camera_near || frame.camera_fov <= 0 ||
        frame.camera_aspect <= 0)
        return false;
    const uint32_t formats[] = {VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R32_SFLOAT,
                                VK_FORMAT_R16G16_SFLOAT,       VK_FORMAT_R16G16B16A16_SFLOAT,
                                VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT,
                                VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16_SFLOAT,
                                VK_FORMAT_R16G16_SFLOAT};
    for (uint32_t index = 0; index < PRIME_SL_IMAGE_COUNT; ++index) {
        if (index == PRIME_SL_SPECULAR_HIT_DISTANCE && absent(frame.images[index]))
            continue;
        const bool output = index == PRIME_SL_OUTPUT;
        if (!valid_image(frame.images[index], formats[index],
                         output ? ctx.options.outputWidth : ctx.size.render_width,
                         output ? ctx.options.outputHeight : ctx.size.render_height))
            return false;
    }
    for (uint32_t index = 0; index < PRIME_SL_IMAGE_COUNT; ++index)
        if (index != PRIME_SL_OUTPUT &&
            frame.images[index].image == frame.images[PRIME_SL_OUTPUT].image)
            return false;
    return true;
}

sl::Constants make_constants(const PrimeSlFrame &frame, PrimeSlSize size) {
    sl::Constants constants;
    copy_matrix(constants.cameraViewToClip, frame.view_to_clip);
    copy_matrix(constants.clipToCameraView, frame.clip_to_view);
    copy_matrix(constants.clipToPrevClip, frame.clip_to_previous_clip);
    copy_matrix(constants.prevClipToClip, frame.previous_clip_to_clip);
    identity(constants.clipToLensClip);
    constants.jitterOffset = {frame.jitter[0], frame.jitter[1]};
    // SL multiplies these by the render extent before passing NGX its MV scale.
    // Both dense motion fields already contain input-pixel displacement.
    constants.mvecScale = {1.0f / size.render_width, 1.0f / size.render_height};
    constants.cameraPinholeOffset = {0.0f, 0.0f};
    constants.cameraPos = {frame.camera_position[0], frame.camera_position[1],
                           frame.camera_position[2]};
    constants.cameraUp = {frame.camera_up[0], frame.camera_up[1], frame.camera_up[2]};
    constants.cameraRight = {frame.camera_right[0], frame.camera_right[1], frame.camera_right[2]};
    constants.cameraFwd = {frame.camera_forward[0], frame.camera_forward[1],
                           frame.camera_forward[2]};
    constants.cameraNear = frame.camera_near;
    constants.cameraFar = frame.camera_far;
    constants.cameraFOV = frame.camera_fov;
    constants.cameraAspectRatio = frame.camera_aspect;
    // DLSSD does not forward motionVectorsInvalidValue to NGX. Unknown motion is
    // handled by the engine's completion mask, never by a magic vector value.
    constants.depthInverted = sl::Boolean::eFalse;
    constants.cameraMotionIncluded = sl::Boolean::eTrue;
    constants.motionVectors3D = sl::Boolean::eFalse;
    constants.motionVectorsJittered = sl::Boolean::eFalse;
    constants.motionVectorsDilated = sl::Boolean::eFalse;
    constants.orthographicProjection = sl::Boolean::eFalse;
    constants.reset = frame.reset ? sl::Boolean::eTrue : sl::Boolean::eFalse;
    return constants;
}

sl::Resource make_resource(const PrimeSlImage &image) {
    sl::Resource resource;
    resource.type = sl::ResourceType::eTex2d;
    resource.native = reinterpret_cast<void *>(image.image);
    resource.view = reinterpret_cast<void *>(image.view);
    resource.memory = reinterpret_cast<void *>(image.memory);
    resource.state = image.layout;
    resource.width = image.width;
    resource.height = image.height;
    resource.nativeFormat = image.format;
    resource.mipLevels = resource.arrayLayers = 1;
    resource.flags = 0;
    resource.usage = image.usage;
    return resource;
}

int32_t set_common_constants(Context &ctx, const PrimeSlFrame &frame, sl::FrameToken &token) {
    const auto *camera =
            reinterpret_cast<const unsigned char *>(&frame) + offsetof(PrimeSlFrame, reset);
    const bool logical = ctx.interposed && ctx.frame_token == &token;
    if (logical && ctx.frame_constants_set) {
        if (std::memcmp(ctx.frame_camera.data(), camera, ctx.frame_camera.size()))
            return fail("RR and FG camera constants differ within one logical frame");
        return 0;
    }
    auto constants = make_constants(frame, ctx.size);
    int32_t result = check(ctx.set_constants(constants, token, ctx.viewport), "slSetConstants");
    if (!result && logical) {
        std::memcpy(ctx.frame_camera.data(), camera, ctx.frame_camera.size());
        ctx.frame_constants_set = true;
    }
    return result;
}

int32_t suspend_fg(Context &ctx) {
    if (!ctx.fg_enabled)
        return 0;
    sl::DLSSGState state;
    int32_t result = check(ctx.fg_state(ctx.viewport, state, nullptr), "slDLSSGGetState(retire)");
    if (result)
        return result;
    if (state.lastPresentInputsProcessingCompletionFenceValue) {
        // Public SDK completion contract; Vulkan compute Fence is a native timeline VkSemaphore.
        auto semaphore = reinterpret_cast<VkSemaphore>(state.inputsProcessingCompletionFence);
        if (!semaphore || !ctx.wait_semaphores)
            return fail("DLSS FG did not publish a usable input-completion fence");
        VkSemaphoreWaitInfo wait{VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO};
        wait.semaphoreCount = 1;
        wait.pSemaphores = &semaphore;
        wait.pValues = &state.lastPresentInputsProcessingCompletionFenceValue;
        VkResult status = ctx.wait_semaphores(ctx.device, &wait, 5'000'000'000ULL);
        if (status != VK_SUCCESS)
            return fail("DLSS FG input completion is unresolved", static_cast<int32_t>(status));
    }
    sl::DLSSGOptions options;
    if ((result = check(ctx.fg_options(ctx.viewport, options), "slDLSSGSetOptions(OFF)")))
        return result;
    if (ctx.fg_tag_token) {
        const sl::ResourceTag tags[] = {
                {nullptr, sl::kBufferTypeDepth, sl::ResourceLifecycle::eValidUntilPresent},
                {nullptr, sl::kBufferTypeMotionVectors, sl::ResourceLifecycle::eValidUntilPresent},
                {nullptr, sl::kBufferTypeHUDLessColor, sl::ResourceLifecycle::eValidUntilPresent},
                {nullptr, sl::kBufferTypeUIAlpha, sl::ResourceLifecycle::eValidUntilPresent}};
        if ((result = check(ctx.set_tags(*ctx.fg_tag_token, ctx.viewport, tags, 4, nullptr),
                            "slSetTagForFrame(clear FG)")))
            return result;
    }
    if ((result = check(ctx.free_resources(sl::kFeatureDLSS_G, ctx.viewport),
                        "slFreeResources(FG)")))
        return result;
    ctx.fg_tag_token = nullptr;
    ctx.fg_enabled = false;
    return 0;
}

int32_t mark(Context &ctx, sl::PCLMarker marker) {
    return ctx.frame_token && ctx.marker
                   ? check(ctx.marker(marker, *ctx.frame_token), "slPCLSetMarker")
                   : 0;
}

VkResult present_with_hooks(Context *context, PFN_vkQueuePresentKHR present, VkQueue queue,
                            const VkPresentInfoKHR *info) {
    const bool notify = context && context->queue == queue;
    if (notify) {
        bool skip = false;
        // RR-only common hooks never replace or skip presentation. Preserve the
        // host's real present even if plugin bookkeeping unexpectedly fails.
        context->before_present(queue, info, skip);
    }
    const VkResult result = present(queue, info);
    if (notify)
        context->after_present();
    return result;
}
} // namespace

extern "C" uint32_t prime_sl_abi_version() {
    return 2;
}
extern "C" const char *prime_sl_last_error() {
    return error_text;
}

extern "C" int32_t prime_sl_bootstrap() {
    std::lock_guard lock(api_mutex);
    if (bootstrapped)
        return 0;
    if (active)
        return fail("Streamline bootstrap must precede host Vulkan creation", -2);
    try {
        auto ctx = std::make_unique<Context>();
        int32_t result = initialize_context(*ctx, true);
        if (result)
            return result;
        bootstrapped = ctx.release();
        return 0;
    } catch (...) {
        return fail("Exception during early Streamline bootstrap", -4);
    }
}

extern "C" int32_t prime_sl_frame(uint32_t action, uint32_t enabled) {
    std::lock_guard lock(api_mutex);
    if (action > 4 || enabled > 1)
        return fail("Invalid Streamline frame action");
    Context *ctx = bootstrapped;
    if (!ctx || !ctx->initialized)
        return 0;
    if (action == 3 || action == 4 || (action == 0 && !enabled)) {
        int32_t result = suspend_fg(*ctx);
        if (result)
            return result;
        if (ctx->reflex_enabled) {
            sl::ReflexOptions options;
            if ((result = check(ctx->reflex_options(options), "slReflexSetOptions(OFF)")))
                return result;
            ctx->reflex_enabled = false;
        }
        ctx->frame_token = nullptr;
        if (action == 4) {
            if (active)
                return fail("Streamline world owner must retire before host device shutdown");
            result = check(ctx->shutdown(), "slShutdown(host device)");
            if (!result)
                ctx->initialized = false;
        }
        return result;
    }
    if (!ctx->fg_supported)
        return 0;
    if (action == 0) {
        ctx->frame_token = nullptr;
        ctx->frame_constants_set = false;
        const uint32_t index = ++ctx->logical_frame;
        int32_t result = check(ctx->get_token(ctx->frame_token, &index), "slGetNewFrameToken(FG)");
        if (result || !ctx->frame_token)
            return result ? result : fail("Streamline returned an empty frame token");
        sl::ReflexOptions options;
        options.mode = sl::ReflexMode::eLowLatency;
        if (!ctx->reflex_enabled) {
            if ((result = check(ctx->reflex_options(options), "slReflexSetOptions")))
                return result;
            ctx->reflex_enabled = true;
        }
        if ((result = check(ctx->reflex_sleep(*ctx->frame_token), "slReflexSleep")))
            return result;
        return mark(*ctx, sl::PCLMarker::eSimulationStart);
    }
    if (action == 1) {
        int32_t result = mark(*ctx, sl::PCLMarker::eSimulationEnd);
        return result ? result : mark(*ctx, sl::PCLMarker::eRenderSubmitStart);
    }
    return mark(*ctx, sl::PCLMarker::eRenderSubmitEnd);
}

extern "C" int32_t prime_sl_create(const PrimeSlInit *init, void **output) {
    std::lock_guard lock(api_mutex);
    if (output)
        *output = nullptr;
    if (!init || !output || !init->device || !init->instance || !init->physical_device)
        return fail("Invalid Streamline initialization arguments");
    if (active)
        return fail("Only one Streamline context can be active", -2);
    try {
        std::unique_ptr<Context> owned;
        Context *ctx = bootstrapped;
        int32_t result = 0;
        if (!ctx) {
            owned = std::make_unique<Context>();
            ctx = owned.get();
            if ((result = initialize_context(*ctx, false)))
                return result;
        }
        sl::AdapterInfo adapter;
        adapter.vkPhysicalDevice = reinterpret_cast<void *>(init->physical_device);
        if ((result = check(ctx->supported(ctx->feature, adapter), "DLSS RR unsupported")))
            return result;
        sl::VulkanInfo info;
        info.instance = reinterpret_cast<VkInstance>(init->instance);
        info.physicalDevice = reinterpret_cast<VkPhysicalDevice>(init->physical_device);
        info.device = reinterpret_cast<VkDevice>(init->device);
        info.graphicsQueueFamily = info.computeQueueFamily = init->queue_family;
        info.graphicsQueueIndex = info.computeQueueIndex = init->queue_index;
        // sl_helpers_vk.h:218 and the pinned proxy implementation specify this for manual
        // attachment only. The interposer already initialized plugins and its extra FG queues.
        if (!ctx->interposed && (result = check(ctx->set_vulkan(info), "slSetVulkanInfo")))
            return result;
        if ((result =
                     feature_function(*ctx, ctx->feature, "slDLSSDSetOptions", ctx->set_options)) ||
            (result = feature_function(*ctx, ctx->feature, "slDLSSDGetOptimalSettings",
                                       ctx->optimal)) ||
            (result = feature_function(*ctx, sl::kFeatureCommon, "slHookVkPresent",
                                       ctx->before_present)) ||
            (result = feature_function(*ctx, sl::kFeatureCommon, "slHookVkAfterPresent",
                                       ctx->after_present)))
            return result;
        PFN_vkGetDeviceProcAddr get_proc{};
        if (!symbol(vulkan_loader(), "vkGetDeviceProcAddr", get_proc) || !native_present())
            return fail("Cannot load Vulkan loader entry points");
        auto get_queue =
                reinterpret_cast<PFN_vkGetDeviceQueue>(get_proc(info.device, "vkGetDeviceQueue"));
        if (!get_queue)
            return fail("Cannot retrieve host Vulkan queue");
        get_queue(info.device, init->queue_family, init->queue_index, &ctx->queue);
        ctx->device = info.device;
        ctx->wait_semaphores =
                reinterpret_cast<PFN_vkWaitSemaphores>(get_proc(info.device, "vkWaitSemaphores"));
        if (ctx->interposed) {
            ctx->fg_supported = ctx->supported(sl::kFeatureDLSS_G, adapter) == sl::Result::eOk &&
                                ctx->supported(sl::kFeatureReflex, adapter) == sl::Result::eOk &&
                                ctx->supported(sl::kFeaturePCL, adapter) == sl::Result::eOk;
            if (ctx->fg_supported) {
                if ((result = feature_function(*ctx, sl::kFeatureDLSS_G, "slDLSSGSetOptions",
                                               ctx->fg_options)) ||
                    (result = feature_function(*ctx, sl::kFeatureDLSS_G, "slDLSSGGetState",
                                               ctx->fg_state)) ||
                    (result = feature_function(*ctx, sl::kFeatureReflex, "slReflexSetOptions",
                                               ctx->reflex_options)) ||
                    (result = feature_function(*ctx, sl::kFeatureReflex, "slReflexSleep",
                                               ctx->reflex_sleep)) ||
                    (result = feature_function(*ctx, sl::kFeaturePCL, "slPCLSetMarker",
                                               ctx->marker)) ||
                    !symbol(ctx->module, "slSetTagForFrame", ctx->set_tags))
                    ctx->fg_supported = false;
            }
            PFN_vkGetDeviceProcAddr interposed_proc{};
            if (!symbol(ctx->module, "vkGetDeviceProcAddr", interposed_proc) ||
                !(ctx->interposed_present = reinterpret_cast<PFN_vkQueuePresentKHR>(
                          interposed_proc(info.device, "vkQueuePresentKHR"))))
                return fail("Cannot retrieve interposed Vulkan Present");
        }
        active = ctx;
        if (owned)
            owned.release();
        *output = active;
        return 0;
    } catch (...) {
        return fail("Exception while initializing Streamline", -4);
    }
}

extern "C" int32_t prime_sl_configure(void *context, uint32_t width, uint32_t height,
                                      uint32_t quality, PrimeSlSize *size) {
    std::lock_guard lock(api_mutex);
    if (!context || context != active || !width || !height || quality > 4 || !size)
        return fail("Invalid Streamline configuration");
    auto &ctx = *active;
    int32_t suspended = suspend_fg(ctx);
    if (suspended)
        return suspended;
    auto options = make_options(width, height, quality);
    sl::DLSSDOptimalSettings settings;
    int32_t result = check(ctx.optimal(options, settings), "slDLSSDGetOptimalSettings");
    if (result)
        return result;
    if (!settings.optimalRenderWidth || !settings.optimalRenderHeight)
        return fail("DLSS returned an empty render extent");
    // The caller supplies a GPU completion proof before configure. Free explicitly
    // so SDK resize handling never relies on its default three-frame retirement.
    if (ctx.evaluated) {
        if ((result = check(ctx.free_resources(ctx.feature, ctx.viewport), "slFreeResources")))
            return result;
        ctx.evaluated = false;
    }
    if ((result = check(ctx.set_options(ctx.viewport, options), "slDLSSDSetOptions")))
        return result;
    ctx.options = options;
    ctx.size = {settings.optimalRenderWidth, settings.optimalRenderHeight};
    ctx.configured = true;
    *size = ctx.size;
    return 0;
}

extern "C" int32_t prime_sl_evaluate(void *context, const PrimeSlFrame *frame) {
    std::lock_guard lock(api_mutex);
    if (!context || context != active || !frame || !active->configured ||
        !valid_frame(*active, *frame))
        return fail("Invalid DLSS RR frame resources or constants");
    auto &ctx = *active;
    copy_matrix(ctx.options.worldToCameraView, frame->world_to_view);
    copy_matrix(ctx.options.cameraViewToWorld, frame->view_to_world);
    int32_t result = check(ctx.set_options(ctx.viewport, ctx.options), "slDLSSDSetOptions");
    if (result)
        return result;
    sl::FrameToken *token = ctx.interposed ? ctx.frame_token : nullptr;
    if (!token && (result = check(ctx.get_token(token, &frame->frame_index), "slGetNewFrameToken")))
        return result;
    if ((result = set_common_constants(ctx, *frame, *token)))
        return result;
    constexpr sl::BufferType types[] = {sl::kBufferTypeScalingInputColor,
                                        sl::kBufferTypeLinearDepth,
                                        sl::kBufferTypeMotionVectors,
                                        sl::kBufferTypeNormalRoughness,
                                        sl::kBufferTypeAlbedo,
                                        sl::kBufferTypeSpecularAlbedo,
                                        sl::kBufferTypeScalingOutputColor,
                                        sl::kBufferTypeSpecularHitDistance,
                                        sl::kBufferTypeSpecularMotionVectors};
    std::array<sl::Resource, PRIME_SL_IMAGE_COUNT> resources;
    std::array<sl::ResourceTag, PRIME_SL_IMAGE_COUNT> tags;
    std::array<const sl::BaseStructure *, PRIME_SL_IMAGE_COUNT + 1> inputs{};
    inputs[0] = &ctx.viewport;
    uint32_t count = 1;
    for (uint32_t index = 0; index < PRIME_SL_IMAGE_COUNT; ++index) {
        // Select the explicit specular-motion path for the entire viewport.
        // Distance belongs to the engine's post reconstruction, not SDK fallback.
        if (index == PRIME_SL_SPECULAR_HIT_DISTANCE || absent(frame->images[index]))
            continue;
        resources[index] = make_resource(frame->images[index]);
        const sl::Extent extent{0, 0, frame->images[index].width, frame->images[index].height};
        tags[index] = sl::ResourceTag(&resources[index], types[index],
                                      sl::ResourceLifecycle::eValidUntilEvaluate, &extent);
        inputs[count++] = &tags[index];
    }
    // Local tags avoid SDK tag copies. GPU image ownership remains with Rust;
    // the real Present hook still runs SDK bookkeeping once per host present.
    ctx.evaluated = true;
    evaluating = true;
    evaluation_logged_error = false;
    sl::Result status;
    try {
        status = ctx.evaluate(ctx.feature, *token, inputs.data(), count,
                              reinterpret_cast<sl::CommandBuffer *>(frame->command_buffer));
    } catch (...) {
        evaluating = false;
        return fail("Exception during DLSS RR recording; command buffer is unsafe", -100);
    }
    evaluating = false;
    if (status == sl::Result::eErrorExceptionHandler)
        return fail("Streamline exception; command buffer is unsafe", -100);
    if (status != sl::Result::eOk)
        return check(status, "slEvaluateFeature(DLSS RR)");
    // v2.14.1 dlss_dEntry.cpp ignores the bool returned by NGX evaluate. The
    // common plugin synchronously reports its failure via this error callback.
    return evaluation_logged_error ? -5 : 0;
}

extern "C" uint32_t prime_sl_fg_supported(void *context) {
    std::lock_guard lock(api_mutex);
    return context && context == active && active->interposed && active->fg_supported;
}

extern "C" int32_t prime_sl_fg_suspend(void *context) {
    std::lock_guard lock(api_mutex);
    if (!context || context != active)
        return fail("Invalid Streamline FG owner");
    return suspend_fg(*active);
}

extern "C" int32_t prime_sl_fg_prepare(void *context, const PrimeSlFgFrame *frame) {
    std::lock_guard lock(api_mutex);
    // +1 is reserved for the explicit unavailable gates below. SDK eErrorIO is
    // also numerically 1 and must remain a failure, including after partial tags.
    const auto error = [](int32_t result) { return result == 1 ? -101 : result; };
    if (!context || context != active || !active->interposed || !active->fg_supported)
        return fail("DLSS FG requires early interposer initialization and adapter support", 1);
    try {
        auto &ctx = *active;
        if (!frame || !frame->constants || !frame->command_buffer || !ctx.frame_token ||
            !frame->back_buffer_count || frame->back_buffer_count > 16)
            return fail("Invalid DLSS FG frame");
        const auto &depth = frame->images[0], &motion = frame->images[1];
        const auto &hudless = frame->images[2], &alpha = frame->images[3];
        if (!valid_image(depth, VK_FORMAT_R32_SFLOAT, ctx.size.render_width,
                         ctx.size.render_height) ||
            !valid_image(motion, VK_FORMAT_R16G16_SFLOAT, depth.width, depth.height) ||
            !valid_image(hudless,
                         frame->back_buffer_format == VK_FORMAT_R16G16B16A16_SFLOAT
                                 ? VK_FORMAT_R16G16B16A16_SFLOAT
                                 : VK_FORMAT_R8G8B8A8_UNORM,
                         ctx.options.outputWidth, ctx.options.outputHeight) ||
            !valid_image(alpha, VK_FORMAT_R8_UNORM, hudless.width, hudless.height) ||
            (frame->back_buffer_format != VK_FORMAT_R16G16B16A16_SFLOAT &&
             frame->back_buffer_format != VK_FORMAT_R8G8B8A8_UNORM &&
             frame->back_buffer_format != VK_FORMAT_B8G8R8A8_UNORM))
            return fail("Invalid first-visible/HUD-less/UI-alpha FG resources");
        for (uint32_t i = 0; i < 4; ++i)
            for (uint32_t j = i + 1; j < 4; ++j)
                if (frame->images[i].image == frame->images[j].image)
                    return fail("DLSS FG inputs must have distinct owners");
        const PrimeSlFrame &camera = *frame->constants;
        constexpr size_t floats =
                (offsetof(PrimeSlFrame, images) - offsetof(PrimeSlFrame, jitter)) / sizeof(float);
        const auto *values =
                reinterpret_cast<const char *>(&camera) + offsetof(PrimeSlFrame, jitter);
        for (size_t index = 0; index < floats; ++index) {
            float value;
            std::memcpy(&value, values + index * sizeof(float), sizeof(float));
            if (!std::isfinite(value))
                return fail("Non-finite DLSS FG camera");
        }
        int32_t result = set_common_constants(ctx, camera, *ctx.frame_token);
        if (result)
            return error(result);
        sl::DLSSGOptions options;
        options.mode = sl::DLSSGMode::eOn;
        options.numFramesToGenerate = 1;
        options.numBackBuffers = frame->back_buffer_count;
        options.mvecDepthWidth = depth.width;
        options.mvecDepthHeight = depth.height;
        options.colorWidth = hudless.width;
        options.colorHeight = hudless.height;
        options.colorBufferFormat = frame->back_buffer_format;
        options.depthBufferFormat = depth.format;
        options.mvecBufferFormat = motion.format;
        options.hudLessBufferFormat = hudless.format;
        options.uiBufferFormat = alpha.format;
        options.queueParallelismMode = sl::DLSSGQueueParallelismMode::eBlockPresentingClientQueue;
        options.enableUserInterfaceRecomposition = sl::Boolean::eTrue;
        sl::DLSSGState state;
        if ((result = check(ctx.fg_state(ctx.viewport, state, &options),
                            "slDLSSGGetState(support)")))
            return error(result);
        if (state.numFramesToGenerateMax < 1 || hudless.width < state.minWidthOrHeight ||
            hudless.height < state.minWidthOrHeight) {
            if ((result = suspend_fg(ctx)))
                return error(result);
            return fail("DLSS FG output extent or generated frame count is unsupported", 1);
        }
        ctx.fg_enabled = true; // Options may allocate resources even if the following call fails.
        if ((result = check(ctx.fg_options(ctx.viewport, options), "slDLSSGSetOptions(ON)")))
            return error(result);
        constexpr sl::BufferType types[] = {sl::kBufferTypeDepth, sl::kBufferTypeMotionVectors,
                                            sl::kBufferTypeHUDLessColor, sl::kBufferTypeUIAlpha};
        std::array<sl::Resource, 4> resources;
        std::array<sl::ResourceTag, 4> tags;
        for (uint32_t index = 0; index < 4; ++index) {
            resources[index] = make_resource(frame->images[index]);
            const sl::Extent extent{0, 0, frame->images[index].width, frame->images[index].height};
            tags[index] = sl::ResourceTag(&resources[index], types[index],
                                          sl::ResourceLifecycle::eValidUntilPresent, &extent);
        }
        ctx.fg_tag_token = ctx.frame_token; // A failed tag call may have registered a partial set.
        if ((result = check(
                     ctx.set_tags(*ctx.frame_token, ctx.viewport, tags.data(), 4,
                                  reinterpret_cast<sl::CommandBuffer *>(frame->command_buffer)),
                     "slSetTagForFrame(FG)")))
            return error(result);
        if ((result =
                     check(ctx.fg_state(ctx.viewport, state, nullptr), "slDLSSGGetState(runtime)")))
            return error(result);
        return state.status == sl::DLSSGStatus::eOk
                       ? 0
                       : fail("DLSS FG runtime status rejected this frame", -20);
    } catch (...) {
        return fail("Exception during DLSS FG recording; submission ownership is unresolved", -100);
    }
}

extern "C" int32_t prime_sl_destroy(void *context) {
    std::lock_guard lock(api_mutex);
    if (!context)
        return 0;
    if (context != active)
        return fail("Invalid Streamline context");
    Context *ctx = active;
    int32_t suspended = suspend_fg(*ctx);
    if (suspended)
        return suspended;
    std::unique_ptr<Context> owned(ctx == bootstrapped ? nullptr : ctx);
    active = nullptr;
    int32_t result = 0;
    if (ctx->evaluated)
        result = check(ctx->free_resources(ctx->feature, ctx->viewport), "slFreeResources");
    if (ctx == bootstrapped) {
        ctx->evaluated = ctx->configured = false;
        return result;
    }
    auto shutdown_result = check(ctx->shutdown(), "slShutdown");
    ctx->initialized = false;
    return result ? result : shutdown_result;
}

extern "C" int32_t prime_sl_present(uint64_t queue, uint64_t present_info) {
    std::lock_guard lock(api_mutex);
    Context *context = active ? active : bootstrapped;
    auto present = context && context->interposed ? context->interposed_present : native_present();
    if (!present)
        return VK_ERROR_INITIALIZATION_FAILED;
    auto native_queue = reinterpret_cast<VkQueue>(queue);
    auto info = reinterpret_cast<const VkPresentInfoKHR *>(present_info);
    // Interposer already owns all mandatory lifecycle hooks. Never invoke common hooks twice.
    if (context && context->interposed) {
        mark(*context, sl::PCLMarker::eRenderSubmitEnd);
        mark(*context, sl::PCLMarker::ePresentStart);
        if (!info || info->swapchainCount != 1)
            return VK_ERROR_INITIALIZATION_FAILED;
        // The pinned interposer overwrites the driver's aggregate result in its after hooks.
        // Keep the caller's descriptor intact and recover the actual per-swapchain result.
        VkPresentInfoKHR copy = *info;
        VkResult swapchain_result = VK_ERROR_UNKNOWN;
        copy.pResults = &swapchain_result;
        const VkResult sdk_result = present(native_queue, &copy);
        if (info->pResults)
            info->pResults[0] = swapchain_result;
        mark(*context, sl::PCLMarker::ePresentEnd);
        if (sdk_result < VK_SUCCESS)
            return sdk_result;
        if (swapchain_result == VK_ERROR_UNKNOWN) {
            fail("Interposed Present did not publish the actual swapchain result");
            return VK_ERROR_INITIALIZATION_FAILED;
        }
        return swapchain_result == VK_SUCCESS ? sdk_result : swapchain_result;
    }
    return present_with_hooks(active, present, native_queue, info);
}
