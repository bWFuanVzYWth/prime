package dev.prime.render.vulkan.nrd;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.vulkan.VK12;

/** Owns every persistent and transient image used by one NRD instance. */
final class NrdImages implements Destroyable {
    private static final int IMAGE_USAGE =
            VK12.VK_IMAGE_USAGE_STORAGE_BIT
                    | VK12.VK_IMAGE_USAGE_SAMPLED_BIT
                    | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    final VulkanImage noisyDiffuse;
    final VulkanImage noisySpecular;
    final VulkanImage noisyDiffuseSh1;
    final VulkanImage noisySpecularSh1;
    final VulkanImage normalRoughness;
    final VulkanImage viewZ;
    final VulkanImage motion;
    final VulkanImage fsrMotion;
    final VulkanImage fsrDepth;
    final VulkanImage material;
    final VulkanImage specularMaterial;
    final VulkanImage reconstructionControl;
    final VulkanImage primaryPosition;
    final VulkanImage sunLighting;
    final VulkanImage sunPenumbra;
    final VulkanImage sunShadow;
    final VulkanImage denoisedDiffuse;
    final VulkanImage denoisedSpecular;
    final VulkanImage denoisedDiffuseSh1;
    final VulkanImage denoisedSpecularSh1;
    final VulkanImage reflectionNoisyDiffuse;
    final VulkanImage reflectionNoisySpecular;
    final VulkanImage reflectionNoisyDiffuseSh1;
    final VulkanImage reflectionNoisySpecularSh1;
    final VulkanImage reflectionNormalRoughness;
    final VulkanImage reflectionViewZ;
    final VulkanImage reflectionMotion;
    final VulkanImage reflectionMaterial;
    final VulkanImage reflectionSpecularMaterial;
    final VulkanImage reflectionPosition;
    final VulkanImage reflectionDenoisedDiffuse;
    final VulkanImage reflectionDenoisedSpecular;
    final VulkanImage reflectionDenoisedDiffuseSh1;
    final VulkanImage reflectionDenoisedSpecularSh1;
    final VulkanImage displayPosition;
    final VulkanImage fsrReactiveMask;
    final VulkanImage fsrTransparencyCompositionMask;
    final VulkanImage[] permanentPool;
    final VulkanImage[] transientPool;
    final VulkanImage[] ownedImages;
    private boolean destroyed;

    private NrdImages(
            VulkanImage noisyDiffuse,
            VulkanImage noisySpecular,
            VulkanImage noisyDiffuseSh1,
            VulkanImage noisySpecularSh1,
            VulkanImage normalRoughness,
            VulkanImage viewZ,
            VulkanImage motion,
            VulkanImage fsrMotion,
            VulkanImage fsrDepth,
            VulkanImage material,
            VulkanImage specularMaterial,
            VulkanImage reconstructionControl,
            VulkanImage primaryPosition,
            VulkanImage sunLighting,
            VulkanImage sunPenumbra,
            VulkanImage sunShadow,
            VulkanImage denoisedDiffuse,
            VulkanImage denoisedSpecular,
            VulkanImage denoisedDiffuseSh1,
            VulkanImage denoisedSpecularSh1,
            VulkanImage reflectionNoisyDiffuse,
            VulkanImage reflectionNoisySpecular,
            VulkanImage reflectionNoisyDiffuseSh1,
            VulkanImage reflectionNoisySpecularSh1,
            VulkanImage reflectionNormalRoughness,
            VulkanImage reflectionViewZ,
            VulkanImage reflectionMotion,
            VulkanImage reflectionMaterial,
            VulkanImage reflectionSpecularMaterial,
            VulkanImage reflectionPosition,
            VulkanImage reflectionDenoisedDiffuse,
            VulkanImage reflectionDenoisedSpecular,
            VulkanImage reflectionDenoisedDiffuseSh1,
            VulkanImage reflectionDenoisedSpecularSh1,
            VulkanImage displayPosition,
            VulkanImage fsrReactiveMask,
            VulkanImage fsrTransparencyCompositionMask,
            VulkanImage[] permanentPool,
            VulkanImage[] transientPool,
            VulkanImage[] ownedImages) {
        this.noisyDiffuse = noisyDiffuse;
        this.noisySpecular = noisySpecular;
        this.noisyDiffuseSh1 = noisyDiffuseSh1;
        this.noisySpecularSh1 = noisySpecularSh1;
        this.normalRoughness = normalRoughness;
        this.viewZ = viewZ;
        this.motion = motion;
        this.fsrMotion = fsrMotion;
        this.fsrDepth = fsrDepth;
        this.material = material;
        this.specularMaterial = specularMaterial;
        this.reconstructionControl = reconstructionControl;
        this.primaryPosition = primaryPosition;
        this.sunLighting = sunLighting;
        this.sunPenumbra = sunPenumbra;
        this.sunShadow = sunShadow;
        this.denoisedDiffuse = denoisedDiffuse;
        this.denoisedSpecular = denoisedSpecular;
        this.denoisedDiffuseSh1 = denoisedDiffuseSh1;
        this.denoisedSpecularSh1 = denoisedSpecularSh1;
        this.reflectionNoisyDiffuse = reflectionNoisyDiffuse;
        this.reflectionNoisySpecular = reflectionNoisySpecular;
        this.reflectionNoisyDiffuseSh1 = reflectionNoisyDiffuseSh1;
        this.reflectionNoisySpecularSh1 = reflectionNoisySpecularSh1;
        this.reflectionNormalRoughness = reflectionNormalRoughness;
        this.reflectionViewZ = reflectionViewZ;
        this.reflectionMotion = reflectionMotion;
        this.reflectionMaterial = reflectionMaterial;
        this.reflectionSpecularMaterial = reflectionSpecularMaterial;
        this.reflectionPosition = reflectionPosition;
        this.reflectionDenoisedDiffuse = reflectionDenoisedDiffuse;
        this.reflectionDenoisedSpecular = reflectionDenoisedSpecular;
        this.reflectionDenoisedDiffuseSh1 = reflectionDenoisedDiffuseSh1;
        this.reflectionDenoisedSpecularSh1 = reflectionDenoisedSpecularSh1;
        this.displayPosition = displayPosition;
        this.fsrReactiveMask = fsrReactiveMask;
        this.fsrTransparencyCompositionMask = fsrTransparencyCompositionMask;
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
            VulkanImage noisy = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, debugPrefix + " noisy diffuse");
            VulkanImage noisySpecular = createImage(
                    context,
                    created,
                    width,
                    height,
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    debugPrefix + " noisy specular");
            VulkanImage noisyDiffuseSh1 = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    debugPrefix + " noisy diffuse SH1");
            VulkanImage noisySpecularSh1 = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    debugPrefix + " noisy specular SH1");
            VulkanImage normal = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                    debugPrefix + " normal roughness");
            VulkanImage viewZ = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R32_SFLOAT, debugPrefix + " view Z");
            VulkanImage motion = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, debugPrefix + " 2.5D screen motion");
            VulkanImage fsrMotion = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, debugPrefix + " visible-surface FSR motion");
            VulkanImage fsrDepth = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R32_SFLOAT, debugPrefix + " FSR depth");
            VulkanImage material = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, debugPrefix + " material metadata");
            VulkanImage specularMaterial = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, debugPrefix + " specular material or virtual guide");
            VulkanImage reconstructionControl = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R8_UINT,
                    debugPrefix + " reconstruction control");
            VulkanImage primaryPosition = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R32G32B32A32_SFLOAT, debugPrefix + " primary or virtual position");
            VulkanImage sunLighting = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, debugPrefix + " unshadowed sun lighting");
            VulkanImage sunPenumbra = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16_SFLOAT, debugPrefix + " noisy sun penumbra");
            VulkanImage sunShadow = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16_SFLOAT, debugPrefix + " SIGMA sun shadow");
            VulkanImage denoised = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, debugPrefix + " denoised diffuse");
            VulkanImage denoisedSpecular = createImage(
                    context,
                    created,
                    width,
                    height,
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    debugPrefix + " denoised specular");
            VulkanImage denoisedDiffuseSh1 = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    debugPrefix + " denoised diffuse SH1");
            VulkanImage denoisedSpecularSh1 = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    debugPrefix + " denoised specular SH1");
            VulkanImage reflectionNoisyDiffuse = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    debugPrefix + " reflection noisy diffuse");
            VulkanImage reflectionNoisySpecular = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    debugPrefix + " reflection noisy specular");
            VulkanImage reflectionNoisyDiffuseSh1 = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    debugPrefix + " reflection noisy diffuse SH1");
            VulkanImage reflectionNoisySpecularSh1 = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    debugPrefix + " reflection noisy specular SH1");
            VulkanImage reflectionNormalRoughness = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                    debugPrefix + " reflection normal roughness");
            VulkanImage reflectionViewZ = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R32_SFLOAT,
                    debugPrefix + " reflection view Z");
            VulkanImage reflectionMotion = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    debugPrefix + " reflection 2.5D motion");
            VulkanImage reflectionMaterial = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    debugPrefix + " reflection material");
            VulkanImage reflectionSpecularMaterial = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    debugPrefix + " reflection specular material");
            VulkanImage reflectionPosition = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                    debugPrefix + " reflection virtual position");
            VulkanImage reflectionDenoisedDiffuse = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    debugPrefix + " reflection denoised diffuse");
            VulkanImage reflectionDenoisedSpecular = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    debugPrefix + " reflection denoised specular");
            VulkanImage reflectionDenoisedDiffuseSh1 = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    debugPrefix + " reflection denoised diffuse SH1");
            VulkanImage reflectionDenoisedSpecularSh1 = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    debugPrefix + " reflection denoised specular SH1");
            VulkanImage displayPosition = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                    debugPrefix + " visible primary position");
            VulkanImage fsrReactiveMask = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R8_UNORM, debugPrefix + " FSR reactive mask");
            VulkanImage fsrTransparencyCompositionMask = createImage(
                    context, created, width, height, VK12.VK_FORMAT_R8_UNORM, debugPrefix + " FSR transparency mask");
            VulkanImage[] permanent = createPool(
                    context, created, width, height, description.permanentPool(), debugPrefix + " permanent");
            VulkanImage[] transientImages = createPool(
                    context, created, width, height, description.transientPool(), debugPrefix + " transient");
            return new NrdImages(
                    noisy,
                    noisySpecular,
                    noisyDiffuseSh1,
                    noisySpecularSh1,
                    normal,
                    viewZ,
                    motion,
                    fsrMotion,
                    fsrDepth,
                    material,
                    specularMaterial,
                    reconstructionControl,
                    primaryPosition,
                    sunLighting,
                    sunPenumbra,
                    sunShadow,
                    denoised,
                    denoisedSpecular,
                    denoisedDiffuseSh1,
                    denoisedSpecularSh1,
                    reflectionNoisyDiffuse,
                    reflectionNoisySpecular,
                    reflectionNoisyDiffuseSh1,
                    reflectionNoisySpecularSh1,
                    reflectionNormalRoughness,
                    reflectionViewZ,
                    reflectionMotion,
                    reflectionMaterial,
                    reflectionSpecularMaterial,
                    reflectionPosition,
                    reflectionDenoisedDiffuse,
                    reflectionDenoisedSpecular,
                    reflectionDenoisedDiffuseSh1,
                    reflectionDenoisedSpecularSh1,
                    displayPosition,
                    fsrReactiveMask,
                    fsrTransparencyCompositionMask,
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

    VulkanImage[] allImages() {
        return this.ownedImages;
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        VulkanImage[] owned = this.allImages();
        for (int index = owned.length - 1; index >= 0; index--) {
            owned[index].destroy();
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
