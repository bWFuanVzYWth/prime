// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/surface/accumulate.slang";

import "service/trace/context.slang";
import "contract/realtime/ordinary.slang";
import "contract/realtime/transparent.slang";

public void primeAccumulate(
        inout PrimeIntegratorContext context,
        inout float3 accumulator,
        float3 contribution) {
    accumulator += contribution;
}

public void primeAccumulateAfterPrimary(
        inout PrimeIntegratorContext context,
        inout PrimeOrdinarySample result,
        bool diffusePath,
        float3 contribution) {
    if (diffusePath) {
        primeAccumulate(context, result.radiance.diffuse, contribution);
    } else {
        primeAccumulate(context, result.radiance.specular, contribution);
    }
}

public void primeAccumulateTransparentBranch(
        inout PrimeIntegratorContext context,
        inout PrimeTransparentBranchResult result,
        bool diffusePath,
        float3 contribution) {
    if (diffusePath) {
        primeAccumulate(context, result.diffuseRadiance, contribution);
    } else {
        primeAccumulate(context, result.specularRadiance, contribution);
    }
}
