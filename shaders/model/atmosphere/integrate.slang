// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_LICENSES/SKY-TRACER-NOTICE.md.

#language slang 2026
module "model/atmosphere/integrate.slang";
import "model/atmosphere/sky.slang";
import "model/atmosphere/parameters.slang";
import "model/atmosphere/spectrum.slang";

import "model/atmosphere/geometry.slang";
import "model/atmosphere/transmittance.slang";
import "model/atmosphere/source.slang";

public struct AtmTransport {
    public float4 light;
    public float4 transmittance;
};
public AtmTransport atmIntegrateAt(float3 ray, float3 sun, float initialHeight, float segment, uint steps,
                            bool multiple) {
    AtmTransport result;
    result.light = float4(0.0);
    result.transmittance = float4(1.0);
    var h = initialHeight;
    var mu = ray.y;
    var sunMu = sun.y;
    let nu = dot(ray, sun);
    var entry = 0.0;
    var up = float3(0.0, 1.0, 0.0);
    if (h > ATM_THICKNESS_KM) {
        let radius = ATM_BOTTOM_RADIUS_KM + h;
        let b = radius * mu;
        let c = (h - ATM_THICKNESS_KM) * (radius + ATM_BOTTOM_RADIUS_KM + ATM_THICKNESS_KM);
        let disc = b * b - c;
        if (b >= 0.0 || disc <= 0.0) {
            return result;
        }
        entry = c / (-b + sqrt(disc));
        if (segment > 0.0 && entry >= segment) {
            return result;
        }
        up = normalize(up * radius + ray * entry);
        h = ATM_THICKNESS_KM;
        mu = dot(ray, up);
        sunMu = dot(sun, up);
    }
    let hit = atmGround(h, mu);
    let fullLength = atmBoundary(h, mu, hit);
    var length = fullLength;
    if (segment > 0.0) {
        length = min(length, max(segment - entry, 0.0));
    }
    let count = steps;
    // Concentrate both sides of a grazing path near its minimum height.
    let b = (ATM_BOTTOM_RADIUS_KM + h) * mu;
    let middle = clamp(-b, 0.0, length);
    let hmin = atmHeightAt(h, mu, middle);
    let hend = atmHeightAt(h, mu, length);
    let scale = max(0.25, 1e-10);
    var before = 0.0;
    var after = 0.0;
    if (0.25 > 0.0) {
        before = log(1.0 + max(h - hmin, 0.0) / scale);
        after = log(1.0 + max(hend - hmin, 0.0) / scale);
    }
    var split = uint(round(float(count) * before / max(before + after, 1e-20)));
    if (middle <= 0.0) {
        split = 0u;
    } else if (middle >= length) {
        split = count;
    } else {
        split = clamp(split, 1u, count - 1u);
    }
    float4 solarPhase[5] = {};
    let cosine = clamp(nu, -1.0, 1.0);
    let stencil = atmPhaseStencil(cosine);
    solarPhase[0] = float4(3.0 * (1.0 + cosine * cosine) / (16.0 * ATM_PI));
    for (var k = 0u; k < 4u; k++) {
        solarPhase[k + 1u] = atmTabulatedPhase(stencil, k);
    }
    var previous = 0.0;
    for (var i = 1u; i <= count; i++) {
        var edge = length * float(i) / float(count);
        if (before + after >= 1e-5 && i < count) {
            if (i == split) {
                edge = middle;
            } else {
                let incoming = i < split;
                var lh = after * float(i - split) / max(float(count - split), 1.0);
                if (incoming) {
                    lh = before * (1.0 - float(i) / float(split));
                }
                let hh = hmin + scale * (exp(lh) - 1.0);
                let c = (h - hh) * (2.0 * ATM_BOTTOM_RADIUS_KM + h + hh);
                let root = sqrt(max(b * b - c, 0.0));
                if (incoming) {
                    edge = c / max(-b + root, 1e-20);
                } else if (b > 0.0) {
                    edge = -c / max(root + b, 1e-20);
                } else {
                    edge = -b + root;
                }
                edge = clamp(edge, previous, length);
            }
        }
        let dt = edge - previous;
        let d = (edge + previous) * 0.5;
        previous = edge;
        let hp = atmHeightAt(h, mu, d);
        let localUp = (up * (ATM_BOTTOM_RADIUS_KM + h) + ray * d) / (ATM_BOTTOM_RADIUS_KM + hp);
        let c = atmMedium(hp);
        var direct = float4(0.0);
        for (var k = 0u; k < 5u; k++) {
            direct += c.scattering[k] * solarPhase[k];
        }
        direct *= atmSunT(hp, dot(localUp, sun));
        direct *= ATM_SOLAR;
        var source = direct;
        // The first iteration has an exactly zero previous volume source.
        if (multiple) {
            source += atmIndirect(hp, dot(localUp, ray), dot(localUp, sun), nu, c);
        }
        let tau = c.extinction * dt;
        let trans = exp(-tau);
        let integral = select(tau < float4(0.001), dt * (float4(1.0) - tau * 0.5 + tau * tau / 6.0),
                              (float4(1.0) - trans) / max(c.extinction, float4(1e-30)));
        result.light += result.transmittance * source * integral;
        result.transmittance *= trans;
    }
    if (hit && length >= fullLength) {
        let end_up = (up * (ATM_BOTTOM_RADIUS_KM + h) + ray * length) / ATM_BOTTOM_RADIUS_KM;
        result.light += result.transmittance * atmGroundLight(dot(end_up, sun));
    }
    return result;
}
