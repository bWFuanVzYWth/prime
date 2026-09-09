// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/trace/surface_records.slang";

import "prime_abi_types.slang";
import "prime_surface_abi.slang";

[[vk::binding(PRIME_SURFACE_RECORDS_BINDING, 0)]]
public StructuredBuffer<PrimitiveRecord> primeSurfaceRecords;
