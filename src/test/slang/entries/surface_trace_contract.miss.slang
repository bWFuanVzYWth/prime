// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

import "prime_abi_types.slang";
import "contract/path.slang";
[shader("miss")]
void main(inout TracePayload payload) { payload.hitKind = PRIME_HIT_NONE; }
