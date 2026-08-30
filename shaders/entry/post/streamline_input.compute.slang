#language slang 2026
module "entry/post/streamline_input.compute.slang";

import "phase/post/streamline_input.slang";

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    primePhase(dispatchThreadId);
}
