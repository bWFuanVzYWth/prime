// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#include "core/prime_test_abi.slang"
import "service/light/cell_sample.slang";
import "service/light/area_common.slang";

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID) {
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;
    uint input = primeTestInputOffset(caseIndex, 4u);
    uint4 cell = primeTestInput[input];
    float4 random = asfloat(primeTestInput[input + 1u]);
    float4 expected = asfloat(primeTestInput[input + 2u]);
    float4 geometry = asfloat(primeTestInput[input + 3u]);
    float2 bary = primeSampleLightCell(cell.x, random.xy);
    uint selected = primeSelectLightCell(random.z, random.w, cell.y);
    uint reverse = primeLightCellIndex(bary);
    uint mask = primeTestClassify(bary);
    if (any(bary < 0.0) || bary.x + bary.y > 1.0) mask |= PRIME_TEST_FAILURE_RANGE;
    if (reverse != cell.z || selected != cell.w) mask |= PRIME_TEST_FAILURE_INDEX;
    if (!primeTestClose(float3(bary, 0.0), float3(expected.xy, 0.0), 2e-7, 2e-6)) {
        mask |= PRIME_TEST_FAILURE_VALUE;
    }
    float3 delta = float3(bary, geometry.x);
    float cosine = geometry.x / length(delta);
    // Unit right triangle has area 1/2, hence every cell has area 1/512.
    float pdf = primeAreaSolidAnglePdf(float3(0.0), delta, cosine, geometry.y * 512.0);
    float scaled = primeAreaSolidAnglePdf(float3(0.0), delta * 4.0, cosine,
            geometry.y * 512.0 / 16.0);
    if (!primeTestClose(pdf, expected.z, 1e-7, 4e-6)
            || !primeTestClose(pdf, scaled, 1e-7, 4e-6)) mask |= PRIME_TEST_FAILURE_PDF;
    uint output = primeTestOutputOffset(caseIndex, primeTestOutputWords());
    primeTestOutput[output] = uint4(mask, caseIndex, reverse, selected);
    if (primeTestWitnessEnabled()) {
        primeTestOutput[output + 1u] = asuint(float4(bary, pdf, scaled));
    }
}
