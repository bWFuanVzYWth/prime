// Executes the actual production Slang distance sampler without a Vulkan device.
#include "light_tree.generated.cpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

using F3 = Vector<float, 3>;
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
static uint32_t path(uint32_t bits, uint32_t depth) {
    return bits | (depth << 27);
}
static bool nearCount(uint32_t actual, uint32_t expected) {
    // The independent analytic ratio can round one selector differently from scaled FP32.
    return std::abs(int64_t(actual) - int64_t(expected)) <= 1;
}
static TreeNode_0 node(F3 center, float power, uint32_t child, uint32_t leaves) {
    return {center, power, child, leaves};
}
static void quad(uint32_t *record, float x, bool degenerate = false) {
    const float positions[16] = {x - 1, -1, 0, 0.5f, x + 1, -1, 0, 0,
                                 x + 1, 1,  0, 0,    x - 1, 1,  0, 0};
    std::memcpy(record, positions, sizeof(positions));
    if (degenerate)
        std::memcpy(record + 12, record + 8, 3 * sizeof(float));
}
static uint32_t left(TreePage_0 page, TreeNode_0 a, TreeNode_0 b, bool world, F3 point,
                     uint32_t count = N) {
    return treeCpuLeft_0(&page, &a, &b, world, point, count);
}
static void jointWitnesses(LightTree_0 tree, const std::vector<uint32_t> &lights, F3 point) {
    double mass = 0;
    for (uint32_t id : lights) {
        const auto reference = tree.emitters_1[id];
        auto page = tree.pages_0[reference.page_0];
        const auto w = treeCpuInterval_0(tree.world_0, &page, page.path_0, true, point);
        const auto l = treeCpuInterval_0(page.nodes_0, &page, reference.path_1, false,
                                         point - page.origin_0);
        require(w.y > 0 && l.y > 0 && w.x + w.y <= N && l.x + l.y <= N,
                "every leaf retains a finite 24-bit selector interval");
        const float expected = sample(w.y) * sample(l.y);
        const float reverse = treeCpuReverse_0(&tree, id, point);
        require(reverse == expected && std::isfinite(reverse) && reverse > 0,
                "reverse PDF is the actual joint integer PMF");
        mass += reverse;
        for (uint32_t endpoint : {0u, 1u}) {
            const Vector<float, 2> samples{sample(w.x + endpoint * (w.y - 1)),
                                           sample(l.x + endpoint * (l.y - 1))};
            const auto selected = treeCpuSelection_0(&tree, samples, point);
            require(selected.x == id && selected.y == reference.page_0 &&
                            selected.z == reference.emitter_0,
                    "interval endpoints select the stable page/emitter identity");
            require(asFloat(selected.w) == reverse,
                    "production forward and reverse PDF agree bit for bit");
            float pdf;
            TreePage_0 carried;
            TreeEmitter_0 emitter;
            selectTreeLight_0(&tree, samples, point, &pdf, &carried, &emitter);
            require(carried.nodes_0 == page.nodes_0 && carried.emitters_0 == page.emitters_0 &&
                            carried.origin_0.x == page.origin_0.x &&
                            carried.origin_0.y == page.origin_0.y &&
                            carried.origin_0.z == page.origin_0.z &&
                            carried.format_0 == page.format_0 && carried.first_0 == page.first_0 &&
                            carried.count_0 == page.count_0 && carried.path_0 == page.path_0,
                    "selection preserves the carried page layout and replay path");
            require(pdf * emitter.inverseArea_0 == reverse * reference.inverseArea_0,
                    "area conversion preserves forward and reverse equality");
        }
    }
    require(std::abs(mass - 1.0) < 2e-7, "joint lattice probabilities normalize");
}

int main() {
    static_assert(sizeof(TreeNode_0) == 24);
    static_assert(offsetof(TreeNode_0, centroid_0) == 0);
    static_assert(offsetof(TreeNode_0, power_0) == 12);
    static_assert(offsetof(TreeNode_0, child_0) == 16);
    static_assert(offsetof(TreeNode_0, leaves_0) == 20);
    static_assert(sizeof(TreePage_0) == 48);
    static_assert(offsetof(TreePage_0, origin_0) == 16);
    static_assert(offsetof(TreePage_0, path_0) == 40);
    static_assert(sizeof(TreeEmitter_0) == 16);
    static_assert(offsetof(TreeEmitter_0, path_1) == 8);
    static_assert(sizeof(LightTree_0) == 32);
    static_assert(offsetof(LightTree_0, worldCount_0) == 28);

    // Sparse stable IDs; all three production emitter strides, with a degenerate quad half.
    TreeNode_0 world[] = {node({9, 0, 0}, 6, 1, 3), node({2, 0, 0}, 3, leaf | 2, 1),
                          node({16, 0, 0}, 3, 3, 2), node({12, 0, 0}, 1, leaf | 7, 1),
                          node({22, 0, 0}, 2, leaf | 11, 1)};
    TreeNode_0 local2[] = {node({4, 0, 0}, 3, 1, 3), node({0, 0, 0}, 1, leaf, 1),
                           node({6, 0, 0}, 2, 3, 2), node({4, 0, 0}, 1, leaf | 1, 1),
                           node({8, 0, 0}, 1, leaf | 2, 1)};
    TreeNode_0 local7[] = {node({0, 0, 0}, 1, leaf, 1)};
    TreeNode_0 local11[] = {node({2, 0, 0}, 2, 1, 2), node({0, 0, 0}, 1, leaf, 1),
                            node({4, 0, 0}, 1, leaf | 1, 1)};
    uint32_t records2[180] = {}, records7[68] = {}, records11[216] = {};
    for (uint32_t i = 0; i < 3; ++i)
        quad(records2 + 60 * i, float(i * 4));
    quad(records7, 0, true);
    quad(records11, 0);
    quad(records11 + 108, 4);
    TreePage_0 pages[12] = {};
    pages[2] = {local2, records2, {1.25f, -2.5f, 3.75f}, 1, 1, 3, path(0, 1), 0};
    pages[7] = {local7, records7, {-4.5f, 5.25f, -6.0f}, 2, 9, 1, path(1, 2), 0};
    pages[11] = {local11, records11, {19.0f, -21.0f, 23.0f}, 3, 20, 2, path(3, 2), 0};
    TreeEmitter_0 emitters[22] = {};
    emitters[1] = {2, 0, path(0, 1), 0.5f};
    emitters[2] = {2, 1, path(1, 2), 1.0f};
    emitters[3] = {2, 2, path(3, 2), 2.0f};
    emitters[9] = {7, 0, path(0, 0), 4.0f};
    emitters[20] = {11, 0, path(0, 1), 8.0f};
    emitters[21] = {11, 1, path(1, 1), 16.0f};
    LightTree_0 tree{world, pages, emitters, 6.0f, 3};
    const std::vector<uint32_t> ids{1, 2, 3, 9, 20, 21};
    for (F3 point : {F3{1.25f, -2.5f, 3.75f}, F3{1.5f, -2.3f, 3.75f}, F3{2.25f, -1.5f, 3.75f},
                     F3{23, 2, 8}, F3{23, 2, -8}})
        jointWitnesses(tree, ids, point);
    require(treeCpuReverse_0(&tree, 1, {1.25f, -2.5f, 4.75f}) !=
                    treeCpuReverse_0(&tree, 1, {9.25f, -2.5f, 4.75f}),
            "receiver motion changes the proposal rather than retaining a power-only PDF");

    // Analytic branch oracle: equal power at center distance 1 and 9 gives 81:1.
    auto a = node({0, 0, 0}, 1, leaf, 1), b = node({10, 0, 0}, 1, leaf | 1, 1);
    const uint32_t expected = uint32_t(std::round(float(N) * (81.0f / 82.0f)));
    require(left(pages[2], a, b, true, {1, 0, 0}) == expected,
            "world distance follows power / squared center distance");
    require(left(pages[2], a, b, true, {9, 0, 0}) == N - expected,
            "symmetric receiver motion swaps world distance probability");
    // Quad AABBs at [-1,1] and [3,5], receiver x=-2: 1 and 5, giving 25:1.
    a = local2[1];
    b = local2[3];
    require(nearCount(left(pages[2], a, b, false, {-2, 0, 0}),
                      uint32_t(std::round(float(N) * (25.0f / 26.0f)))),
            "local leaves use distance to quad bounds instead of centroid");
    require(nearCount(left(pages[2], a, b, false, {0, 0, 0}),
                      uint32_t(std::round(float(N) * (9.0f / 17.0f)))),
            "coplanar leaf center uses squared edge extent fallback");
    const uint32_t inside = left(pages[2], a, b, false, {0.5f, 0, 0});
    require(inside > N * 0.9f && inside < N,
            "inside AABB away from center uses finite centroid fallback");
    uint32_t records68[204] = {}, records108[324] = {};
    for (uint32_t i = 0; i < 3; ++i) {
        quad(records68 + 68 * i, float(i * 4));
        quad(records108 + 108 * i, float(i * 4));
    }
    const auto savedPage = pages[2];
    for (uint32_t format : {2u, 3u}) {
        pages[2].format_0 = format;
        pages[2].emitters_0 = format == 2 ? records68 : records108;
        require(left(pages[2], a, b, false, {0.5f, 0, 0}) == inside,
                "all production emitter strides use the same geometric distance");
        jointWitnesses(tree, ids, {23, 2, 8});
    }
    pages[2] = savedPage;

    // Positive powers many orders apart retain exact support under normalized scoring.
    world[1].power_0 = 1e-30f;
    world[3].power_0 = 1e30f;
    world[4].power_0 = 1e-30f;
    local2[1].power_0 = 1e-30f;
    local2[3].power_0 = 1e30f;
    local2[4].power_0 = 1e-30f;
    const F3 receiver{23, 2, 8};
    jointWitnesses(tree, ids, receiver);
    std::array<uint32_t, 12> worldCounts{};
    std::array<uint32_t, 3> localCounts{};
    TreeNode_0 singleton = node({0, 0, 0}, 1, leaf | 2, 1);
    LightTree_0 localTree{&singleton, pages, emitters, 1, 1};
    for (uint32_t k = 0; k < N; ++k) {
        ++worldCounts[treeCpuSelection_0(&tree, {sample(k), 0}, receiver).y];
        ++localCounts[treeCpuSelection_0(&localTree, {0, sample(k)}, receiver).z];
    }
    for (uint32_t pageId : {2u, 7u, 11u}) {
        auto page = pages[pageId];
        require(worldCounts[pageId] ==
                        treeCpuInterval_0(world, &page, page.path_0, true, receiver).y,
                "exhaustive world selectors match the inverse integer PMF");
    }
    for (uint32_t i = 0; i < 3; ++i)
        require(localCounts[i] == treeCpuInterval_0(local2, &pages[2], emitters[i + 1].path_1,
                                                    false, receiver - pages[2].origin_0)
                                          .y,
                "exhaustive local selectors retain every rare leaf");

    // Boundary depth and one-selector support: a 27-branch chain behind a tiny subtree.
    std::vector<TreeNode_0> deep(55);
    std::vector<uint32_t> deepRecords(28 * 60);
    std::vector<TreeEmitter_0> deepEmitters(28);
    for (uint32_t d = 0; d < 27; ++d) {
        const uint32_t at = d == 0 ? 0 : 2 * d;
        deep[at] = node({0, 0, 0}, d == 0 ? 1 : float(28 - d) * 1e-30f, 2 * d + 1, 28 - d);
        deep[2 * d + 1] = node({0, 0, 0}, d == 0 ? 1 : 1e-30f, leaf | d, 1);
        deepEmitters[d] = {0, d, path((1u << d) - 1u, d + 1), 1};
        quad(deepRecords.data() + d * 60, 0);
    }
    deep[54] = node({0, 0, 0}, 1e-30f, leaf | 27, 1);
    deepEmitters[27] = {0, 27, path((1u << 27) - 1u, 27), 1};
    quad(deepRecords.data() + 27 * 60, 0);
    TreeNode_0 onlyWorld = node({0, 0, 0}, 1, leaf, 1);
    TreePage_0 deepPage{deep.data(), deepRecords.data(), {0, 0, 0}, 1, 0, 28, 0, 0};
    LightTree_0 deepTree{&onlyWorld, &deepPage, deepEmitters.data(), 1, 1};
    std::vector<uint32_t> deepIds(28);
    for (uint32_t i = 0; i < 28; ++i)
        deepIds[i] = i;
    jointWitnesses(deepTree, deepIds, {0, 0, 1});
    require(treeCpuReverse_0(&deepTree, 27, {0, 0, 1}) == sample(1),
            "deep tiny subtree retains a one-selector leaf");
    deepEmitters[0].path_1 = path(1, 1);
    require(treeCpuReverse_0(&deepTree, 0, {0, 0, 1}) == 0,
            "a stale replay path pointing at another leaf is rejected locally");
    std::printf("distance tree Slang CPU: %u checks passed; exhausted 2 x 2^24 selectors\n",
                checks);
}
