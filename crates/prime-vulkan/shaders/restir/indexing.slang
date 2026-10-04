// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module "restir/indexing.slang";
import "restir/rng.slang";

public uint restirOffset(uint2 pixel, uint2 extent) {
    uint2 tile = pixel >> 4;
    return (tile.y * ((extent.x + 15u) >> 4) + tile.x) * 256u + (interleave_32bit(pixel) & 255u);
}
public bool restirInBounds(int2 pixel, uint2 extent) {
    return all(pixel >= 0) && all(pixel < int2(extent));
}
