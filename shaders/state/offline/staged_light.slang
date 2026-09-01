#language slang 2026
module "state/offline/staged_light.slang";

import "prime_abi_types.slang";
import "prime_offline_abi.slang";
import "contract/light_sample.slang";
import "service/light/emission.slang";
import "state/offline/contract.slang";
import "state/offline/index.slang";
import "state/offline/surface.slang";

public struct PrimeOfflineStagedLightSelection {
    public AreaLightSelection area;
};

public static const uint PRIME_OFFLINE_STAGED_LIGHT_WORDS =
        PRIME_OFFLINE_STAGED_LIGHT_RECORD_SIZE / 4u;

public PrimeOfflineStagedLightSelection primeDisabledOfflineStagedLightSelection() {
    PrimeOfflineStagedLightSelection selection;
    selection.area.sectionIndex = PRIME_NO_LIGHT_INDEX;
    selection.area.emitterIndex = PRIME_NO_LIGHT_INDEX;
    selection.area.pdf = -1.0;
    return selection;
}

public bool primeOfflineStagedDirectEnabled(
        PrimeOfflineStagedLightSelection selection) {
    return selection.area.pdf >= 0.0;
}

public uint primeOfflineStagedLightWord(uint pathIndex) {
    uint surfaceWords = primeOfflinePixelCount() * PRIME_OFFLINE_SURFACE_WORDS;
    return surfaceWords + pathIndex * PRIME_OFFLINE_STAGED_LIGHT_WORDS;
}

public void primeStoreOfflineStagedLightSelection(
        uint pathIndex, PrimeOfflineStagedLightSelection selection) {
    uint word = primeOfflineStagedLightWord(pathIndex);
    primeOfflineQueue[word + 0u] = selection.area.sectionIndex;
    primeOfflineQueue[word + 1u] = selection.area.emitterIndex;
    primeOfflineQueue[word + 2u] = asuint(selection.area.pdf);
}

public PrimeOfflineStagedLightSelection primeLoadOfflineStagedLightSelection(
        uint pathIndex) {
    uint word = primeOfflineStagedLightWord(pathIndex);
    PrimeOfflineStagedLightSelection selection;
    selection.area.sectionIndex = primeOfflineQueue[word + 0u];
    selection.area.emitterIndex = primeOfflineQueue[word + 1u];
    selection.area.pdf = asfloat(primeOfflineQueue[word + 2u]);
    return selection;
}
