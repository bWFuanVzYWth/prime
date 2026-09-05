#language slang 2026
module "entry/lambert/camera.raygeneration.slang";
import "prime_abi.slang";
import "contract/lambert.slang";
import glsl;
import "state/lambert.slang";
import "service/camera/projection.slang";
[shader("raygeneration")]
void main() {
    uint2 pixel = DispatchRaysIndex().xy;
    uint index = pixel.y * primePush.outputExtent.x + pixel.x;
    PrimeLambertRecord path = {};
    path.origin = float4(primePush.cameraPosition, 0.0);
    path.direction = float4(primeCameraRayDirection(pixel, primeRealtimeCameraSample(pixel)), 0.0);
    path.throughput = float4(1.0, 1.0, 1.0, 0.0);
    path.identity.xy = uint2(0xffffffffu);
    path.radiance.w = 65504.0;
    primeLambertPaths[index] = path;
    primeLambertQueue[primeLambertQueueWord(0u, index)] = index;
    if (index == 0u) primeLambertQueue[0] = primeLambertPixelCount();
}
