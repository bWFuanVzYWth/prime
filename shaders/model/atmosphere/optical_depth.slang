// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_LICENSES/SKY-TRACER-NOTICE.md.
#language slang 2026
module "model/atmosphere/optical_depth.slang";
import "model/atmosphere/parameters.slang";
import "model/atmosphere/sky.slang";
import "model/atmosphere/geometry.slang";
import "model/atmosphere/spectrum.slang";
public float4 atmOpticalDepth(uint2 pixel) {
    let h = atmData[ATM_MAPPING.z + pixel.y].x;
    let v = float(pixel.x) / float(ATM_DIMS.w - 1u);
    let a = v * v * v;
    var x = a;
    let mu = atmHorizon(h) + (1.0 - atmHorizon(h)) * x;
    let length = atmBoundary(h, mu, false);
    let dt = length / float(ATM_EXTRA.w);
    var tau = float4(0.0);
    for (var j = 0u; j < ATM_EXTRA.w; j++) {
        tau += atmMedium(atmHeightAt(h, mu, (float(j) + 0.5) * dt)).extinction * dt;
    }
    return min(tau, float4(80.0));
}
