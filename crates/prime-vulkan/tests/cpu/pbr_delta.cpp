// Executes Slang's generated production code without a Vulkan device or window.
#include "pbr_delta.generated.cpp"
#include <cstdio>

int main() {
    unsigned failures = 0, delta = 0, guide = 0, tir = 0;
    unsigned masks[9] = {};
    for (unsigned i = 0; i < 74088; ++i) {
        const auto result = pbrDeltaCase_0(i, 0u);
        delta += result.y;
        guide += result.z;
        tir += result.w;
        // Signed zero has identical ray geometry; bit 8 rejects every numeric difference.
        if ((result.x & ~128u) != 0) {
            if (failures < 25)
                std::printf("FAIL case=%u mask=%u\n", i, result.x);
            ++failures;
        }
        for (unsigned bit = 0; bit < 9; ++bit)
            if (result.x & (1u << bit))
                ++masks[bit];
    }
    for (unsigned i = 0; i < 9; ++i) {
        if (pbrDeltaEdgeCheck_0(i).x != 0) {
            ++failures;
            std::printf("FAIL edge=%u\n", i);
        }
    }
    std::printf(
            "PBR narrow contracts: 74088 cases, 9 edges; failures=%u delta=%u guide=%u TIR=%u\n",
            failures, delta, guide, tir);
    for (unsigned bit = 0; bit < 9; ++bit)
        std::printf("mask%u=%u ", 1u << bit, masks[bit]);
    std::puts("");
    return failures || delta < 10000 || guide < 10000 || tir < 500 ? 1 : 0;
}
