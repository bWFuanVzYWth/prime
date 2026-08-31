#language slang 2026
module "service/material/medium_access.slang";

import "prime_material_core_abi.slang";
import "prime_abi_types.slang";
import "math/packed_geometry.slang";
import "service/material/access.slang";

public uint2 primeMaterialCoreWords(uint materialId) {
    return primeMaterialCoreRecords.Load2(materialId * PRIME_MATERIAL_CORE_RECORD_SIZE);
}

public uint primeMaterialMediumId(uint materialId) {
    return primeMaterialCoreRecords.Load(
            materialId * PRIME_MATERIAL_CORE_RECORD_SIZE
                    + PRIME_MATERIAL_CORE_MEDIUM_ID_OFFSET)
            & PRIME_MATERIAL_CORE_MEDIUM_ID_MASK;
}

public uint primePrimitiveMediumId(PrimitiveRecord primitive) {
    uint materialId = primePrimitiveMaterialId(primitive);
    return materialId == 0u
            ? primeMediumId(primitive.identity)
            : primeMaterialMediumId(materialId);
}
