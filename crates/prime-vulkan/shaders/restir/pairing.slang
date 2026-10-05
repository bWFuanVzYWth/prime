// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module "restir/pairing.slang";
import "restir/parameters.slang";
import "restir/paired_neighbors.slang";
import "restir/rng.slang";
import "restir/config.slang";

public PairedNeighborTransform restirPairing(RestirParameters params, uint sampleOffset,
                                             uint spatialRound = 0u) {
    // Original defaults reserve the second temporal stream even in the point profile.
    SampleGenerator shared = TinyUniformSampleGenerator(
            params.frame.dimensions.xy + 1u,
            restirStreamCount(params.config) *
                            (params.frame.target.y + sampleOffset + params.frame.target.w) +
                    restirSpatialStream(params.config, spatialRound));
    return PairedNeighborTransform(shared, params.frame.dimensions.x, params.frame.dimensions.y);
}
public int2 restirNeighbor(RestirParameters params, PairedNeighborTransform pairing, uint2 pixel,
                           uint neighbor) {
    int dx = 0, dy = 0;
    pairing.readTransformedDelta(params.pairedDeltas, params.config.queue.z,
                                 restirPairSize(params.config, neighbor), neighbor, pixel.x,
                                 pixel.y, dx, dy);
    return int2(pixel) + int2(dx, dy);
}
