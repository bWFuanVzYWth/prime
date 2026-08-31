package dev.prime.render.vulkan;

/**
 * Lifetime-stable image view written by one resolved wavefront frame.
 *
 * <p>These are transport outputs, not reconstruction inputs. In particular,
 * {@link #transportScratch()} is phase-private raygen and reconstruction scratch.
 * {@link #reconstructionMotion()} is backend-private guide motion and must not be passed to an
 * interop API as visible-surface motion.
 */
public interface RawWavefrontFrame {
    VulkanImage noisyDiffuse();

    VulkanImage noisySpecular();

    VulkanImage normalRoughness();

    VulkanImage viewZ();

    VulkanImage transportScratch();

    VulkanImage reconstructionMotion();

    VulkanImage material();

    VulkanImage specularMaterial();

    VulkanImage reconstructionControl();

    VulkanImage primaryPosition();

    VulkanImage sunLighting();

    VulkanImage sunPenumbra();

    /** Raygen writes these only when {@link #usesShInputs()} is true. */
    default VulkanImage diffuseDirection() {
        return noisyDiffuse();
    }

    default VulkanImage specularDirection() {
        return noisySpecular();
    }

    default VulkanImage reflectionNoisyDiffuse() { return noisyDiffuse(); }

    default VulkanImage reflectionNoisySpecular() { return noisySpecular(); }

    default VulkanImage reflectionNormalRoughness() { return normalRoughness(); }

    default VulkanImage reflectionMaterial() { return material(); }

    default VulkanImage reflectionSpecularMaterial() { return specularMaterial(); }

    default VulkanImage reflectionPosition() { return primaryPosition(); }

    default VulkanImage reflectionDiffuseDirection() { return diffuseDirection(); }

    default VulkanImage reflectionSpecularDirection() { return specularDirection(); }

    default VulkanImage displayPosition() { return primaryPosition(); }

    /** Previous visible position for moving objects, current position otherwise. */
    default VulkanImage visibleHistoryPosition() { return primaryPosition(); }

    /** Whether {@link #visibleHistoryPosition()} is exact for a transmissive visible interface. */
    default boolean hasExactTransmissiveVisibleHistory() { return false; }

    default boolean usesShInputs() {
        return false;
    }

}
