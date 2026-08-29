package dev.prime.render.vulkan.terrain;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.vulkan.StagingArena;
import dev.prime.render.vulkan.TopLevelAccelerationStructure;
import dev.prime.render.vulkan.VulkanBuffer;
import dev.prime.render.vulkan.VulkanContext;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Owns unpublished terrain GPU resources across recording, submission and publication. */
final class TerrainUpdateTransaction implements AutoCloseable {
    private final VulkanContext context;
    private final VoxelBlasPool voxelBlasPool;
    private final @Nullable StagingArena.Batch clusterStaging;
    private final @Nullable StagingArena.Batch worldStaging;
    private final List<GpuCluster> replacements;
    private @Nullable TopLevelAccelerationStructure tlas;
    private @Nullable VulkanBuffer worldLights;
    private State state = State.OPEN;

    TerrainUpdateTransaction(
            VulkanContext context,
            VoxelBlasPool voxelBlasPool,
            @Nullable StagingArena.Batch clusterStaging,
            @Nullable StagingArena.Batch worldStaging,
            @Nullable TopLevelAccelerationStructure tlas,
            int replacementCapacity) {
        this.context = context;
        this.voxelBlasPool = voxelBlasPool;
        this.clusterStaging = clusterStaging;
        this.worldStaging = worldStaging;
        this.tlas = tlas;
        this.replacements = new ArrayList<>(replacementCapacity);
    }

    List<GpuCluster> replacements() {
        requireOpen("access replacement clusters");
        return this.replacements;
    }

    void worldLights(VulkanBuffer worldLights) {
        requireOpen("attach world lights");
        if (this.worldLights != null) {
            throw new IllegalStateException("Terrain transaction already owns world lights");
        }
        this.worldLights = worldLights;
    }

    void submitted() {
        requireOpen("submit terrain resources");
        this.state = State.SUBMITTED;
        RuntimeException failure = null;
        if (this.clusterStaging != null) {
            failure = ResourceCleanup.run(this.clusterStaging::submitted, failure);
        }
        if (this.worldStaging != null) {
            failure = ResourceCleanup.run(this.worldStaging::submitted, failure);
        }
        ResourceCleanup.throwIfFailed(failure);
    }

    void published() {
        if (this.state != State.OPEN && this.state != State.SUBMITTED) {
            throw new IllegalStateException(
                    "Cannot publish terrain resources after " + stateName());
        }
        this.state = State.PUBLISHED;
        this.tlas = null;
        this.worldLights = null;
    }

    RuntimeException abort(RuntimeException failure) {
        if (this.state == State.CLOSED) {
            return failure;
        }
        if (this.state != State.PUBLISHED) {
            for (GpuCluster replacement : this.replacements) {
                if (this.state == State.SUBMITTED) {
                    failure = ResourceCleanup.run(
                            () -> {
                                Destroyable cleanup = replacement.prepareRetirement(
                                        this.voxelBlasPool);
                                this.context.defer(cleanup);
                            },
                            failure);
                } else {
                    failure = ResourceCleanup.run(
                            () -> replacement.prepareRetirement(this.voxelBlasPool).destroy(),
                            failure);
                }
            }
            if (this.tlas != null) {
                TopLevelAccelerationStructure failedTlas = this.tlas;
                failure = this.state == State.SUBMITTED
                        ? ResourceCleanup.run(
                                () -> this.context.defer(failedTlas::release), failure)
                        : ResourceCleanup.run(failedTlas::release, failure);
                this.tlas = null;
            }
            if (this.worldLights != null) {
                VulkanBuffer failedWorldLights = this.worldLights;
                failure = this.state == State.SUBMITTED
                        ? ResourceCleanup.run(
                                () -> this.context.defer(failedWorldLights), failure)
                        : ResourceCleanup.destroy(failedWorldLights, failure);
                this.worldLights = null;
            }
        }
        failure = ResourceCleanup.close(this.clusterStaging, failure);
        failure = ResourceCleanup.close(this.worldStaging, failure);
        this.state = State.CLOSED;
        return failure;
    }

    @Override
    public void close() {
        if (this.state == State.CLOSED) {
            return;
        }
        RuntimeException failure = null;
        failure = ResourceCleanup.close(this.clusterStaging, failure);
        failure = ResourceCleanup.close(this.worldStaging, failure);
        this.state = State.CLOSED;
        ResourceCleanup.throwIfFailed(failure);
    }

    private void requireOpen(String operation) {
        if (this.state != State.OPEN) {
            throw new IllegalStateException(
                    "Cannot " + operation + " after " + stateName());
        }
    }

    private String stateName() {
        return this.state.name().toLowerCase(java.util.Locale.ROOT);
    }

    private enum State {
        OPEN,
        SUBMITTED,
        PUBLISHED,
        CLOSED
    }
}
