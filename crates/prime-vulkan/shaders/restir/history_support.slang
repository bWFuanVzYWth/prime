// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module "restir/history_support.slang";
import "restir/reservoir.slang";

public bool restirPathFitsBudget(ReSTIRPathFlags path, uint budget) {
    // NEE stores its receiver index. A BSDF-hit/escape stores the preceding
    // vertex, so its endpoint needs one further trace within the new budget.
    return uint(path.pathLength()) + uint(!path.lastVertexNEE()) < budget;
}
