#language slang 2026
module "entry/post/ui_alpha_extract.compute.slang";

import "phase/post/ui_alpha_extract.slang";

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    primePhase(dispatchThreadId);
}
