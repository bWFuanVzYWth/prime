// Executes production argument builders and dispatch paths with SDK/Vulkan mocks.
// It does not initialize Streamline, create a GPU device, or present a window.
#include "prime_streamline.cpp"

#include <cassert>
#include <limits>
#include <vector>

namespace {
struct Token : sl::FrameToken {
    uint32_t index{};
    operator uint32_t() const override {
        return index;
    }
} token;
sl::DLSSDOptions seen_options;
sl::Constants seen_constants;
std::vector<sl::BufferType> seen_tags;
int released{}, shutdowns{}, evaluates{}, before{}, presents{}, after{};
int constants_calls{};
sl::Result evaluation_result = sl::Result::eOk;
VkResult present_result = VK_SUCCESS;
VkResult swapchain_result = VK_SUCCESS;
bool publish_present_result = true;
bool emit_error{};
uint32_t expected_token = 123;
sl::DLSSGOptions seen_fg;
std::vector<sl::PCLMarker> markers;
std::vector<int> retirement;
VkResult fg_wait_result = VK_SUCCESS;
uint32_t fg_min_extent = 128;
int fg_io_failure{};

sl::Result mock_options(const sl::ViewportHandle &, const sl::DLSSDOptions &options) {
    seen_options = options;
    return sl::Result::eOk;
}
sl::Result mock_optimal(const sl::DLSSDOptions &options, sl::DLSSDOptimalSettings &settings) {
    settings.optimalRenderWidth = options.outputWidth / 2;
    settings.optimalRenderHeight = options.outputHeight / 2;
    return sl::Result::eOk;
}
sl::Result mock_release(sl::Feature feature, const sl::ViewportHandle &) {
    assert(feature == sl::kFeatureDLSS_RR || feature == sl::kFeatureDLSS_G);
    if (feature == sl::kFeatureDLSS_G) {
        retirement.push_back(3);
        return fg_io_failure == 7 ? sl::Result::eErrorIO : sl::Result::eOk;
    }
    ++released;
    return sl::Result::eOk;
}
sl::Result mock_shutdown() {
    ++shutdowns;
    return sl::Result::eOk;
}
sl::Result mock_token(sl::FrameToken *&output, const uint32_t *index) {
    token.index = *index;
    output = &token;
    return sl::Result::eOk;
}
sl::Result mock_constants(const sl::Constants &constants, const sl::FrameToken &current,
                          const sl::ViewportHandle &) {
    assert(static_cast<uint32_t>(current) == expected_token);
    ++constants_calls;
    seen_constants = constants;
    return fg_io_failure == 1 ? sl::Result::eErrorIO : sl::Result::eOk;
}
sl::Result mock_evaluate(sl::Feature feature, const sl::FrameToken &current,
                         const sl::BaseStructure **inputs, uint32_t count, sl::CommandBuffer *cmd) {
    assert(feature == sl::kFeatureDLSS_RR && static_cast<uint32_t>(current) == expected_token);
    assert(cmd == reinterpret_cast<sl::CommandBuffer *>(10));
    assert(inputs[0]->structType == sl::ViewportHandle::s_structType);
    seen_tags.clear();
    for (uint32_t index = 1; index < count; ++index) {
        assert(inputs[index]->structType == sl::ResourceTag::s_structType);
        auto &tag = *static_cast<const sl::ResourceTag *>(inputs[index]);
        assert(tag.lifecycle == sl::ResourceLifecycle::eValidUntilEvaluate);
        assert(tag.resource->state == VK_IMAGE_LAYOUT_GENERAL);
        assert(tag.resource->mipLevels == 1 && tag.resource->arrayLayers == 1);
        assert(tag.extent.width == tag.resource->width);
        if (tag.type == sl::kBufferTypeSpecularMotionVectors) {
            assert(tag.resource->nativeFormat == VK_FORMAT_R16G16_SFLOAT);
            assert(tag.resource->native == reinterpret_cast<void *>(108));
        }
        seen_tags.push_back(tag.type);
    }
    ++evaluates;
    if (emit_error)
        log_message(sl::LogType::eError, "NGX evaluate feature failed mock");
    return evaluation_result;
}
VkResult mock_before(VkQueue, const VkPresentInfoKHR *, bool &skip) {
    ++before;
    skip = true; // Unexpected plugin request must not suppress the host present.
    return VK_ERROR_UNKNOWN;
}
VkResult mock_after() {
    ++after;
    return VK_SUCCESS;
}
VkResult VKAPI_CALL mock_present(VkQueue, const VkPresentInfoKHR *info) {
    ++presents;
    if (info && info->pResults && publish_present_result)
        info->pResults[0] = swapchain_result;
    return present_result;
}
sl::Result mock_fg_options(const sl::ViewportHandle &, const sl::DLSSGOptions &options) {
    seen_fg = options;
    if (options.mode == sl::DLSSGMode::eOff)
        retirement.push_back(2);
    return fg_io_failure == (options.mode == sl::DLSSGMode::eOff ? 6 : 3) ? sl::Result::eErrorIO
                                                                          : sl::Result::eOk;
}
sl::Result mock_fg_state(const sl::ViewportHandle &, sl::DLSSGState &state,
                         const sl::DLSSGOptions *options) {
    state.numFramesToGenerateMax = 1;
    state.minWidthOrHeight = fg_min_extent;
    state.status = sl::DLSSGStatus::eOk;
    state.inputsProcessingCompletionFence = reinterpret_cast<void *>(81);
    state.lastPresentInputsProcessingCompletionFenceValue = 83;
    return fg_io_failure == (options ? 2 : 5) ? sl::Result::eErrorIO : sl::Result::eOk;
}
VkResult VKAPI_CALL mock_fg_wait(VkDevice device, const VkSemaphoreWaitInfo *info,
                                 uint64_t timeout) {
    assert(device == reinterpret_cast<VkDevice>(79) && timeout == 5'000'000'000ULL);
    assert(info->semaphoreCount == 1 && info->pSemaphores[0] == reinterpret_cast<VkSemaphore>(81));
    assert(info->pValues[0] == 83);
    retirement.push_back(1);
    return fg_wait_result;
}
sl::Result mock_reflex_options(const sl::ReflexOptions &options) {
    assert(options.mode == sl::ReflexMode::eLowLatency || options.mode == sl::ReflexMode::eOff);
    return sl::Result::eOk;
}
sl::Result mock_reflex_sleep(const sl::FrameToken &current) {
    assert(static_cast<uint32_t>(current) == expected_token);
    return sl::Result::eOk;
}
sl::Result mock_marker(sl::PCLMarker marker, const sl::FrameToken &current) {
    assert(static_cast<uint32_t>(current) == expected_token);
    markers.push_back(marker);
    return sl::Result::eOk;
}
sl::Result mock_tags(const sl::FrameToken &, const sl::ViewportHandle &,
                     const sl::ResourceTag *tags, uint32_t count, sl::CommandBuffer *command) {
    if (!command) {
        assert(count == 4);
        for (uint32_t i = 0; i < count; ++i)
            assert(tags[i].resource == nullptr);
        retirement.push_back(4);
        return sl::Result::eOk;
    }
    assert(count == 4 && command == reinterpret_cast<sl::CommandBuffer *>(10));
    seen_tags.clear();
    for (uint32_t i = 0; i < count; i++) {
        assert(tags[i].lifecycle == sl::ResourceLifecycle::eValidUntilPresent);
        assert(tags[i].resource->state == VK_IMAGE_LAYOUT_GENERAL);
        seen_tags.push_back(tags[i].type);
    }
    return fg_io_failure == 4 ? sl::Result::eErrorIO : sl::Result::eOk;
}

PrimeSlFrame make_frame(const Context &ctx) {
    PrimeSlFrame frame{};
    frame.command_buffer = 10;
    frame.frame_index = 123;
    frame.reset = 1;
    frame.jitter[0] = 0.25f;
    frame.jitter[1] = -0.125f;
    frame.camera_near = 0.1f;
    frame.camera_far = 10000.0f;
    frame.camera_fov = 1.2f;
    frame.camera_aspect = 16.0f / 9.0f;
    for (uint32_t index = 0; index < PRIME_SL_IMAGE_COUNT; ++index) {
        auto &image = frame.images[index];
        image.image = 100 + index;
        image.view = 200 + index;
        image.width = index == PRIME_SL_OUTPUT ? ctx.options.outputWidth : ctx.size.render_width;
        image.height = index == PRIME_SL_OUTPUT ? ctx.options.outputHeight : ctx.size.render_height;
        image.format = VK_FORMAT_R16G16B16A16_SFLOAT;
        image.layout = VK_IMAGE_LAYOUT_GENERAL;
        image.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    }
    frame.images[PRIME_SL_DEPTH].format = VK_FORMAT_R32_SFLOAT;
    frame.images[PRIME_SL_MOTION].format = VK_FORMAT_R16G16_SFLOAT;
    frame.images[PRIME_SL_SPECULAR_HIT_DISTANCE].format = VK_FORMAT_R16_SFLOAT;
    frame.images[PRIME_SL_SPECULAR_MOTION].format = VK_FORMAT_R16G16_SFLOAT;
    return frame;
}
} // namespace

int main() {
    assert(prime_sl_abi_version() == 2);
    assert(sizeof(PrimeSlFrame) == 904);
    void *output = reinterpret_cast<void *>(1);
    assert(prime_sl_create(nullptr, &output) < 0 && output == nullptr);
    assert(prime_sl_destroy(nullptr) == 0);
    auto *ctx = new Context;
    ctx->set_options = mock_options;
    ctx->optimal = mock_optimal;
    ctx->free_resources = mock_release;
    ctx->shutdown = mock_shutdown;
    ctx->get_token = mock_token;
    ctx->set_constants = mock_constants;
    ctx->evaluate = mock_evaluate;
    ctx->before_present = mock_before;
    ctx->after_present = mock_after;
    ctx->queue = reinterpret_cast<VkQueue>(50);
    active = ctx;
    const sl::DLSSMode modes[] = {sl::DLSSMode::eDLAA, sl::DLSSMode::eMaxQuality,
                                  sl::DLSSMode::eBalanced, sl::DLSSMode::eMaxPerformance,
                                  sl::DLSSMode::eUltraPerformance};
    PrimeSlSize size{};
    for (uint32_t quality = 0; quality < 5; ++quality) {
        assert(prime_sl_configure(ctx, 1920, 1080, quality, &size) == 0);
        assert(size.render_width == 960 && size.render_height == 540);
        assert(seen_options.mode == modes[quality]);
        assert(seen_options.alphaUpscalingEnabled == sl::Boolean::eTrue);
        assert(seen_options.dlaaPreset == sl::DLSSDPreset::ePresetF);
        assert(seen_options.qualityPreset == sl::DLSSDPreset::ePresetF);
        assert(seen_options.balancedPreset == sl::DLSSDPreset::ePresetF);
        assert(seen_options.performancePreset == sl::DLSSDPreset::ePresetF);
        assert(seen_options.ultraPerformancePreset == sl::DLSSDPreset::ePresetF);
        assert(seen_options.ultraQualityPreset == sl::DLSSDPreset::ePresetF);
    }
    assert(prime_sl_configure(ctx, 1920, 1080, 5, &size) < 0);
    auto frame = make_frame(*ctx);
    assert(prime_sl_evaluate(ctx, &frame) == 0);
    assert(evaluates == 1 && seen_tags.size() == 8);
    assert((seen_tags == std::vector<sl::BufferType>{
                                 sl::kBufferTypeScalingInputColor, sl::kBufferTypeLinearDepth,
                                 sl::kBufferTypeMotionVectors, sl::kBufferTypeNormalRoughness,
                                 sl::kBufferTypeAlbedo, sl::kBufferTypeSpecularAlbedo,
                                 sl::kBufferTypeScalingOutputColor,
                                 sl::kBufferTypeSpecularMotionVectors}));
    assert(seen_constants.jitterOffset.x == 0.25f && seen_constants.jitterOffset.y == -0.125f);
    assert(seen_constants.mvecScale.x == 1.0f / size.render_width);
    assert(seen_constants.mvecScale.y == 1.0f / size.render_height);
    assert(std::abs(seen_constants.mvecScale.x * size.render_width - 1.0f) < 1e-7f);
    assert(std::abs(seen_constants.mvecScale.y * size.render_height - 1.0f) < 1e-7f);
    assert(seen_constants.cameraMotionIncluded == sl::Boolean::eTrue);
    assert(seen_constants.motionVectorsJittered == sl::Boolean::eFalse);
    assert(seen_constants.reset == sl::Boolean::eTrue);
    frame.images[PRIME_SL_SPECULAR_HIT_DISTANCE] = {};
    assert(prime_sl_evaluate(ctx, &frame) == 0 && seen_tags.size() == 8);
    frame.images[PRIME_SL_NOISY].width += 1;
    assert(prime_sl_evaluate(ctx, &frame) < 0 && evaluates == 2);
    frame = make_frame(*ctx);
    frame.images[PRIME_SL_SPECULAR_MOTION] = {};
    assert(prime_sl_evaluate(ctx, &frame) < 0 && evaluates == 2);
    frame = make_frame(*ctx);
    frame.images[PRIME_SL_SPECULAR_MOTION].format = VK_FORMAT_R16G16B16A16_SFLOAT;
    assert(prime_sl_evaluate(ctx, &frame) < 0 && evaluates == 2);
    frame = make_frame(*ctx);
    frame.images[PRIME_SL_SPECULAR_MOTION].height += 1;
    assert(prime_sl_evaluate(ctx, &frame) < 0 && evaluates == 2);
    frame = make_frame(*ctx);
    frame.images[PRIME_SL_SPECULAR_MOTION].image = frame.images[PRIME_SL_OUTPUT].image;
    assert(prime_sl_evaluate(ctx, &frame) < 0 && evaluates == 2);
    frame = make_frame(*ctx);
    frame.images[PRIME_SL_OUTPUT].image = frame.images[PRIME_SL_NOISY].image;
    assert(prime_sl_evaluate(ctx, &frame) < 0 && evaluates == 2);
    frame = make_frame(*ctx);
    frame.view_to_clip[3] = std::numeric_limits<float>::quiet_NaN();
    assert(prime_sl_evaluate(ctx, &frame) < 0 && evaluates == 2);
    frame = make_frame(*ctx);
    evaluation_result = sl::Result::eErrorInvalidParameter;
    assert(prime_sl_evaluate(ctx, &frame) == static_cast<int32_t>(evaluation_result));
    assert(ctx->evaluated && released == 0); // GPU work is not destroyed on error.
    evaluation_result = sl::Result::eOk;
    emit_error = true;
    assert(prime_sl_evaluate(ctx, &frame) == -5);
    assert(std::strstr(prime_sl_last_error(), "NGX evaluate feature failed mock"));
    emit_error = false;
    evaluation_result = sl::Result::eErrorExceptionHandler;
    assert(prime_sl_evaluate(ctx, &frame) == -100);
    assert(prime_sl_configure(ctx, 1280, 720, 3, &size) == 0 && released == 1);
    for (auto expected :
         {VK_SUCCESS, VK_SUBOPTIMAL_KHR, VK_ERROR_OUT_OF_DATE_KHR, VK_ERROR_DEVICE_LOST}) {
        present_result = expected;
        assert(present_with_hooks(ctx, mock_present, ctx->queue, nullptr) == expected);
    }
    assert(before == 4 && presents == 4 && after == 4);
    present_with_hooks(ctx, mock_present, reinterpret_cast<VkQueue>(99), nullptr);
    present_with_hooks(nullptr, mock_present, ctx->queue, nullptr);
    assert(before == 4 && presents == 6 && after == 4);
    ctx->evaluated = true;
    assert(prime_sl_destroy(ctx) == 0);
    assert(released == 2 && shutdowns == 1 && active == nullptr);
    ctx = new Context;
    active = bootstrapped = ctx;
    ctx->initialized = ctx->interposed = ctx->fg_supported = true;
    ctx->device = reinterpret_cast<VkDevice>(79);
    ctx->queue = reinterpret_cast<VkQueue>(50);
    ctx->interposed_present = mock_present;
    ctx->get_token = mock_token;
    ctx->set_constants = mock_constants;
    ctx->set_tags = mock_tags;
    ctx->fg_options = mock_fg_options;
    ctx->fg_state = mock_fg_state;
    ctx->free_resources = mock_release;
    ctx->shutdown = mock_shutdown;
    ctx->wait_semaphores = mock_fg_wait;
    ctx->reflex_options = mock_reflex_options;
    ctx->reflex_sleep = mock_reflex_sleep;
    ctx->marker = mock_marker;
    ctx->set_options = mock_options;
    ctx->evaluate = mock_evaluate;
    ctx->configured = true;
    ctx->size = {960, 540};
    ctx->options = make_options(1920, 1080, 3);
    evaluation_result = sl::Result::eOk;
    expected_token = 1;
    assert(prime_sl_fg_supported(ctx) == 1);
    assert(prime_sl_frame(0, 1) == 0 && prime_sl_frame(1, 1) == 0);
    frame = make_frame(*ctx);
    int prior_constants_calls = constants_calls;
    assert(prime_sl_evaluate(ctx, &frame) == 0 && constants_calls == prior_constants_calls + 1);
    PrimeSlFgFrame fg{};
    fg.constants = &frame;
    fg.command_buffer = 10;
    fg.back_buffer_count = 3;
    fg.back_buffer_format = VK_FORMAT_B8G8R8A8_UNORM;
    for (uint32_t i = 0; i < 4; i++)
        fg.images[i] = frame.images[i < 2 ? i + 1 : PRIME_SL_OUTPUT];
    fg.images[0].format = VK_FORMAT_R32_SFLOAT;
    fg.images[1].format = VK_FORMAT_R16G16_SFLOAT;
    fg.images[2].format = VK_FORMAT_R8G8B8A8_UNORM;
    fg.images[3].format = VK_FORMAT_R8_UNORM;
    fg.images[3].image = 999;
    frame.jitter[0] += 0.25f;
    assert(prime_sl_fg_prepare(ctx, &fg) < 0 && constants_calls == prior_constants_calls + 1);
    frame.jitter[0] -= 0.25f;
    fg_io_failure = 4;
    assert(prime_sl_fg_prepare(ctx, &fg) == -101 && ctx->fg_enabled &&
           ctx->fg_tag_token == ctx->frame_token);
    assert(std::strstr(prime_sl_last_error(), "slSetTagForFrame(FG)"));
    fg_io_failure = 0;
    assert(prime_sl_fg_prepare(ctx, &fg) == 0);
    assert(constants_calls == prior_constants_calls + 1); // SDK rejects a second set on this token.
    assert((seen_tags ==
            std::vector<sl::BufferType>{sl::kBufferTypeDepth, sl::kBufferTypeMotionVectors,
                                        sl::kBufferTypeHUDLessColor, sl::kBufferTypeUIAlpha}));
    assert(seen_fg.mode == sl::DLSSGMode::eOn && seen_fg.numFramesToGenerate == 1 &&
           seen_fg.numBackBuffers == 3);
    assert(seen_fg.hudLessBufferFormat == VK_FORMAT_R8G8B8A8_UNORM &&
           seen_fg.colorBufferFormat == VK_FORMAT_B8G8R8A8_UNORM);
    assert(seen_fg.queueParallelismMode ==
           sl::DLSSGQueueParallelismMode::eBlockPresentingClientQueue);
    assert(seen_fg.enableUserInterfaceRecomposition == sl::Boolean::eTrue);
    present_result = VK_SUCCESS;
    swapchain_result = VK_SUBOPTIMAL_KHR;
    VkPresentInfoKHR present_info{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    VkResult caller_result = VK_SUCCESS;
    present_info.swapchainCount = 1;
    present_info.pResults = &caller_result;
    auto original_info = present_info;
    assert(prime_sl_present(50, reinterpret_cast<uint64_t>(&present_info)) == VK_SUBOPTIMAL_KHR);
    assert(caller_result == VK_SUBOPTIMAL_KHR &&
           !std::memcmp(&present_info, &original_info, sizeof(present_info)));
    assert(before == 4 && after == 4); // Interposer owns the mandatory hooks exactly once.
    assert((markers == std::vector<sl::PCLMarker>{
                               sl::PCLMarker::eSimulationStart, sl::PCLMarker::eSimulationEnd,
                               sl::PCLMarker::eRenderSubmitStart, sl::PCLMarker::eRenderSubmitEnd,
                               sl::PCLMarker::ePresentStart, sl::PCLMarker::ePresentEnd}));
    for (VkResult result :
         {VK_SUCCESS, VK_SUBOPTIMAL_KHR, VK_ERROR_OUT_OF_DATE_KHR, VK_ERROR_DEVICE_LOST}) {
        swapchain_result = result;
        assert(prime_sl_present(50, reinterpret_cast<uint64_t>(&present_info)) == result);
    }
    present_result = VK_ERROR_DEVICE_LOST;
    swapchain_result = VK_SUBOPTIMAL_KHR;
    assert(prime_sl_present(50, reinterpret_cast<uint64_t>(&present_info)) == VK_ERROR_DEVICE_LOST);
    present_result = VK_SUCCESS;
    publish_present_result = false;
    assert(prime_sl_present(50, reinterpret_cast<uint64_t>(&present_info)) ==
           VK_ERROR_INITIALIZATION_FAILED);
    publish_present_result = true;
    fg_wait_result = VK_TIMEOUT;
    assert(prime_sl_fg_suspend(ctx) == VK_TIMEOUT && ctx->fg_enabled);
    assert((retirement == std::vector<int>{1}));
    fg_wait_result = VK_SUCCESS;
    retirement.clear();
    assert(prime_sl_fg_suspend(ctx) == 0 && !ctx->fg_enabled);
    assert((retirement == std::vector<int>{1, 2, 4, 3}));
    assert(prime_sl_fg_prepare(ctx, &fg) == 0 && ctx->fg_enabled);
    retirement.clear();
    fg_min_extent = 2000;
    assert(prime_sl_fg_prepare(ctx, &fg) == 1 && !ctx->fg_enabled);
    assert((retirement == std::vector<int>{1, 2, 4, 3}));
    fg_min_extent = 128;
    // Every SDK stage, including retirement on an unavailable extent, preserves
    // eErrorIO as an error rather than the bridge's +1 unavailable result.
    for (int stage = 1; stage <= 7; ++stage) {
        ++expected_token;
        assert(prime_sl_frame(0, 1) == 0);
        if (stage >= 6) {
            assert(prime_sl_fg_prepare(ctx, &fg) == 0);
            fg_min_extent = 2000;
        }
        fg_io_failure = stage;
        assert(prime_sl_fg_prepare(ctx, &fg) == -101);
        assert(std::strstr(prime_sl_last_error(), "sl"));
        if (stage >= 3)
            assert(ctx->fg_enabled);
        fg_io_failure = 0;
        fg_min_extent = 128;
        assert(prime_sl_fg_suspend(ctx) == 0 && !ctx->fg_enabled);
    }
    fg.images[3].image = fg.images[2].image;
    assert(prime_sl_fg_prepare(ctx, &fg) < 0 && !ctx->fg_enabled);
    assert(prime_sl_destroy(ctx) == 0 && active == nullptr && shutdowns == 1);
    assert(prime_sl_frame(4, 0) == 0 && shutdowns == 2 && !ctx->initialized);
    bootstrapped = nullptr;
    delete ctx;
    std::puts("Streamline bridge contract tests passed");
}
