// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.nrd;

import dev.prime.render.vulkan.VulkanImage;
import java.util.Objects;

/**
 * Images after Prime's raygen-to-NRD preparation pass has completed.
 *
 * <p>The view aliases lifetime-stable storage owned by {@link NrdDenoiser}; its type marks the
 * command-stream point after motion reconstruction, demodulation and guide encoding.
 */
public final class PreparedNrdFrame {
    private final Branch primary;
    private final Branch reflection;
    private final VulkanImage sunPenumbra;
    private final VulkanImage fsrDepth;
    private final VulkanImage fsrMotion;

    PreparedNrdFrame(
            Branch primary,
            Branch reflection,
            VulkanImage sunPenumbra,
            VulkanImage fsrDepth,
            VulkanImage fsrMotion) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.reflection = Objects.requireNonNull(reflection, "reflection");
        this.sunPenumbra = Objects.requireNonNull(sunPenumbra, "sunPenumbra");
        this.fsrDepth = Objects.requireNonNull(fsrDepth, "fsrDepth");
        this.fsrMotion = Objects.requireNonNull(fsrMotion, "fsrMotion");
    }

    VulkanImage resolveInput(int resourceType, int denoiserIdentifier) {
        Branch branch = denoiserIdentifier == 2 ? this.reflection : this.primary;
        return switch (resourceType) {
            case NrdNative.RESOURCE_IN_MV -> branch.motion();
            case NrdNative.RESOURCE_IN_NORMAL_ROUGHNESS -> branch.normalRoughness();
            case NrdNative.RESOURCE_IN_VIEWZ -> branch.viewZ();
            case NrdNative.RESOURCE_IN_DIFF_RADIANCE_HITDIST,
                    NrdNative.RESOURCE_IN_DIFF_SH0 -> branch.noisyDiffuse();
            case NrdNative.RESOURCE_IN_SPEC_RADIANCE_HITDIST,
                    NrdNative.RESOURCE_IN_SPEC_SH0 -> branch.noisySpecular();
            case NrdNative.RESOURCE_IN_DIFF_SH1 -> branch.noisyDiffuseSh1();
            case NrdNative.RESOURCE_IN_SPEC_SH1 -> branch.noisySpecularSh1();
            case NrdNative.RESOURCE_IN_PENUMBRA -> this.sunPenumbra;
            default -> throw new IllegalArgumentException(
                    "Not a prepared NRD input resource: " + resourceType);
        };
    }

    public Branch primary() {
        return this.primary;
    }

    public Branch reflection() {
        return this.reflection;
    }

    public VulkanImage sunPenumbra() {
        return this.sunPenumbra;
    }

    public VulkanImage fsrDepth() {
        return this.fsrDepth;
    }

    public VulkanImage fsrMotion() {
        return this.fsrMotion;
    }

    public record Branch(
            VulkanImage motion,
            VulkanImage normalRoughness,
            VulkanImage viewZ,
            VulkanImage noisyDiffuse,
            VulkanImage noisySpecular,
            VulkanImage noisyDiffuseSh1,
            VulkanImage noisySpecularSh1) {
        public Branch {
            Objects.requireNonNull(motion, "motion");
            Objects.requireNonNull(normalRoughness, "normalRoughness");
            Objects.requireNonNull(viewZ, "viewZ");
            Objects.requireNonNull(noisyDiffuse, "noisyDiffuse");
            Objects.requireNonNull(noisySpecular, "noisySpecular");
            Objects.requireNonNull(noisyDiffuseSh1, "noisyDiffuseSh1");
            Objects.requireNonNull(noisySpecularSh1, "noisySpecularSh1");
        }
    }
}
