package dev.prime.render.vulkan.terrain;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.terrain.*;
import dev.prime.render.vulkan.PreparedBlas;
import dev.prime.render.vulkan.VulkanBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.lwjgl.vulkan.VkCommandBuffer;

record GpuCluster(
        long key,
        int clusterX,
        int clusterY,
        int clusterZ,
        PreparedBlas blas,
        List<PreparedBlas> voxelBlases,
        ResolvedVoxelInstances voxelInstances,
        long surfaceRelationAddress,
        VulkanBuffer lightBuffer,
        VulkanBuffer motionBuffer,
        CompiledClusterLights.Summary lights,
        TextureTintUsage textureTintUsage,
        MaterialTableCandidate materialTableCandidate,
        long surfaceRelationSourceBytes,
        long surfaceRelationGpuBytes,
        boolean dynamic,
        DynamicBufferPool.Lease dynamicBuffers) {
    GpuCluster {
        voxelBlases = List.copyOf(voxelBlases);
        voxelInstances = Objects.requireNonNull(
                voxelInstances, "voxelInstances");
        lights = Objects.requireNonNull(lights, "lights");
        textureTintUsage = Objects.requireNonNull(textureTintUsage, "textureTintUsage");
        materialTableCandidate = Objects.requireNonNull(
                materialTableCandidate, "materialTableCandidate");
        if (surfaceRelationSourceBytes < 0L
                || surfaceRelationGpuBytes < 0L
                || surfaceRelationGpuBytes > surfaceRelationSourceBytes
                || (surfaceRelationAddress == 0L) != (surfaceRelationGpuBytes == 0L)) {
            throw new IllegalArgumentException(
                    "GPU surface-relation storage is inconsistent");
        }
        if (lightBuffer != null && motionBuffer != null
                || motionBuffer != null && !dynamic) {
            throw new IllegalArgumentException(
                    "Only a dynamic cluster may own previous-position storage");
        }
        if (dynamicBuffers != null
                && (!dynamic || motionBuffer != dynamicBuffers.motion())) {
            throw new IllegalArgumentException(
                    "A dynamic buffer lease must own the cluster motion buffer");
        }
        if (voxelBlases.isEmpty() != (voxelInstances.count() == 0)) {
            throw new IllegalArgumentException(
                    "GPU voxel meshes and their instances must be present together");
        }
        for (int meshIndex : voxelInstances.meshIndices()) {
            if (meshIndex < 0 || meshIndex >= voxelBlases.size()) {
                throw new IllegalArgumentException(
                        "GPU voxel instance references an invalid BLAS");
            }
        }
    }

    GpuCluster(
            long key,
            int clusterX,
            int clusterY,
            int clusterZ,
            PreparedBlas blas,
            List<PreparedBlas> voxelBlases,
            ResolvedVoxelInstances voxelInstances,
            VulkanBuffer lightBuffer,
            VulkanBuffer motionBuffer,
            CompiledClusterLights.Summary lights,
            boolean dynamic) {
        this(
                key,
                clusterX,
                clusterY,
                clusterZ,
                blas,
                voxelBlases,
                voxelInstances,
                0L,
                lightBuffer,
                motionBuffer,
                lights,
                TextureTintUsage.EMPTY,
                MaterialTableCandidate.EMPTY,
                0L,
                0L,
                dynamic,
                null);
    }

    GpuCluster(
            long key,
            int clusterX,
            int clusterY,
            int clusterZ,
            PreparedBlas blas,
            VulkanBuffer lightBuffer,
            CompiledClusterLights.Summary lights) {
        this(
                key,
                clusterX,
                clusterY,
                clusterZ,
                blas,
                lightBuffer,
                lights,
                false);
    }

    GpuCluster(
            long key,
            int clusterX,
            int clusterY,
            int clusterZ,
            PreparedBlas blas,
            VulkanBuffer lightBuffer,
            CompiledClusterLights.Summary lights,
            boolean dynamic) {
        this(
                key,
                clusterX,
                clusterY,
                clusterZ,
                blas,
                List.of(),
                ResolvedVoxelInstances.EMPTY,
                0L,
                lightBuffer,
                null,
                lights,
                TextureTintUsage.EMPTY,
                MaterialTableCandidate.EMPTY,
                0L,
                0L,
                dynamic,
                null);
    }
    long lightAddress() {
        // Dynamic clusters have no emitters, so this section slot carries the previous-position
        // address consumed only after closest hit has identified a dynamic primitive.
        VulkanBuffer payload = this.lightBuffer != null
                ? this.lightBuffer
                : this.motionBuffer;
        return payload == null ? 0L : payload.deviceAddress();
    }

    PreparedBlas baseBlas() {
        if (this.blas != null) {
            return this.blas;
        }
        if (this.voxelBlases.isEmpty()) {
            throw new IllegalStateException(
                    "A resident GPU cluster must own at least one BLAS");
        }
        return this.voxelBlases.getFirst();
    }

    int tlasInstanceCount() {
        return Math.addExact(1, this.voxelInstances.count());
    }

    long instancedTriangleCount() {
        long count = this.blas == null ? 0L : triangleCount(this.blas);
        for (int meshIndex : this.voxelInstances.meshIndices()) {
            count = Math.addExact(
                    count, triangleCount(this.voxelBlases.get(meshIndex)));
        }
        return count;
    }

    static long triangleCount(PreparedBlas blas) {
        return Math.addExact(
                Math.addExact(
                        blas.opaqueTriangleCount(),
                        blas.cutoutTriangleCount()),
                blas.transmissiveTriangleCount());
    }

    void forEachBlas(Consumer<PreparedBlas> consumer) {
        if (this.blas != null) {
            consumer.accept(this.blas);
        }
        this.voxelBlases.forEach(consumer);
    }

    boolean hasOpacityMicromapBuild(VoxelBlasPool voxelPool) {
        if (this.blas != null && this.blas.hasOpacityMicromapBuild()) {
            return true;
        }
        for (PreparedBlas voxelBlas : this.voxelBlases) {
            if (voxelPool.hasOpacityMicromapBuild(voxelBlas)) {
                return true;
            }
        }
        return false;
    }

    void recordOpacityMicromapBuild(
            VoxelBlasPool voxelPool, VkCommandBuffer commandBuffer) {
        if (this.blas != null) {
            this.blas.recordOpacityMicromapBuild(commandBuffer);
        }
        for (PreparedBlas voxelBlas : this.voxelBlases) {
            voxelPool.recordOpacityMicromapBuild(voxelBlas, commandBuffer);
        }
    }

    void recordBuild(VoxelBlasPool voxelPool, VkCommandBuffer commandBuffer) {
        if (this.blas != null) {
            this.blas.recordBuild(commandBuffer);
        }
        for (PreparedBlas voxelBlas : this.voxelBlases) {
            voxelPool.recordBuild(voxelBlas, commandBuffer);
        }
    }

    void submitted(VoxelBlasPool voxelPool) {
        RuntimeException failure = null;
        if (this.blas != null) {
            failure = ResourceCleanup.run(this.blas::onBuildSubmitted, failure);
            failure = ResourceCleanup.run(this.blas::retireBuildResources, failure);
        }
        for (PreparedBlas voxelBlas : this.voxelBlases) {
            failure = ResourceCleanup.run(
                    () -> voxelPool.submitted(voxelBlas), failure);
        }
        ResourceCleanup.throwIfFailed(failure);
    }

    /**
     * Releases render-thread-owned pool references and returns GPU cleanup safe to defer.
     */
    Destroyable prepareRetirement(VoxelBlasPool voxelPool) {
        ArrayList<PreparedBlas> releasedVoxelBlases = new ArrayList<>();
        if (this.blas != null) {
            this.blas.releaseSharedResources();
        }
        for (PreparedBlas voxelBlas : this.voxelBlases) {
            PreparedBlas released = voxelPool.release(voxelBlas);
            if (released != null) {
                releasedVoxelBlases.add(released);
                released.releaseSharedResources();
            }
        }
        return () -> {
            RuntimeException failure = null;
            if (this.blas != null) {
                failure = ResourceCleanup.run(
                        this.blas::destroyAllResources, failure);
            }
            for (PreparedBlas voxelBlas : releasedVoxelBlases) {
                failure = ResourceCleanup.run(
                        voxelBlas::destroyAllResources, failure);
            }
            failure = ResourceCleanup.destroy(this.lightBuffer, failure);
            if (this.dynamicBuffers != null) {
                failure = ResourceCleanup.run(this.dynamicBuffers::release, failure);
            } else {
                failure = ResourceCleanup.destroy(this.motionBuffer, failure);
            }
            ResourceCleanup.throwIfFailed(failure);
        };
    }

}
