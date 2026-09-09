// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
import "contract/lambert.slang";
[shader("miss")]
#if defined(PRIME_TEST_SER)
void main(inout PrimeLambertPayload payload) { payload.flags = 99u; }
#else
void main(inout uint payload) { payload = 99u; }
#endif
