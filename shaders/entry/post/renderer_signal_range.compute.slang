#language slang 2026
module "entry/post/renderer_signal_range.compute.slang";

import "phase/post/renderer_signal_range.slang";

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    primePhase(dispatchThreadId);
}
