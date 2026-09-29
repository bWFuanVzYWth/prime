// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_NOTICES.md.

#language slang 2026
module "atmosphere/transmittance.slang";
import "atmosphere/resources.slang";
import "atmosphere/sky.slang";

import "atmosphere/parameters.slang";
import "atmosphere/geometry.slang";
import "atmosphere/optical_mapping.slang";

// Below-horizon coordinates extend the tangent value. Direction visibility is evaluated
// separately for the camera table so interpolation never crosses a stored zero discontinuity.
public float4 atmOpticalTransmittance(AtmModel model, float h, float mu) {
    return exp(-model.opticalDepth.SampleLevel(atmOpticalUv(h, mu), 0.0));
}
public float4 atmSunT(AtmModel model, float h, float mu) {
    return atmGround(h, mu) ? float4(0.0) : atmOpticalTransmittance(model, h, mu);
}
