#language slang 2026
module "bsdf/compact/model/volume.slang";

import "bsdf/compact/contract/material.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/math/spectrum.slang";

public PrimeOpenPbrVolume primeOpenPbrVolumeFromTransmission(
        PrimeOpenPbrTransmission transmission) {
    PrimeOpenPbrVolume result;
    if (transmission.depth > 0.0) {
        float3 muT = -log(max(float3(1.0e-3), transmission.color))
                / transmission.depth;
        float3 muS = transmission.scatter / transmission.depth;
        float3 muA = muT - muS;
        muA -= float3(min(0.0, primeOpenPbrReduceMin(muA)));
        result.extinction = muA + muS;
        result.albedo = float3(
                result.extinction.x != 0.0 ? muS.x / result.extinction.x : 0.0,
                result.extinction.y != 0.0 ? muS.y / result.extinction.y : 0.0,
                result.extinction.z != 0.0 ? muS.z / result.extinction.z : 0.0);
        result.anisotropy = clamp(transmission.scatterAnisotropy, -0.99, 0.99);
    } else {
        result.extinction = float3(0.0);
        result.albedo = float3(0.0);
        result.anisotropy = 0.0;
    }
    result.ior = 1.0;
    result.mediumId = 0u;
    result.iorEncoding = 0u;
    return result;
}
