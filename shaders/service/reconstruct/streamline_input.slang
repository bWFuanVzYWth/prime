#language slang 2026
module "service/reconstruct/streamline_input.slang";

import "service/reconstruct/fsr_input.slang";

public float primeStreamlineReversedDepth(float viewZ) {
    return primeFsrReversedInfiniteDepth(viewZ);
}

public float2 primeStreamlineTopLeftMotion(float2 sourceMotion) {
    return float2(sourceMotion.x, -sourceMotion.y);
}
