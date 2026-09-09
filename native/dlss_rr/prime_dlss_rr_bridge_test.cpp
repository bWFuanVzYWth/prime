// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

// Exercise the exact production ABI-to-NGX mapping without initializing NGX or a Vulkan device.
#include "prime_dlss_rr_bridge.cpp"
#include <cstdio>

int main() {
    PrimeEvaluateDescription description{};
    for (auto& image : description.images) {
        image.image = 1;
        image.view = 2;
        image.format = VK_FORMAT_R16G16B16A16_SFLOAT;
        image.width = 320;
        image.height = 180;
    }
    description.images[SPECULAR_MOTION_VECTORS].format = VK_FORMAT_R32G32_SFLOAT;
    description.images[SPECULAR_HIT_DISTANCE].format = VK_FORMAT_R16_SFLOAT;
    std::array<NVSDK_NGX_Resource_VK, IMAGE_COUNT> resources{};
    auto explicitMotion = makeEvaluation(description, resources);
    if (explicitMotion.pInMotionVectorsReflections != &resources[SPECULAR_MOTION_VECTORS]
            || explicitMotion.pInSpecularHitDistance != &resources[SPECULAR_HIT_DISTANCE]) return 1;
    description.images[SPECULAR_MOTION_VECTORS] = {};
    description.images[RESPONSIVITY] = {};
    auto distanceOnly = makeEvaluation(description, resources);
    if (distanceOnly.pInMotionVectorsReflections != nullptr
            || distanceOnly.pInResponsivityMask != nullptr
            || distanceOnly.pInSpecularHitDistance != &resources[SPECULAR_HIT_DISTANCE]
            || distanceOnly.pInMotionVectors != &resources[MOTION_VECTORS]
            || distanceOnly.pInWorldToViewMatrix != description.worldToView
            || distanceOnly.pInViewToClipMatrix != description.viewToClip) return 2;
    auto malformed = description.images[SPECULAR_MOTION_VECTORS];
    malformed.width = 1;
    if (absentImage(malformed) || validImage(malformed, VK_FORMAT_R32G32_SFLOAT, 320, 180)) return 3;
    std::puts("DLSS reflection motion: explicit, absent, matrices and malformed descriptor passed.");
    return 0;
}
