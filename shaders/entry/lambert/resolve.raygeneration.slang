// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/lambert/resolve.raygeneration.slang";
import "contract/lambert.slang";
import "prime_abi.slang";
import glsl;
import "state/lambert.slang";
import "service/reconstruct/lambert_signal.slang";
[shader("raygeneration")]
void main() {
    uint2 pixel = DispatchRaysIndex().xy;
    uint index = pixel.y * primePush.outputExtent.x + pixel.x;
    primeLambertWriteSignal(pixel, primeLambertPaths[index]);
}
