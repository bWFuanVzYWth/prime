package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Recording and lifetime boundary for the realtime integrator. */
public interface RealtimeIntegratorPipeline extends Destroyable {
    void ensureDescriptors(
            long tlas,
            VulkanImage stableRadiance,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler,
            List<TraceBackend.SceneTexture> sceneTextures,
            List<VulkanImage> materialBaseColorPages,
            List<VulkanImage> materialNormalPages,
            List<VulkanImage> materialOpticalPages,
            VulkanBuffer textureRecords,
            TerrainScene.MaterialCoreBinding materialCore,
            TerrainScene.TintOperatorBinding tintOperators,
            AtmospherePipeline atmosphere,
            RawWavefrontFrame signals);

    void trace(
            VkCommandBuffer commandBuffer,
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene);

    int passCount();

    long sizedResourceBytes();

    void releaseSizedResourcesAfterIdle();
}
