// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/light_receiver.slang";

import "contract/light_receiver.slang";
import "contract/path.slang";
import "state/surface_storage.slang";

// Realtime and offline share the surface core. A miss publishes only its hit word; no other
// word may be read before that test. Hit loads exclude color, opacity, motion and texture LOD.
public PrimeLightReceiver primeLoadLightReceiver(RWStructuredBuffer<uint> words, uint base) {
    PrimeLightReceiver receiver = {};
    uint identity = words[base + 4u];
    receiver.hitKind = identity & PRIME_SURFACE_STORAGE_HIT_MASK;
    if (receiver.hitKind == PRIME_HIT_NONE) return receiver;
    receiver.classification.materialControl = (identity >> 8u) & PRIME_SURFACE_STORAGE_MATERIAL_MASK;
    receiver.classification.roughness = asfloat(words[base + 11u]);
    uint optical = words[base + 12u];
    receiver.classification.opticalControl = optical & PRIME_SURFACE_STORAGE_OPTICAL_MASK;
    receiver.classification.adjacentInterfaceControl = (words[base + 23u] >> 16u)
            | ((optical >> 25u) & 0x3u) << 16u;
    receiver.position = asfloat(uint3(words[base + 19u], words[base + 20u], words[base + 21u]));
    receiver.geometricNormal = asfloat(uint3(words[base + 1u], words[base + 2u], words[base + 3u]));
    receiver.shadingNormal = asfloat(uint3(words[base + 13u], words[base + 14u], words[base + 15u]));
    return receiver;
}
