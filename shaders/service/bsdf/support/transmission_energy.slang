// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/support/transmission_energy.slang";

import "prime_abi_bindings.slang";
import "bsdf/compact/math/microfacet_energy.slang";

[[vk::binding(PRIME_TRANSMISSION_GGX_ENERGY_BINDING, 0)]]
Sampler3D<float4> primeTransmissionGgxEnergyTexture;

public struct PrimeTransmissionGgxEnergy : IPrimeOpenPbrTransmissionGgxEnergy {
    public float4 sampleLevel(float3 uvw) {
        return primeTransmissionGgxEnergyTexture.SampleLevel(uvw, 0.0);
    }
};

public PrimeTransmissionGgxEnergy primeTransmissionGgxEnergy() {
    return PrimeTransmissionGgxEnergy();
}

public float4 primeLoadTransmissionGgxEnergyTexel(uint3 coordinate) {
    return primeTransmissionGgxEnergyTexture.Load(int4(coordinate, 0));
}
