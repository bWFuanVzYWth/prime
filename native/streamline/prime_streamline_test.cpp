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
sl::Result evaluation_result = sl::Result::eOk;
VkResult present_result = VK_SUCCESS;
bool emit_error{};

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
    assert(feature == sl::kFeatureDLSS_RR);
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
    assert(static_cast<uint32_t>(current) == 123);
    seen_constants = constants;
    return sl::Result::eOk;
}
sl::Result mock_evaluate(sl::Feature feature, const sl::FrameToken &current,
                         const sl::BaseStructure **inputs, uint32_t count, sl::CommandBuffer *cmd) {
    assert(feature == sl::kFeatureDLSS_RR && static_cast<uint32_t>(current) == 123);
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
VkResult VKAPI_CALL mock_present(VkQueue, const VkPresentInfoKHR *) {
    ++presents;
    return present_result;
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
    std::puts("Streamline bridge contract tests passed");
}
