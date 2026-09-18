// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_LICENSES/SKY-TRACER-NOTICE.md.

#language slang 2026
module "model/atmosphere/transmittance.slang";
import "model/atmosphere/sky.slang";

import "model/atmosphere/parameters.slang";
import "model/atmosphere/geometry.slang";
import "model/atmosphere/optical_mapping.slang";

[[vk::binding(0, 0)]] public Sampler2D<float4> opticalDepth;
// Below-horizon coordinates extend the tangent value. Direction visibility is evaluated
// separately for the camera table so interpolation never crosses a stored zero discontinuity.
public float4 atmOpticalTransmittance(float h, float mu) {
    return exp(-opticalDepth.SampleLevel(atmOpticalUv(h, mu), 0.0));
}
public float4 atmSunT(float h, float mu) {
    return atmGround(h, mu) ? float4(0.0) : atmOpticalTransmittance(h, mu);
}
