// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/material/emission_access.slang";
import "service/material/access.slang";
import "service/material/coverage.slang";
import "math/packed_geometry.slang";
import "service/material/optical_pages.slang";

// Emission only consumes filtered alpha. Normal and categorical PBR reads cannot enter this closure.
public float primeSampleMaterialEmission(uint textureId, uint packedFrameExtent, float2 uv, float lod) {
    uint3 channel = primeTextureRecords.Load3(textureId * PRIME_TEXTURE_RECORD_SIZE + 16u);
    uint page = channel.x >> 8u & 0xffu;
    int2 backingExtent;
    if (!primeDecodeTexturePageExtent(channel.z >> 16u, backingExtent)) {
        uint width, height, levels;
        primeMaterialOpticalPages[NonUniformResourceIndex(page)].GetDimensions(0, width, height, levels);
        backingExtent = int2(width, height);
    }
    float2 translated = primeTranslatedUv(channel.y,
            int2(packedFrameExtent & 0xffffu, packedFrameExtent >> 16u), backingExtent, uv);
    return primeMaterialOpticalPages[NonUniformResourceIndex(page)].SampleLevel(
            translated, min(lod, float(channel.x >> 24u & 0xffu))).a;
}
