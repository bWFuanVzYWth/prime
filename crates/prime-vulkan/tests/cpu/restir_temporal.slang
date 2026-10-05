// CPU-only mathematical oracle. No shader catalog entry or production pipeline consumes this.
// Three discrete paths with an analytic integral stress actual ReSTIR reservoir math and RNG.
#language slang 2026
module restir_temporal_math_test;
import "restir/rng.slang";
import "restir/reservoir.slang";
import "restir/pairwise.slang";
import "restir/paired_neighbors.slang";

StructuredBuffer<uint> pairedDeltas;
RWStructuredBuffer<float4> results;
ConstantBuffer<uint4> config; // mode, independent chains, frames, independent seed offset

static const uint WIDTH = 32;
static const uint HEIGHT = 16;
static const uint PIXELS = WIDTH * HEIGHT;

float3 integrand(uint pixel, uint path) {
    // Receiver-dependent integrands with full support; mapping has unit Jacobian.
    float x = float(pixel % WIDTH) / float(WIDTH - 1);
    float y = float(pixel / WIDTH) / float(HEIGHT - 1);
    float3 base = path == 0   ? float3(.25, .5, 1)
                  : path == 1 ? float3(1.5, .75, .3)
                              : float3(32, 16, 8);
    float gain = path == 0 ? .5 + x : path == 1 ? .75 + .5 * y : 1.25 - .5 * x;
    return base * gain;
}
uint pathIndex(PathReservoir reservoir) {
    return reservoir.initRandomSeed & 3u;
}
PathReservoir initial(uint pixel, uint sequence) {
    SampleGenerator sg = SampleGenerator(uint2(pixel % WIDTH, pixel / WIDTH), 4u * sequence);
    float u = sampleNext1D(sg);
    uint path = u < .8 ? 0u : u < .99 ? 1u : 2u;
    float probability = path == 0 ? .8 : path == 1 ? .19 : .01;
    PathReservoir result = {};
    result.M = 1;
    result.integrand = integrand(pixel, path);
    result.weight = 1.0 / probability;
    // Test lineage identity stores a unique source frame/pixel and the discrete path index.
    result.initRandomSeed = (sequence << 11u) | (pixel << 2u) | path;
    return result;
}
PathReservoir temporal(uint pixel, uint sequence, PathReservoir canonical, PathReservoir history) {
    history.M = min(20.0, history.M);
    float canonicalHat = PathRISState::toScalar(canonical.integrand);
    float historyHat = PathRISState::toScalar(history.integrand);
    float canonicalWeight =
            temporalCanonicalWeight(canonical.M, history.M, canonicalHat, canonicalHat);
    float historyWeight = temporalNeighborWeight(canonical.M, history.M, historyHat, historyHat);
    SampleGenerator sg = SampleGenerator(uint2(pixel % WIDTH, pixel / WIDTH), 4u * sequence + 1u);
    PathReservoir result;
    result.init();
    result.merge(history.integrand, 1.0, history, sg, historyWeight);
    result.merge(canonical.integrand, 1.0, canonical, sg, canonicalWeight);
    result.finalizeReservoirWeight();
    return result;
}

[shader("compute")][numthreads(1, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= config.y)
        return;
    PathReservoir history[PIXELS], current[PIXELS], next[PIXELS];
    float3 sum = 0;
    float3 target = 0;
    float frameEnergySquared = 0;
    float covariance = 0, leftVariance = 0, rightVariance = 0;
    float temporalCovariance = 0, currentVariance = 0, previousVariance = 0;
    uint temporalRetained = 0;
    uint retainedEdges = 0, edges = 0;
    uint retainedFrames = 0;
    for (uint pixel = 0; pixel < PIXELS; ++pixel) {
        history[pixel].init();
        target += integrand(pixel, 0) + integrand(pixel, 1) + integrand(pixel, 2);
    }
    target /= float(PIXELS);
    for (uint frame = 0; frame < config.z; ++frame) {
        uint sequence = config.w + id.x * 1000003u + frame;
        for (uint pixel = 0; pixel < PIXELS; ++pixel) {
            current[pixel] = initial(pixel, sequence);
            if (config.x != 0u && frame != 0u)
                current[pixel] = temporal(pixel, sequence, current[pixel], history[pixel]);
        }
        SampleGenerator shared = SampleGenerator(uint2(WIDTH, HEIGHT) + 1u, 4u * sequence + 3u);
        PairedNeighborTransform pairing = PairedNeighborTransform(shared, WIDTH, HEIGHT);
        float3 frameSum = 0;
        for (uint pixel = 0; pixel < PIXELS; ++pixel) {
            PathReservoir canonical = current[pixel];
            next[pixel] = canonical;
            if (config.x == 2u) {
                uint neighbors[3];
                uint mask = 0;
                PairwiseFactor factor = {canonical.M, canonical.M};
                for (uint i = 0; i < 3u; ++i) {
                    int dx = 0, dy = 0;
                    pairing.readTransformedDelta(pairedDeltas, i, pixel % WIDTH, pixel / WIDTH, dx,
                                                 dy);
                    int2 neighbor = int2(pixel % WIDTH, pixel / WIDTH) + int2(dx, dy);
                    if (any(neighbor < 0) || neighbor.x >= WIDTH || neighbor.y >= HEIGHT)
                        continue;
                    neighbors[i] = uint(neighbor.y) * WIDTH + uint(neighbor.x);
                    mask |= 1u << i;
                    factor.c_sum += current[neighbors[i]].M;
                }
                PathReservoir merged;
                merged.init();
                float canonicalWeight = spatialCanonicalInitialWeight(factor);
                SampleGenerator sg =
                        SampleGenerator(uint2(pixel % WIDTH, pixel / WIDTH), 4u * sequence + 3u);
                sampleNext1D(sg); // Original unused unpaired-neighbor starting index.
                for (uint i = 0; i < 3u; ++i) {
                    if ((mask & (1u << i)) == 0u)
                        continue;
                    PathReservoir source = current[neighbors[i]];
                    float3 forward = integrand(pixel, pathIndex(source));
                    float3 inverse = integrand(neighbors[i], pathIndex(canonical));
                    canonicalWeight += spatialCanonicalWeightTerm(
                            factor, source.M, PathRISState::toScalar(canonical.integrand),
                            PathRISState::toScalar(inverse));
                    float neighborWeight = spatialNeighborWeight(
                            factor, source.M, PathRISState::toScalar(source.integrand),
                            PathRISState::toScalar(forward));
                    merged.merge(forward, 1.0, source, sg, neighborWeight);
                }
                merged.merge(canonical.integrand, 1.0, canonical, sg, canonicalWeight);
                merged.finalizeReservoirWeight();
                next[pixel] = merged;
            }
            frameSum += next[pixel].integrand * next[pixel].weight;
        }
        if (frame >= config.z / 2u) {
            ++retainedFrames;
            float3 frameMean = frameSum / float(PIXELS);
            sum += frameMean;
            frameEnergySquared += frameMean.x * frameMean.x;
            for (uint pixel = 0; pixel < PIXELS; ++pixel) {
                float expected =
                        (integrand(pixel, 0) + integrand(pixel, 1) + integrand(pixel, 2)).x;
                float present = (next[pixel].integrand * next[pixel].weight).x - expected;
                float previous = (history[pixel].integrand * history[pixel].weight).x - expected;
                temporalCovariance += present * previous;
                currentVariance += present * present;
                previousVariance += previous * previous;
                temporalRetained +=
                        uint(next[pixel].initRandomSeed == history[pixel].initRandomSeed);
                if (pixel % WIDTH == WIDTH - 1u)
                    continue;
                ++edges;
                retainedEdges +=
                        uint(next[pixel].initRandomSeed == next[pixel + 1u].initRandomSeed);
                float left = (next[pixel].integrand * next[pixel].weight).x -
                             (integrand(pixel, 0) + integrand(pixel, 1) + integrand(pixel, 2)).x;
                float right = (next[pixel + 1u].integrand * next[pixel + 1u].weight).x -
                              (integrand(pixel + 1u, 0) + integrand(pixel + 1u, 1) +
                               integrand(pixel + 1u, 2))
                                      .x;
                covariance += left * right;
                leftVariance += left * left;
                rightVariance += right * right;
            }
        }
        for (uint pixel = 0; pixel < PIXELS; ++pixel)
            history[pixel] = next[pixel];
    }
    results[5u * id.x] =
            float4(sum / float(retainedFrames), float(retainedEdges) / float(max(1u, edges)));
    results[5u * id.x + 1u] = float4(target, frameEnergySquared / float(retainedFrames));
    results[5u * id.x + 2u] = float4(history[0].M, history[PIXELS / 2u].M, history[PIXELS - 1u].M,
                                     float(retainedFrames));
    results[5u * id.x + 3u] =
            float4(covariance, leftVariance, rightVariance, float(edges)) / float(max(1u, edges));
    results[5u * id.x + 4u] =
            float4(temporalCovariance, currentVariance, previousVariance, float(temporalRetained)) /
            float(PIXELS * retainedFrames);
}
