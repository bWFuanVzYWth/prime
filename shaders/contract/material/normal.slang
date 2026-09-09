// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "contract/material/normal.slang";

public static const uint PRIME_OPTICAL_NORMAL_MAPPED = 1u << 24u;

public bool primeMaterialUsesNormalMap(uint opticalControl)
{
    return (opticalControl & PRIME_OPTICAL_NORMAL_MAPPED) != 0u;
}
