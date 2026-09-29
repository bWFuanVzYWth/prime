// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_NOTICES.md.

#language slang 2026
module "atmosphere/spectrum.slang";
import "atmosphere/resources.slang";

import "atmosphere/parameters.slang";
import "atmosphere/sky.slang";

public struct AtmMedium {
    public float4 extinction;
    public float4 scattering[5];
};
public struct AtmPhaseStencil {
    public uint index;
    public float fraction;
};
struct AtmMediumStencil {
    uint a;
    uint b;
    float fraction;
};
AtmMediumStencil atmMediumStencil(AtmModel model, float h) {
    var lo = 0u;
    var hi = ATM_OFFSETS1.z - 1u;
    while (hi - lo > 1u) {
        let mid = (lo + hi) / 2u;
        if (model.data[ATM_OFFSETS0.z + 7u * mid].x <= h) {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    let a = ATM_OFFSETS0.z + lo * 7u;
    let b = ATM_OFFSETS0.z + hi * 7u;
    return {a, b, clamp((h - model.data[a].x) / (model.data[b].x - model.data[a].x), 0.0, 1.0)};
}
public float4 atmExtinction(AtmModel model, float h) {
    let s = atmMediumStencil(model, h);
    return lerp(model.data[s.a + 1u], model.data[s.b + 1u], s.fraction);
}
public AtmMedium atmMedium(AtmModel model, float h) {
    let s = atmMediumStencil(model, h);
    AtmMedium result;
    result.extinction = lerp(model.data[s.a + 1u], model.data[s.b + 1u], s.fraction);
    for (var j = 0u; j < 5u; j++) {
        result.scattering[j] = lerp(model.data[s.a + 2u + j], model.data[s.b + 2u + j], s.fraction);
    }
    return result;
}

public AtmPhaseStencil atmPhaseStencil(float mu) {
    let x = clamp(pow(max((1.0 - mu) * 0.5, 0.0), 1.0 / 3.0) * 1024.0 - 0.5, 0.0, 1023.0);
    let i = min(uint(x), 1022u);
    return {i, x - float(i)};
}

public float4 atmTabulatedPhase(AtmModel model, AtmPhaseStencil stencil, uint species) {
    let offset = ATM_OFFSETS0.w + species * 1024u + stencil.index;
    return lerp(model.data[offset], model.data[offset + 1u], stencil.fraction);
}
public float3 atmLinearRec2020FromSpectral(float4 value) {
    return value[0] * float3(5.8809709548950195, -7.0044331550598145, 90.48178100585938) +
           value[1] * float3(-14.151336669921875, 54.43069839477539, 8.974457740783691) +
           value[2] * float3(87.37126159667969, 58.01542282104492, -1.4691294431686401) +
           value[3] * float3(31.710432052612305, -1.2180235385894775, 0.05091293156147003);
}

// Normalize the fitted solar spectrum to Prime's existing achromatic extraterrestrial sun.
// Scaling after spectral transport preserves linearity of every scattering order.
public float3 atmRadiance(float4 value) {
    return max(atmLinearRec2020FromSpectral(value), float3(0.0)) *
           (ATM_SPACE_SUN_INTENSITY / atmLinearRec2020FromSpectral(ATM_SOLAR));
}
public float3 atmRec2020Transmittance(float4 value) {
    return clamp(atmLinearRec2020FromSpectral(value * ATM_SOLAR) /
                         atmLinearRec2020FromSpectral(ATM_SOLAR),
                 float3(0.0), float3(1.0));
}
