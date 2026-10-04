#language slang 2026
module "restir/rr_guides.slang";
import "restir/path.slang";
import "trace/closest.slang";
import "realtime/primary.slang";
import "realtime/primary_parameters.slang";
import "reconstruct/primary_guides.slang";
import "service/bsdf/contract/sample.slang";

public struct RestirRrPrimaryObserver : IRestirPrimaryObserver {
    public TraceScene resources;
    public PrimaryParameters camera;
    public PrimaryRrGuides guides;
    public uint2 pixel;
    public float2 sampleUv;
    public bool ordinary;

    [mutating] public void capturePrimary(SurfaceHit hit, float3 view) {
        ordinary =
                primeTraceGuidesFromPrimary(resources, camera, pixel, sampleUv, -view, hit, guides);
    }[mutating] public void captureFirstScatter(float distance, uint eventFlags) {
        if (ordinary && (eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0) {
            guides.distance[pixel] = isfinite(distance) ? clamp(distance, 0.0, 65504.0) : 0.0;
        }
    }
}
