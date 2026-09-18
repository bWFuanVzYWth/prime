// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_LICENSES/SKY-TRACER-NOTICE.md.
#language slang 2026
module "model/atmosphere/optical_mapping.slang";
import "model/atmosphere/parameters.slang";
import "model/atmosphere/sky.slang";
import "model/atmosphere/geometry.slang";
public float atmHeightCoord(float h) {
    {
        let x = sqrt(clamp(h, 0.0, ATM_THICKNESS_KM));
        var j = 0u;
        for (var i = 0u; i < 5u; i++) {
            if (x > ATM_OPTICAL_SEGMENTS[i].x) {
                j = i + 1u;
            }
        }
        let c = ATM_OPTICAL_SEGMENTS[j];
        return clamp(x * c.y + c.z, 0.0, 1.0);
    }
}

public float2 atmOpticalUv(float h, float mu) {
    float x = clamp((mu - atmHorizon(h)) / (1.0 - atmHorizon(h)), 0.0, 1.0);
    float u = pow(x, 1.0 / 3.0);
    float2 pixel =
        float2(u * float(ATM_DIMS.w - 1u), atmHeightCoord(h) * float(ATM_DIMS.z - 1u)) + 0.5;
    return pixel / float2(ATM_DIMS.w, ATM_DIMS.z);
}
