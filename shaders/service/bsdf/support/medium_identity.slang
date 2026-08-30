#language slang 2026
module "service/bsdf/support/medium_identity.slang";

import "model/material/defaults.slang";
import "bsdf/compact/math/spectrum.slang";

public static const uint PRIME_MEDIUM_ID_VACUUM = 0u;
// Renderer upload permanently reserves the first nonzero identity for camera and world water.
public static const uint PRIME_MEDIUM_ID_WATER = 1u;
public static const uint PRIME_MEDIUM_IOR_CODE_MASK = 0xffu;
public static const uint PRIME_MEDIUM_IOR_WATER = 1u << 8u;

public uint primeMediumIorEncoding(uint materialControl, uint opticalControl) {
    return primeMaterialIsWater(materialControl)
            ? PRIME_MEDIUM_IOR_WATER
            : opticalControl & PRIME_OPTICAL_FRESNEL_MASK;
}

public float primeDecodeMediumIor(uint encoding) {
    if ((encoding & PRIME_MEDIUM_IOR_WATER) != 0u) {
        return 1.333;
    }
    uint fresnelCode = encoding & PRIME_MEDIUM_IOR_CODE_MASK;
    float dielectricF0 = fresnelCode <= 230u
            ? primeDecodeDielectricF0(fresnelCode)
            : PRIME_DEFAULT_DIELECTRIC_F0;
    return primeOpenPbrF0ToIor(dielectricF0);
}
