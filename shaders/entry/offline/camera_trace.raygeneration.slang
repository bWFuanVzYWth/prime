// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/offline/camera_trace.raygeneration.slang";

import "transport/offline/trace.slang";

[shader("raygeneration")]
void main() {
    primeTraceOfflineCamera(DispatchRaysIndex().xy);
}
