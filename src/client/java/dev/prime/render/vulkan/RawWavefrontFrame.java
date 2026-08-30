package dev.prime.render.vulkan;

/**
 * Lifetime-stable image view written by one resolved wavefront frame.
 *
 * <p>These are transport outputs, not reconstruction inputs. In particular,
 * {@link #transportMetadata()} is private raygen scratch; a reconstruction preparation stage may
 * alias and overwrite it with a backend-specific motion vector only after ray tracing completes.
 */
public interface RawWavefrontFrame {
    VulkanImage noisyDiffuse();

    VulkanImage noisySpecular();

    VulkanImage normalRoughness();

    VulkanImage viewZ();

    VulkanImage transportMetadata();

    VulkanImage material();

    VulkanImage specularMaterial();

    VulkanImage materialClass();

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

    default boolean usesShInputs() {
        return false;
    }

}
