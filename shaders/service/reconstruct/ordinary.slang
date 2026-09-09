// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/reconstruct/ordinary.slang";

import "contract/realtime/ordinary.slang";
import "contract/realtime/radiance.slang";
import "model/realtime/guide.slang";

public PrimeOrdinarySample primeEmptyOrdinarySample() {
    PrimeOrdinarySample result;
    result.radiance = primeEmptyPathRadiance();
    result.guides = primeEmptyDenoiserGuides();
    return result;
}
