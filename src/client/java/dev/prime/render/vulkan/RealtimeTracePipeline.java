// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Frame-owned transport; reconstruction consumes the same raw signal contract for both models. */
public interface RealtimeTracePipeline extends Destroyable {
    int passCount();
    long sizedResourceBytes();
    void releaseSizedResourcesAfterIdle();
    void ensureDescriptors(long tlas, VulkanImage stableRadiance,
            VulkanGpuTextureView atlasView, VulkanGpuSampler atlasSampler,
            List<TraceBackend.SceneTexture> sceneTextures,
            MaterialTexturePages.Binding materialTextures, TerrainScene.MaterialCoreBinding materialCore,
            TerrainScene.SurfaceBinding surfaces, TerrainScene.TintSampleBinding tintSamples,
            AtmospherePipeline atmosphere, RawWavefrontFrame signals);
    void trace(VkCommandBuffer commandBuffer, IntegratorFrameInput input, TerrainScene.ResidentSceneView scene);
}
