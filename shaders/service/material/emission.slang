#language slang 2026
module "service/material/emission.slang";

import "prime_abi_types.slang";
import "service/material/coverage.slang";
import "service/material/static_color.slang";
import "service/material/emission_access.slang";
import "model/material/labpbr.slang";
import "policy/runtime/config.slang";

static const uint PRIME_EMITTER_FLAG_TWO_SIDED = 1u;
static const uint PRIME_EMITTER_FLAG_LABPBR_EMISSION = 2u;

public float3 primeEvaluateEmitterRadiance(
        uint textureId,
        uint atlasTint,
        uint emitterFlags,
        float fallbackRadiance,
        float2 uv,
        float textureLodValue) {
    float opacity;
    uint packedFrameExtent;
    float3 color = primeStaticBaseColor(
            textureId,
            atlasTint,
            uv,
            textureLodValue,
            opacity,
            packedFrameExtent);
    bool cutout = (emitterFlags & PRIME_EMITTER_FLAG_TWO_SIDED) != 0u;
    if (cutout && opacity < PRIME_CUTOUT_ALPHA_THRESHOLD) {
        return float3(0.0);
    }
    // Minecraft's 0..15 block-light value is an ordinal influence radius, not radiometry. Prime's
    // documented fallback maps its squared normalized level to the ABI source calibration, so a
    // white level-15 texel has radiance PRIME_LEVEL_15_BLOCK_INTENSITY. The
    // path geometry term supplies the actual inverse-square falloff; never add it here as well.
    // This global radiometric scale is uniform over every emitter. It therefore changes neither
    // light-tree selection probabilities nor their PDFs and does not require rebuilding the tree.
    float authoredEmission = 0.0;
    if ((emitterFlags & PRIME_EMITTER_FLAG_LABPBR_EMISSION) != 0u) {
        authoredEmission = primeDecodeLabPbrEmission(
                primeSampleMaterialEmission(
                        textureId,
                        packedFrameExtent,
                        uv,
                        textureLodValue));
    }
    // LabPBR defines only a normalized emissiveness, not absolute radiometry. Prime maps 100%
    // to the same calibrated radiance as a white vanilla level-15 source. Once authored, this
    // channel replaces Minecraft's block-light value; alpha 0 can intentionally turn it off.
    float radianceScale = (emitterFlags & PRIME_EMITTER_FLAG_LABPBR_EMISSION) != 0u
            ? authoredEmission * PRIME_LEVEL_15_BLOCK_INTENSITY
            : max(fallbackRadiance, 0.0);
    return color * radianceScale * primeBlockLightRadianceMultiplier();
}

public float3 primeEvaluateEmitterRadiance(
        uint textureId,
        uint atlasTint,
        uint emitterFlags,
        float fallbackRadiance,
        float2 uv) {
    // A sampled light point has no screen-space ray footprint. The distribution was constructed
    // from mip 0, so evaluating that same radiometric function preserves its PDF contract.
    return primeEvaluateEmitterRadiance(
            textureId, atlasTint, emitterFlags, fallbackRadiance, uv, 0.0);
}
