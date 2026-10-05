#language slang 2026
module "display/image_space.slang";

// World snapshots, SDK inputs and swapchain pixels are canonical top-left.
// Minecraft's intermediate UI target is bottom-up; its SDR surface blit flips Y.
// HDR writes the swapchain directly and must perform that same conversion here.
public uint2 primePresentUiPixel(uint2 pixel, uint2 extent, bool bottomUp) {
    return uint2(pixel.x, bottomUp ? extent.y - 1u - pixel.y : pixel.y);
}

// Only texels with positive interpolation weight belong to raw's support.
// In DLAA frac(position)==0, so an adjacent unresolved guide cannot expand fallback.
public uint primeRrFootprintMask(float2 position) {
    float2 blend = frac(position);
    return 1u | (blend.x > 0.0 ? 2u : 0u) | (blend.y > 0.0 ? 4u : 0u) |
           (all(blend > 0.0) ? 8u : 0u);
}
