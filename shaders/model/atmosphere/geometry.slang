// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_LICENSES/SKY-TRACER-NOTICE.md.

#language slang 2026
module "model/atmosphere/geometry.slang";

import "model/atmosphere/constants.slang";

public float atmHorizon(float h) {
    return -sqrt(max(h * (2.0 * ATM_BOTTOM_RADIUS_KM + h), 0.0)) / (ATM_BOTTOM_RADIUS_KM + h);
}

public bool atmGround(float h, float mu) { return mu < 0.0 && mu <= atmHorizon(h); }

public float atmHeightAt(float h, float mu, float d) {
    let dh = d * (d + 2.0 * (ATM_BOTTOM_RADIUS_KM + h) * mu);
    return max(0.0, h + dh / (sqrt(max((ATM_BOTTOM_RADIUS_KM + h) * (ATM_BOTTOM_RADIUS_KM + h) + dh,
                                       0.0)) +
                              ATM_BOTTOM_RADIUS_KM + h));
}

public float atmBoundary(float h, float mu, bool hit) {
    let b = (ATM_BOTTOM_RADIUS_KM + h) * mu;
    if (hit) {
        let c = h * (2.0 * ATM_BOTTOM_RADIUS_KM + h);
        return max(0.0, c / max(-b + sqrt(max(b * b - c, 0.0)), 1e-20));
    }
    let c = (ATM_THICKNESS_KM - h) * (2.0 * ATM_BOTTOM_RADIUS_KM + ATM_THICKNESS_KM + h);
    let root = sqrt(max(b * b + c, 0.0));
    if (b > 0.0) {
        return max(0.0, c / max(root + b, 1e-20));
    }
    return max(0.0, -b + root);
}
