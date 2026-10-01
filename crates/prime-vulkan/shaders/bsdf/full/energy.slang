// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/energy.slang";

import "bsdf/full/math/microfacet_energy.slang";

// Immutable author-supplied 44x32x159 HALF4 energy table, uploaded once into
// device-local image memory. Normalized linear clamp filtering matches legacy.

public struct PrimeFullTransmissionGgxEnergy : IPrimeFullOpenPbrTransmissionGgxEnergy {
    public Sampler3D<float4> texture;
    public float4 sampleLevel(float3 uvw) {
        return texture.SampleLevel(uvw, 0.0);
    }
};
