// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_LICENSES/SKY-TRACER-NOTICE.md.
#language slang 2026
module "model/atmosphere/sky_rows.slang";
import "model/atmosphere/sky.slang";
import "model/atmosphere/sky_view.slang";
public float atmSkyElevation(float u, AtmSkyChart c) {
    if (u <= 0.0)
        return c.bounds.x;
    if (u >= 1.0)
        return c.bounds.y;
    float lo = c.bounds.x, hi = c.bounds.y;
    for (uint i = 0; i < 28; i++) {
        float mid = (lo + hi) * 0.5;
        if (atmSkyElevationCoordinate(mid, c) < u)
            lo = mid;
        else
            hi = mid;
    }
    return (lo + hi) * 0.5;
}
public uint3 atmSkyChartRows(float h, uint row) {
    uint lower = h >= ATM_THICKNESS_KM ? 192u : 96u;
    if (row >= 192u)
        return uint3(2, 192, 64);
    if (row >= lower)
        return uint3(1, lower, 192u - lower);
    return uint3(0, 0, lower);
}
public float atmSkyRowCosine(float h, float sunElevation, uint row) {
    uint3 layout = atmSkyChartRows(h, row);
    AtmSkyChart c = atmSkyChart(h, sunElevation, layout.x);
    float u = float(row - layout.y) / float(layout.z - 1);
    return sin(atmSkyElevation(u, c));
}
