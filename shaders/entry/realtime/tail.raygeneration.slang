#language slang 2026
module "entry/realtime/tail.raygeneration.slang";

import "service/trace/context.slang";
import "transport/realtime/tail.slang";

struct PrimeRealtimeTailRecord {
    uint control;
};

[[vk::shader_record]]
ConstantBuffer<PrimeRealtimeTailRecord> primeRealtimeTailRecord;

[shader("raygeneration")]
void main() {
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeRunRealtimeTail(
            context,
            primeRealtimeTailRecord.control,
            launchIndex);
}
