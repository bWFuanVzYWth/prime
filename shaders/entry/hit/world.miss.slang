// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/hit/world.miss.slang";

import glsl;
import "prime_abi_types.slang";
import "contract/path.slang";

layout(location = 0) rayPayloadInEXT TracePayload primePayload;

[shader("miss")]
void main()
{
    primePayload.hitKind = PRIME_HIT_NONE;
}
