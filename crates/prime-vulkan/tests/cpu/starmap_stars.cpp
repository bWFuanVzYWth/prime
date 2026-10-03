// Execute the actual production display/stars entry via Slang's CPU backend.
#include "stars.generated.cpp"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <vector>
static unsigned checked = 0;
static void require(bool condition, const char *message) {
    ++checked;
    if (!condition) {
        std::fprintf(stderr, "stars entry contract failed: %s\n", message);
        std::exit(1);
    }
}
template <class T> struct Image final : IRWTexture {
    unsigned width, height;
    std::vector<T> values;
    Image(unsigned w, unsigned h, T value) : width(w), height(h), values(w * h, value) {}
    TextureDimensions GetDimensions(int = -1) override {
        TextureDimensions result{};
        result.width = width;
        result.height = height;
        return result;
    }
    void *refAt(const uint32_t *xy) override {
        require(xy[0] < width && xy[1] < height, "actual entry image coordinates in bounds");
        return &values[xy[1] * width + xy[0]];
    }
    void Load(const int32_t *xy, void *out, size_t size) override {
        require(xy[0] >= 0 && unsigned(xy[0]) < width && xy[1] >= 0 && unsigned(xy[1]) < height &&
                        size == sizeof(T),
                "actual entry transmittance load is in bounds with correct ABI");
        std::memcpy(out, &values[xy[1] * width + xy[0]], size);
    }
    void Sample(SamplerState, const float *, void *, size_t) override {
        std::abort();
    }
    void SampleLevel(SamplerState, const float *, float, void *, size_t) override {
        std::abort();
    }
};
struct StarsTexture final : ITexture {
    std::vector<float> lods;
    TextureDimensions GetDimensions(int = -1) override {
        return {};
    }
    void Load(const int32_t *, void *, size_t) override {
        std::abort();
    }
    void Sample(SamplerState, const float *, void *, size_t) override {
        std::abort();
    }
    void SampleLevel(SamplerState, const float *uv, float lod, void *out, size_t size) override {
        require(std::isfinite(uv[0]) && std::isfinite(uv[1]) && std::isfinite(lod) &&
                        size == sizeof(Vector<float, 4>),
                "actual production texture requests stay finite");
        lods.push_back(lod);
        const Vector<float, 4> value{1.25f, .5f, 8.f, .123f};
        std::memcpy(out, &value, size);
    }
};
static bool close(float actual, float expected) {
    return std::isfinite(actual) && std::abs(actual - expected) < 1e-6;
}
int main() {
    static_assert(sizeof(StarsParameters_0) == 112, "production push ABI unchanged");
    Image<Vector<float, 4>> output(17, 17, {.25f, .5f, .75f, 0});
    Image<Vector<float, 4>> transmittance(8193, 1, {.5f, .25f, 1.f, 1});
    Image<float> status(17, 17, 0);
    StarsTexture texture;
    StarsParameters_0 parameters{};
    parameters.forward_0 = {0, 0, -1, .0085f};
    parameters.right_0 = {1, 0, 0, 1};
    parameters.up_0 = {0, 1, 0, 0};
    parameters.sun_0 = {0, 1, 0, -1};
    parameters.settings_0 = {0, 0, 2, 0};
    parameters.dimensions_0 = {17, 17, 17, 17};
    GlobalParams_0 globals{
            {&output}, {{&texture}, {nullptr}}, {&transmittance}, {&status}, &parameters};
    ComputeThreadVaryingInput thread{};
    thread.groupID = {1, 1, 0};
    thread.groupThreadID = {0, 0, 0};
    const auto invoke = [&] {
        texture.lods.clear();
        main_0_Thread(&thread, nullptr, &globals);
    };
    for (float sign : {-1.f, 1.f})
        for (unsigned rotation = 0; rotation < 8; ++rotation)
            for (float coverage : {0.f, .25f}) {
                const double angle = rotation * 3.141592653589793 / 4;
                parameters.forward_0.z = -sign;
                parameters.right_0 = {float(std::cos(angle)), float(std::sin(angle)), 0, 1};
                parameters.up_0 = {-parameters.right_0.y, parameters.right_0.x, 0, 0};
                output.values[8 * 17 + 8] = {.25f, .5f, .75f, coverage};
                invoke();
                require(texture.lods.size() == 24,
                        "production polar entry samples eight points (three C++ component calls)");
                for (float lod : texture.lods)
                    require(lod >= 0 && lod < .5,
                            "production polar entry uses pixel-sized latitude mip");
                const auto result = output.values[8 * 17 + 8];
                const float visible = 1 - coverage;
                require(close(result.x, .25f + 1.25f * visible) &&
                                close(result.y, .5f + .25f * visible) &&
                                close(result.z, .75f + 16.f * visible) && result.w == coverage,
                        "actual post preserves scale/transmittance/coverage/RGB/alpha contract");
            }
    for (float coverage : {1.f, std::numeric_limits<float>::quiet_NaN()}) {
        output.values[8 * 17 + 8] = {.25f, .5f, .75f, coverage};
        invoke();
        require(texture.lods.empty(), "full/unknown foreground coverage does not sample stars");
        require(output.values[8 * 17 + 8].x == .25f, "foreground veto preserves scene radiance");
    }
    output.values[8 * 17 + 8] = {.25f, .5f, .75f, 0};
    for (float &value : status.values)
        value = 8.f / 255;
    invoke();
    require(texture.lods.empty(), "actual primary interior veto remains intact");
    for (float &value : status.values)
        value = 0;
    parameters.forward_0 = {1, 0, 0, .0085f};
    parameters.right_0 = {0, 0, 1, 1};
    parameters.up_0 = {0, 1, 0, 0};
    invoke();
    KernelContext_0 context{&globals};
    auto frame = primeCelestialFrame_0({0, 1, 0}, 0, 0);
    const float halfPixel = .5f / 17;
    auto directionUv = [&](float x, float y) {
        return primeStarmapUv_0(&frame, direction_0({x, y}, &context));
    };
    const auto ordinary =
            primeStarmapFootprint_0(primeStarmapWrappedDelta_0(directionUv(.5f + halfPixel, .5f),
                                                               directionUv(.5f - halfPixel, .5f)),
                                    primeStarmapWrappedDelta_0(directionUv(.5f, .5f + halfPixel),
                                                               directionUv(.5f, .5f - halfPixel)),
                                    {16384, 8192});
    require(texture.lods.size() == 3 * ordinary.taps_0 && texture.lods[0] == ordinary.lod_0,
            "ordinary chart uses the unchanged UV ellipse count and mip");
    std::printf("stars production CPU: %u contracts passed\n", checked);
}
