// Executes actual Slang-generated tree selection/PDF code without a Vulkan device.
#include "light_tree.generated.cpp"
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>

static constexpr uint32_t N = 1u << 24;
static constexpr uint32_t leaf = 0x80000000u;
static unsigned checks = 0;
static void require(bool condition, const char *label) {
    ++checks;
    if (!condition) {
        std::fprintf(stderr, "FAIL %s check=%u\n", label, checks);
        std::exit(1);
    }
}
static float sample(uint32_t value) {
    return float(value) * (1.0f / float(N));
}
static float asFloat(uint32_t value) {
    float result;
    std::memcpy(&result, &value, sizeof(result));
    return result;
}

int main() {
    static_assert(sizeof(TreeNode_0) == 8);
    static_assert(offsetof(TreeNode_0, child_0) == 4);
    static_assert(sizeof(TreePage_0) == 48);
    static_assert(offsetof(TreePage_0, origin_0) == 16);
    static_assert(offsetof(TreePage_0, pdf_0) == 40);
    static_assert(sizeof(TreeEmitter_0) == 16);
    static_assert(sizeof(LightTree_0) == 32);
    static_assert(offsetof(LightTree_0, worldCount_0) == 28);

    // Sparse stable page/global IDs and one-selector-point leaves at either extreme.
    TreeNode_0 world[] = {{N - 3, 1}, {0, leaf | 2}, {N - 1, 3}, {0, leaf | 7}, {0, leaf | 11}};
    TreeNode_0 local2[] = {{1, 1}, {0, leaf}, {N - 1, 3}, {0, leaf | 1}, {0, leaf | 2}};
    TreeNode_0 local7[] = {{0, leaf}};
    TreeNode_0 local11[] = {{N - 1, 1}, {0, leaf}, {0, leaf | 1}};
    uint32_t records2[180] = {};
    uint32_t records7[68] = {};
    uint32_t records11[216] = {};
    TreePage_0 pages[12] = {};
    pages[2] = {local2, records2, {1.25f, -2.5f, 3.75f}, 1, 1, 3, sample(N - 3), 0};
    pages[7] = {local7, records7, {-4.5f, 5.25f, -6.0f}, 2, 9, 1, sample(2), 0};
    pages[11] = {local11, records11, {19.0f, -21.0f, 23.0f}, 3, 20, 2, sample(1), 0};
    TreeEmitter_0 emitters[22] = {};
    emitters[1] = {2, 0, sample(1), 0.5f};
    emitters[2] = {2, 1, sample(N - 2), 1.0f};
    emitters[3] = {2, 2, sample(1), 2.0f};
    emitters[9] = {7, 0, 1.0f, 4.0f};
    emitters[20] = {11, 0, sample(N - 1), 8.0f};
    emitters[21] = {11, 1, sample(1), 16.0f};
    LightTree_0 tree{world, pages, emitters, 1.0f, 3};

    std::array<uint32_t, 12> worldCounts{};
    std::array<uint32_t, 3> localCounts{};
    for (uint32_t k = 0; k < N; ++k) {
        ++worldCounts[treeCpuSelect_0(world, sample(k))];
        ++localCounts[treeCpuSelect_0(local2, sample(k))];
    }
    require(worldCounts[2] == N - 3 && worldCounts[7] == 2 && worldCounts[11] == 1,
            "exhaustive world selector frequencies match exact published PMF");
    require(localCounts[0] == 1 && localCounts[1] == N - 2 && localCounts[2] == 1,
            "exhaustive local selector frequencies preserve rare first and last leaves");
    require(treeCpuSelect_0(local7, sample(0)) == 0 && treeCpuSelect_0(local7, sample(N - 1)) == 0,
            "single-emitter tree accepts complete selector domain");

    const uint32_t witnesses[] = {0, 1, 2, N / 2, N - 4, N - 3, N - 2, N - 1};
    for (uint32_t w : witnesses)
        for (uint32_t l : witnesses) {
            const auto selected = treeCpuSelection_0(&tree, {sample(w), sample(l)});
            const uint32_t page = w < N - 3 ? 2 : (w < N - 1 ? 7 : 11);
            const uint32_t local = page == 2 ? (l < 1 ? 0 : (l < N - 1 ? 1 : 2))
                                             : (page == 7 ? 0 : uint32_t(l >= N - 1));
            const uint32_t id = pages[page].first_0 + local;
            require(selected.x == id && selected.y == page && selected.z == local,
                    "two independent selectors resolve stable emitter identity");
            const auto carried = treeCpuPage_0(&tree, {sample(w), sample(l)});
            require(carried.nodes_0 == pages[page].nodes_0 &&
                            carried.emitters_0 == pages[page].emitters_0 &&
                            carried.origin_0.x == pages[page].origin_0.x &&
                            carried.origin_0.y == pages[page].origin_0.y &&
                            carried.origin_0.z == pages[page].origin_0.z &&
                            carried.format_0 == pages[page].format_0 &&
                            carried.first_0 == pages[page].first_0 &&
                            carried.count_0 == pages[page].count_0 &&
                            carried.pdf_0 == pages[page].pdf_0,
                    "carried page preserves source pointers, origin, format and sampling metadata");
            const float forward = asFloat(selected.w);
            const float reverse = treeCpuReverse_0(&tree, id);
            require(forward == reverse && forward == pages[page].pdf_0 * emitters[id].pdf_1,
                    "forward and reverse use identical published marginal PMF");
            require(std::isfinite(forward) && forward > 0,
                    "rare page and rare emitter joint PDF stays positive and finite");
            require(forward * emitters[id].inverseArea_0 == reverse * emitters[id].inverseArea_0,
                    "area conversion retains forward and reverse equality");
        }
    double mass = 0;
    for (uint32_t id : {1u, 2u, 3u, 9u, 20u, 21u})
        mass += treeCpuReverse_0(&tree, id);
    require(std::abs(mass - 1.0) < 1e-7, "joint published probabilities normalize");
    std::printf("light tree shader CPU: %u checks passed; exhausted 2 x 2^24 selectors\n", checks);
}
