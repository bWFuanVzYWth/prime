// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.nrd;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.vulkan.RawWavefrontFrame;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.vulkan.VK12;

/** Owns every persistent and transient image used by one NRD instance. */
final class NrdImages implements RawWavefrontFrame, Destroyable {
    private static final int IMAGE_USAGE =
            VK12.VK_IMAGE_USAGE_STORAGE_BIT
                    | VK12.VK_IMAGE_USAGE_SAMPLED_BIT
                    | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

    private final VulkanImage[] images;
    final VulkanImage[] permanentPool;
    final VulkanImage[] transientPool;
    private final VulkanImage[] ownedImages;
    private boolean destroyed;

    private NrdImages(
            VulkanImage[] images,
            VulkanImage[] permanentPool,
            VulkanImage[] transientPool,
            VulkanImage[] ownedImages) {
        this.images = images;
        this.permanentPool = permanentPool;
        this.transientPool = transientPool;
        this.ownedImages = ownedImages;
    }

    static NrdImages create(
            VulkanContext context,
            int width,
            int height,
            NrdNative.Description description,
            String debugPrefix) {
        ArrayList<VulkanImage> created = new ArrayList<>();
        try {
            Role[] roles = Role.values();
            VulkanImage[] images = new VulkanImage[roles.length];
            for (Role role : roles) {
                images[role.ordinal()] = createImage(
                        context, created, width, height, role.format, debugPrefix + role.label);
            }
            VulkanImage[] permanent = createPool(
                    context,
                    created,
                    width,
                    height,
                    description.permanentPool(),
                    debugPrefix + " permanent");
            VulkanImage[] transientImages = createPool(
                    context,
                    created,
                    width,
                    height,
                    description.transientPool(),
                    debugPrefix + " transient");
            return new NrdImages(
                    images,
                    permanent,
                    transientImages,
                    created.toArray(VulkanImage[]::new));
        } catch (RuntimeException exception) {
            for (int index = created.size() - 1; index >= 0; index--) {
                created.get(index).destroy();
            }
            throw exception;
        }
    }

    VulkanImage get(Role role) {
        return this.images[role.ordinal()];
    }

    @Override public VulkanImage noisyDiffuse() { return get(Role.NOISY_DIFFUSE); }
    @Override public VulkanImage noisySpecular() { return get(Role.NOISY_SPECULAR); }
    VulkanImage noisyDiffuseSh1() { return get(Role.NOISY_DIFFUSE_SH1); }
    VulkanImage noisySpecularSh1() { return get(Role.NOISY_SPECULAR_SH1); }
    @Override public VulkanImage diffuseDirection() { return noisyDiffuseSh1(); }
    @Override public VulkanImage specularDirection() { return noisySpecularSh1(); }
    @Override public VulkanImage normalRoughness() { return get(Role.NORMAL_ROUGHNESS); }
    @Override public VulkanImage viewZ() { return get(Role.VIEW_Z); }
    VulkanImage motion() { return get(Role.MOTION); }
    VulkanImage fsrMotion() { return get(Role.FSR_MOTION); }
    @Override public VulkanImage transportScratch() { return fsrMotion(); }
    @Override public VulkanImage reconstructionMotion() { return fsrMotion(); }
    VulkanImage fsrDepth() { return get(Role.FSR_DEPTH); }
    @Override public VulkanImage material() { return get(Role.MATERIAL); }
    @Override public VulkanImage specularMaterial() { return get(Role.SPECULAR_MATERIAL); }
    @Override public VulkanImage reconstructionControl() { return get(Role.RECONSTRUCTION_CONTROL); }
    @Override public VulkanImage primaryPosition() { return get(Role.PRIMARY_POSITION); }
    @Override public VulkanImage sunLighting() { return get(Role.SUN_LIGHTING); }
    @Override public VulkanImage sunPenumbra() { return get(Role.SUN_PENUMBRA); }
    VulkanImage sunShadow() { return get(Role.SUN_SHADOW); }
    VulkanImage denoisedDiffuse() { return get(Role.DENOISED_DIFFUSE); }
    VulkanImage denoisedSpecular() { return get(Role.DENOISED_SPECULAR); }
    VulkanImage denoisedDiffuseSh1() { return get(Role.DENOISED_DIFFUSE_SH1); }
    VulkanImage denoisedSpecularSh1() { return get(Role.DENOISED_SPECULAR_SH1); }
    @Override public VulkanImage reflectionNoisyDiffuse() {
        return get(Role.REFLECTION_NOISY_DIFFUSE);
    }
    @Override public VulkanImage reflectionNoisySpecular() {
        return get(Role.REFLECTION_NOISY_SPECULAR);
    }
    VulkanImage reflectionNoisyDiffuseSh1() { return get(Role.REFLECTION_NOISY_DIFFUSE_SH1); }
    VulkanImage reflectionNoisySpecularSh1() { return get(Role.REFLECTION_NOISY_SPECULAR_SH1); }
    @Override public VulkanImage reflectionNormalRoughness() {
        return get(Role.REFLECTION_NORMAL_ROUGHNESS);
    }
    VulkanImage reflectionViewZ() { return get(Role.REFLECTION_VIEW_Z); }
    VulkanImage reflectionMotion() { return get(Role.REFLECTION_MOTION); }
    @Override public VulkanImage reflectionMaterial() { return get(Role.REFLECTION_MATERIAL); }
    @Override public VulkanImage reflectionSpecularMaterial() {
        return get(Role.REFLECTION_SPECULAR_MATERIAL);
    }
    @Override public VulkanImage reflectionPosition() { return get(Role.REFLECTION_POSITION); }
    @Override public VulkanImage reflectionDiffuseDirection() {
        return reflectionNoisyDiffuseSh1();
    }
    @Override public VulkanImage reflectionSpecularDirection() {
        return reflectionNoisySpecularSh1();
    }
    VulkanImage reflectionDenoisedDiffuse() { return get(Role.REFLECTION_DENOISED_DIFFUSE); }
    VulkanImage reflectionDenoisedSpecular() { return get(Role.REFLECTION_DENOISED_SPECULAR); }
    VulkanImage reflectionDenoisedDiffuseSh1() { return get(Role.REFLECTION_DENOISED_DIFFUSE_SH1); }
    VulkanImage reflectionDenoisedSpecularSh1() { return get(Role.REFLECTION_DENOISED_SPECULAR_SH1); }
    @Override public VulkanImage displayPosition() { return get(Role.DISPLAY_POSITION); }
    @Override public VulkanImage visibleHistoryPosition() { return displayPosition(); }
    @Override public boolean hasExactTransmissiveVisibleHistory() { return true; }
    @Override public boolean usesShInputs() { return true; }
    VulkanImage fsrReactiveMask() { return get(Role.FSR_REACTIVE_MASK); }
    VulkanImage fsrTransparencyCompositionMask() {
        return get(Role.FSR_TRANSPARENCY_COMPOSITION_MASK);
    }

    VulkanImage[] allImages() {
        return this.ownedImages;
    }

    private static VulkanImage[] createPool(
            VulkanContext context,
            List<VulkanImage> created,
            int width,
            int height,
            List<NrdNative.TextureInfo> descriptions,
            String poolName) {
        VulkanImage[] pool = new VulkanImage[descriptions.size()];
        for (int index = 0; index < pool.length; index++) {
            NrdNative.TextureInfo texture = descriptions.get(index);
            int factor = texture.downsampleFactor();
            if (factor <= 0) {
                throw new IllegalStateException("NRD returned a non-positive downsample factor");
            }
            int textureWidth = (width + factor - 1) / factor;
            int textureHeight = (height + factor - 1) / factor;
            pool[index] = createImage(
                    context,
                    created,
                    textureWidth,
                    textureHeight,
                    vkFormat(texture.format()),
                    "Prime NRD " + poolName + " " + index);
        }
        return pool;
    }

    private static VulkanImage createImage(
            VulkanContext context,
            List<VulkanImage> created,
            int width,
            int height,
            int format,
            String label) {
        VulkanImage image = context.createImage2D(width, height, format, IMAGE_USAGE, label);
        created.add(image);
        return image;
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        for (int index = this.ownedImages.length - 1; index >= 0; index--) {
            this.ownedImages[index].destroy();
        }
    }

    enum Role {
        NOISY_DIFFUSE(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " noisy diffuse"),
        NOISY_SPECULAR(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " noisy specular"),
        NOISY_DIFFUSE_SH1(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " noisy diffuse SH1"),
        NOISY_SPECULAR_SH1(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " noisy specular SH1"),
        NORMAL_ROUGHNESS(VK12.VK_FORMAT_R32G32B32A32_SFLOAT, " normal roughness"),
        VIEW_Z(VK12.VK_FORMAT_R32_SFLOAT, " view Z"),
        MOTION(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " 2.5D screen motion"),
        FSR_MOTION(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " visible-surface FSR motion"),
        FSR_DEPTH(VK12.VK_FORMAT_R32_SFLOAT, " FSR depth"),
        MATERIAL(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " material metadata"),
        SPECULAR_MATERIAL(
                VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " specular material or virtual guide"),
        RECONSTRUCTION_CONTROL(VK12.VK_FORMAT_R8_UINT, " reconstruction control"),
        PRIMARY_POSITION(VK12.VK_FORMAT_R32G32B32A32_SFLOAT, " primary or virtual position"),
        SUN_LIGHTING(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " unshadowed sun lighting"),
        SUN_PENUMBRA(VK12.VK_FORMAT_R16_SFLOAT, " noisy sun penumbra"),
        SUN_SHADOW(VK12.VK_FORMAT_R16_SFLOAT, " SIGMA sun shadow"),
        DENOISED_DIFFUSE(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " denoised diffuse"),
        DENOISED_SPECULAR(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " denoised specular"),
        DENOISED_DIFFUSE_SH1(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " denoised diffuse SH1"),
        DENOISED_SPECULAR_SH1(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " denoised specular SH1"),
        REFLECTION_NOISY_DIFFUSE(
                VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " reflection noisy diffuse"),
        REFLECTION_NOISY_SPECULAR(
                VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " reflection noisy specular"),
        REFLECTION_NOISY_DIFFUSE_SH1(
                VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " reflection noisy diffuse SH1"),
        REFLECTION_NOISY_SPECULAR_SH1(
                VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " reflection noisy specular SH1"),
        REFLECTION_NORMAL_ROUGHNESS(
                VK12.VK_FORMAT_R32G32B32A32_SFLOAT, " reflection normal roughness"),
        REFLECTION_VIEW_Z(VK12.VK_FORMAT_R32_SFLOAT, " reflection view Z"),
        REFLECTION_MOTION(
                VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " reflection 2.5D motion"),
        REFLECTION_MATERIAL(
                VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " reflection material"),
        REFLECTION_SPECULAR_MATERIAL(
                VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " reflection specular material"),
        REFLECTION_POSITION(
                VK12.VK_FORMAT_R32G32B32A32_SFLOAT, " reflection virtual position"),
        REFLECTION_DENOISED_DIFFUSE(
                VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " reflection denoised diffuse"),
        REFLECTION_DENOISED_SPECULAR(
                VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " reflection denoised specular"),
        REFLECTION_DENOISED_DIFFUSE_SH1(
                VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " reflection denoised diffuse SH1"),
        REFLECTION_DENOISED_SPECULAR_SH1(
                VK12.VK_FORMAT_R16G16B16A16_SFLOAT, " reflection denoised specular SH1"),
        DISPLAY_POSITION(VK12.VK_FORMAT_R32G32B32A32_SFLOAT, " visible primary position"),
        FSR_REACTIVE_MASK(VK12.VK_FORMAT_R8_UNORM, " FSR reactive mask"),
        FSR_TRANSPARENCY_COMPOSITION_MASK(VK12.VK_FORMAT_R8_UNORM, " FSR transparency mask");

        private final int format;
        private final String label;

        Role(int format, String label) {
            this.format = format;
            this.label = label;
        }
    }

    private static int vkFormat(int nrdFormat) {
        return switch (nrdFormat) {
            case 0 -> VK12.VK_FORMAT_R8_UNORM;
            case 1 -> VK12.VK_FORMAT_R8_SNORM;
            case 2 -> VK12.VK_FORMAT_R8_UINT;
            case 3 -> VK12.VK_FORMAT_R8_SINT;
            case 4 -> VK12.VK_FORMAT_R8G8_UNORM;
            case 5 -> VK12.VK_FORMAT_R8G8_SNORM;
            case 6 -> VK12.VK_FORMAT_R8G8_UINT;
            case 7 -> VK12.VK_FORMAT_R8G8_SINT;
            case 8 -> VK12.VK_FORMAT_R8G8B8A8_UNORM;
            case 9 -> VK12.VK_FORMAT_R8G8B8A8_SNORM;
            case 10 -> VK12.VK_FORMAT_R8G8B8A8_UINT;
            case 11 -> VK12.VK_FORMAT_R8G8B8A8_SINT;
            case 12 -> VK12.VK_FORMAT_R8G8B8A8_SRGB;
            case 13 -> VK12.VK_FORMAT_R16_UNORM;
            case 14 -> VK12.VK_FORMAT_R16_SNORM;
            case 15 -> VK12.VK_FORMAT_R16_UINT;
            case 16 -> VK12.VK_FORMAT_R16_SINT;
            case 17 -> VK12.VK_FORMAT_R16_SFLOAT;
            case 18 -> VK12.VK_FORMAT_R16G16_UNORM;
            case 19 -> VK12.VK_FORMAT_R16G16_SNORM;
            case 20 -> VK12.VK_FORMAT_R16G16_UINT;
            case 21 -> VK12.VK_FORMAT_R16G16_SINT;
            case 22 -> VK12.VK_FORMAT_R16G16_SFLOAT;
            case 23 -> VK12.VK_FORMAT_R16G16B16A16_UNORM;
            case 24 -> VK12.VK_FORMAT_R16G16B16A16_SNORM;
            case 25 -> VK12.VK_FORMAT_R16G16B16A16_UINT;
            case 26 -> VK12.VK_FORMAT_R16G16B16A16_SINT;
            case 27 -> VK12.VK_FORMAT_R16G16B16A16_SFLOAT;
            case 28 -> VK12.VK_FORMAT_R32_UINT;
            case 29 -> VK12.VK_FORMAT_R32_SINT;
            case 30 -> VK12.VK_FORMAT_R32_SFLOAT;
            case 31 -> VK12.VK_FORMAT_R32G32_UINT;
            case 32 -> VK12.VK_FORMAT_R32G32_SINT;
            case 33 -> VK12.VK_FORMAT_R32G32_SFLOAT;
            case 34 -> VK12.VK_FORMAT_R32G32B32_UINT;
            case 35 -> VK12.VK_FORMAT_R32G32B32_SINT;
            case 36 -> VK12.VK_FORMAT_R32G32B32_SFLOAT;
            case 37 -> VK12.VK_FORMAT_R32G32B32A32_UINT;
            case 38 -> VK12.VK_FORMAT_R32G32B32A32_SINT;
            case 39 -> VK12.VK_FORMAT_R32G32B32A32_SFLOAT;
            case 40 -> VK12.VK_FORMAT_A2B10G10R10_UNORM_PACK32;
            case 41 -> VK12.VK_FORMAT_A2B10G10R10_UINT_PACK32;
            case 42 -> VK12.VK_FORMAT_B10G11R11_UFLOAT_PACK32;
            case 43 -> VK12.VK_FORMAT_E5B9G9R9_UFLOAT_PACK32;
            default -> throw new IllegalStateException(
                    "Unsupported NRD texture format " + nrdFormat);
        };
    }
}
