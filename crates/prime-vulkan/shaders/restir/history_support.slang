// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module "restir/history_support.slang";
import "restir/reservoir.slang";

public bool restirPathFitsBudget(ReSTIRPathFlags path, uint budget) {
    // RA-009, docs/restir-adaptations.md: apply in both directions when budgets differ.
    // NEE stores its receiver index. A BSDF-hit/escape stores the preceding
    // vertex, so its endpoint needs one further trace within the new budget.
    return uint(path.pathLength()) + uint(!path.lastVertexNEE()) < budget;
}
public bool restirMediumIsVacuum(float4 medium) {
    return medium.x == 1.0 && all(medium.yzw == 0.0);
}
public bool restirSuffixNeedsRefresh(ReSTIRPathFlags source, bool fixedThickBoundary,
                                     float4 destinationOutgoing) {
    // RA-011: a static thick RC and fixed source event determine its outgoing medium.
    // Opaque/thin RCs inherit transport medium; without its full source value only
    // the vacuum-to-vacuum case proves cached Beer/IOR valid. Refresh locally otherwise.
    return !fixedThickBoundary &&
           (source.suffixNonVacuum() || !restirMediumIsVacuum(destinationOutgoing));
}
