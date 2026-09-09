// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.scene.vanilla;

/** Stable inclusion rules for content submitted by Minecraft's world renderer. */
public final class VanillaSceneBoundary {
    private VanillaSceneBoundary() {
    }

    public static boolean includes(
            Element element, boolean localPlayer, boolean firstPersonCamera) {
        return switch (element) {
            case ENTITY -> !localPlayer || !firstPersonCamera;
            case BLOCK_ENTITY, PARTICLE, FEATURE, WEATHER -> true;
            case FIRST_PERSON_HAND, FIRST_PERSON_ITEM, SCREEN_OVERLAY -> false;
        };
    }

    /** Prime-owned terrain has no vanilla compiled section to authorize an already-visible entity. */
    public static boolean includesEntitySection(
            boolean replacingWorld, boolean vanillaSectionVisible) {
        return replacingWorld || vanillaSectionVisible;
    }

    public enum Element {
        ENTITY,
        BLOCK_ENTITY,
        PARTICLE,
        FEATURE,
        WEATHER,
        FIRST_PERSON_HAND,
        FIRST_PERSON_ITEM,
        SCREEN_OVERLAY
    }
}
