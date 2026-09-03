package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.SunDirection;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.terrain.TerrainOccluderChange;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageSubresourceRange;

/**
 * Render-thread-owned RT directional-shadow clipmap used by local atmospheric scattering.
 *
 * <p>Each texel stores the first opaque sun-ray hit as a scene-origin-relative coordinate along
 * the bank's fixed sun direction. Two banks allow one direction to remain readable while the next
 * direction is assembled over sixteen frames. Initial or unpublished rebuild texels use a
 * conservative +infinity sentinel, so incomplete work can remove sunlight but can never create
 * cave light leaks. Published streaming changes replace their exact affected columns in one frame.
 */
final class SunShadowClipmap implements Destroyable {
    static final int BANK_COUNT = 2;
    static final int CASCADE_COUNT = 5;
    static final int RESOLUTION = 512;
    static final int TILE_SIZE = 128;
    static final int TILE_COUNT = 16;
    static final float MAX_LOCAL_DISTANCE_METERS = 2_048.0F;
    static final float UNKNOWN_DEPTH = 1.0e20F;
    static final float NO_HIT_DEPTH = -1.0e20F;

    private static final float HALF_TRACE_DISTANCE_METERS = 4_096.0F;
    private static final float DIRECTION_REBUILD_COSINE =
            (float) Math.cos(Math.toRadians(0.02));
    private static final float[] TEXEL_SIZES = {0.5F, 1.0F, 2.0F, 4.0F, 8.0F};
    private static final int[] PRIMARY_TILE_ORDER = {
        5, 6, 9, 10,
        1, 2, 4, 7, 8, 11, 13, 14,
        0, 3, 12, 15
    };
    private static final int IMAGE_COUNT = BANK_COUNT * CASCADE_COUNT;
    private static final int COMPUTE_AND_RAY_STAGES =
            VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                    | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;

    private final VulkanContext context;
    private final VulkanImage[] depths = new VulkanImage[IMAGE_COUNT];
    private final State committed = new State();
    private final State candidate = new State();
    private boolean pending;
    private boolean destroyed;

    SunShadowClipmap(VulkanContext context) {
        this.context = context;
        try {
            for (int bank = 0; bank < BANK_COUNT; bank++) {
                for (int cascade = 0; cascade < CASCADE_COUNT; cascade++) {
                    int index = imageIndex(bank, cascade);
                    this.depths[index] = context.createImage2D(
                            RESOLUTION,
                            RESOLUTION,
                            VK12.VK_FORMAT_R32_SFLOAT,
                            VK12.VK_IMAGE_USAGE_STORAGE_BIT
                                    | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                            "Prime sun shadow bank " + bank + " cascade " + cascade);
                }
            }
        } catch (RuntimeException exception) {
            for (VulkanImage depth : this.depths) {
                if (depth != null) {
                    depth.destroy();
                }
            }
            throw exception;
        }
    }

    VulkanImage depth(int bank, int cascade) {
        return this.depths[imageIndex(bank, cascade)];
    }

    int contentVersion() {
        return this.pending
                ? this.candidate.contentVersion
                : this.committed.contentVersion;
    }

    int activeBank() {
        return this.pending ? this.candidate.activeBank : this.committed.activeBank;
    }

    SunDirection activeDirection(SunDirection fallback) {
        Bank bank = (this.pending ? this.candidate : this.committed)
                .banks[this.activeBank()];
        return bank.valid ? bank.direction : fallback;
    }

    boolean activeValid() {
        State state = this.pending ? this.candidate : this.committed;
        return state.banks[state.activeBank].valid;
    }

    void writeQueryConstants(ByteBuffer target) {
        State state = this.pending ? this.candidate : this.committed;
        Bank active = state.banks[state.activeBank];
        writeQueryConstants(target, active.direction, state.activeBank, active.valid);
    }

    static void writeQueryConstants(
            ByteBuffer target,
            SunDirection direction,
            int bank,
            boolean valid) {
        if (target.capacity() < ShaderAbi.SUN_SHADOW_QUERY_CONSTANT_SIZE) {
            throw new IllegalArgumentException(
                    "Sun-shadow query constant buffer is too small");
        }
        if (bank < 0 || bank >= BANK_COUNT) {
            throw new IllegalArgumentException("Invalid sun-shadow query bank");
        }
        Basis basis = basis(direction);
        int directionOffset = ShaderAbi.SUN_SHADOW_QUERY_DIRECTION_TO_SUN_OFFSET;
        target.order(ByteOrder.nativeOrder())
                .putFloat(directionOffset, direction.x())
                .putFloat(directionOffset + Float.BYTES, direction.y())
                .putFloat(directionOffset + 2 * Float.BYTES, direction.z())
                .putInt(ShaderAbi.SUN_SHADOW_QUERY_BANK_OFFSET, bank);
        int basisUOffset = ShaderAbi.SUN_SHADOW_QUERY_BASIS_U_OFFSET;
        target.putFloat(basisUOffset, basis.ux)
                .putFloat(basisUOffset + Float.BYTES, basis.uy)
                .putFloat(basisUOffset + 2 * Float.BYTES, basis.uz)
                .putInt(ShaderAbi.SUN_SHADOW_QUERY_VALID_OFFSET, valid ? 1 : 0);
        int basisVOffset = ShaderAbi.SUN_SHADOW_QUERY_BASIS_V_OFFSET;
        target.putFloat(basisVOffset, basis.vx)
                .putFloat(basisVOffset + Float.BYTES, basis.vy)
                .putFloat(basisVOffset + 2 * Float.BYTES, basis.vz)
                .putInt(ShaderAbi.SUN_SHADOW_QUERY_RESERVED_OFFSET, 0);
    }

    /**
     * Records all cache work for one frame and leaves a candidate CPU state pending until submit.
     */
    boolean prepare(
            VkCommandBuffer commandBuffer,
            SunShadowPipeline pipeline,
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene,
            boolean forceComplete) {
        if (this.pending) {
            throw new IllegalStateException(
                    "Previous sun-shadow clipmap candidate is still pending");
        }
        this.candidate.copyFrom(this.committed);
        boolean wrote = false;
        if (this.candidate.initialized) {
            shaderAccessToRayWrite(commandBuffer);
        }
        boolean unrelated = !this.candidate.initialized
                || this.candidate.resetRevision != scene.resetRevision()
                || this.candidate.originX != scene.originX()
                || this.candidate.originY != scene.originY()
                || this.candidate.originZ != scene.originZ();
        if (unrelated) {
            transitionAndClearAll(commandBuffer, this.candidate.initialized);
            this.candidate.reset(
                    scene.resetRevision(),
                    scene.occluderRevision(),
                    scene.originX(),
                    scene.originY(),
                    scene.originZ());
            startBank(this.candidate.banks[0], input.sunDirection(), input, scene);
            wrote = true;
        } else if (scene.occluderRevision() != this.candidate.occluderRevision) {
            if (scene.occluderRevision() == this.candidate.occluderRevision + 1L) {
                wrote |= invalidateChanges(
                        commandBuffer,
                        pipeline,
                        input,
                        scene,
                        scene.occluderChanges());
                this.candidate.occluderRevision = scene.occluderRevision();
            } else {
                transitionAndClearAll(commandBuffer, true);
                this.candidate.reset(
                        scene.resetRevision(),
                        scene.occluderRevision(),
                        scene.originX(),
                        scene.originY(),
                        scene.originZ());
                startBank(this.candidate.banks[0], input.sunDirection(), input, scene);
                wrote = true;
            }
        }

        for (int bankIndex = 0; bankIndex < BANK_COUNT; bankIndex++) {
            Bank bank = this.candidate.banks[bankIndex];
            if (bank.valid) {
                wrote |= scrollBank(
                        commandBuffer, pipeline, input, scene, bankIndex, bank);
            }
        }

        Bank active = this.candidate.banks[this.candidate.activeBank];
        if (forceComplete
                && active.valid
                && (!sameDirection(active.direction, input.sunDirection())
                        || !active.complete)) {
            clearBank(commandBuffer, this.candidate.activeBank, true);
            startBank(active, input.sunDirection(), input, scene);
            wrote = true;
        }
        int inactiveIndex = this.candidate.activeBank ^ 1;
        Bank inactive = this.candidate.banks[inactiveIndex];
        if (!forceComplete
                && active.valid
                && active.complete
                && (!inactive.valid || inactive.complete)
                && directionCosine(active.direction, input.sunDirection())
                        < DIRECTION_REBUILD_COSINE) {
            clearBank(commandBuffer, inactiveIndex, true);
            startBank(inactive, input.sunDirection(), input, scene);
            wrote = true;
        }

        int buildingIndex = buildingBank(this.candidate);
        if (buildingIndex >= 0) {
            Bank building = this.candidate.banks[buildingIndex];
            int tileBudget = forceComplete
                    ? TILE_COUNT - building.primaryTile
                    : (buildingIndex == this.candidate.activeBank
                                    && building.primaryTile == 0
                            ? 4
                            : 1);
            for (int tile = 0;
                    tile < tileBudget && building.primaryTile < TILE_COUNT;
                    tile++) {
                wrote |= buildPrimaryTile(
                        commandBuffer,
                        pipeline,
                        input,
                        scene,
                        buildingIndex,
                        building);
            }
        }

        for (int bankIndex = 0; bankIndex < BANK_COUNT; bankIndex++) {
            Bank bank = this.candidate.banks[bankIndex];
            if (readyForDirtyRepair(bank.valid, bank.primaryTile)) {
                do {
                    wrote |= buildOneDirtyTilePerCascade(
                            commandBuffer,
                            pipeline,
                            input,
                            scene,
                            bankIndex,
                            bank);
                } while (forceComplete && hasDirtyTiles(bank));
                if (!bank.complete && !hasDirtyTiles(bank)) {
                    bank.complete = true;
                    if (bankIndex != this.candidate.activeBank) {
                        this.candidate.activeBank = bankIndex;
                    }
                }
            }
        }

        if (wrote) {
            rayWriteToShaderRead(commandBuffer);
            this.candidate.contentVersion++;
            this.pending = true;
        }
        return wrote;
    }

    void submitted() {
        if (!this.pending) {
            return;
        }
        this.committed.copyFrom(this.candidate);
        if (this.committed.initialized) {
            for (VulkanImage depth : this.depths) {
                depth.markInitialized();
            }
        }
        this.pending = false;
    }

    void abandon() {
        this.pending = false;
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            for (int index = this.depths.length - 1; index >= 0; index--) {
                this.depths[index].destroy();
            }
        }
    }

    static float texelSize(int cascade) {
        return TEXEL_SIZES[cascade];
    }

    static float cascadeRadius(int cascade) {
        return 0.5F * RESOLUTION * texelSize(cascade);
    }

    static int cascadeForProjectedDistance(float distance) {
        float finiteDistance = Float.isFinite(distance)
                ? Math.max(distance, 0.0F)
                : Float.POSITIVE_INFINITY;
        for (int cascade = 0; cascade < CASCADE_COUNT; cascade++) {
            if (finiteDistance <= cascadeRadius(cascade)) {
                return cascade;
            }
        }
        return -1;
    }

    static boolean conservativeVisibility(float pointDepth, float blockerDepth) {
        return Float.isFinite(pointDepth)
                && pointDepth + 0.02F >= blockerDepth;
    }

    static int primaryTileForBuild(int buildIndex) {
        if (buildIndex < 0 || buildIndex >= TILE_COUNT) {
            throw new IllegalArgumentException("Invalid primary sun-shadow tile");
        }
        return PRIMARY_TILE_ORDER[buildIndex];
    }

    static boolean readyForDirtyRepair(boolean valid, int primaryTile) {
        return valid && primaryTile == TILE_COUNT;
    }

    static boolean deferInvalidation(int bank, int activeBank) {
        return bank != activeBank;
    }

    static float basisDirectionCosine(
            SunDirection first, SunDirection second) {
        Basis firstBasis = basis(first);
        Basis secondBasis = basis(second);
        return firstBasis.ux * secondBasis.ux
                + firstBasis.uy * secondBasis.uy
                + firstBasis.uz * secondBasis.uz;
    }

    private boolean invalidateChanges(
            VkCommandBuffer commandBuffer,
            SunShadowPipeline pipeline,
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene,
            List<TerrainOccluderChange> changes) {
        if (changes.isEmpty()) {
            return false;
        }
        boolean wrote = false;
        for (int bankIndex = 0; bankIndex < BANK_COUNT; bankIndex++) {
            Bank bank = this.candidate.banks[bankIndex];
            if (!bank.valid) {
                continue;
            }
            Basis basis = basis(bank.direction);
            boolean defer = deferInvalidation(
                    bankIndex, this.candidate.activeBank);
            for (int cascade = 0; cascade < CASCADE_COUNT; cascade++) {
                for (Region dirty : projectChanges(
                        changes, scene, basis, bank, cascade)) {
                    if (defer) {
                        // Inactive banks are never sampled and can coarsen changes to their
                        // existing tile repair budget without exposing stale visibility.
                        markDirtyTiles(bank, cascade, dirty);
                        wrote = true;
                        continue;
                    }
                    traceRegion(
                            commandBuffer,
                            pipeline,
                            input,
                            scene,
                            bankIndex,
                            bank,
                            cascade,
                            dirty);
                    // Never expose UNKNOWN through the published bank. The new TLAS is already
                    // resident, so replace the exact affected light columns in this frame.
                    rayWriteToRayWrite(commandBuffer, depth(bankIndex, cascade));
                    wrote = true;
                }
            }
        }
        return wrote;
    }

    private List<Region> projectChanges(
            List<TerrainOccluderChange> changes,
            TerrainScene.ResidentSceneView scene,
            Basis basis,
            Bank bank,
            int cascade) {
        List<Region> regions = new ArrayList<>();
        for (TerrainOccluderChange change : changes) {
            Region region = projectChange(change, scene, basis, bank, cascade);
            if (!region.empty()) {
                addMergedRegion(regions, region);
            }
        }
        return regions;
    }

    private static void addMergedRegion(List<Region> regions, Region added) {
        Region merged = added;
        for (int index = 0; index < regions.size();) {
            Region existing = regions.get(index);
            if (merged.touches(existing)) {
                merged = merged.union(existing);
                regions.remove(index);
                index = 0;
            } else {
                index++;
            }
        }
        regions.add(merged);
    }

    private boolean scrollBank(
            VkCommandBuffer commandBuffer,
            SunShadowPipeline pipeline,
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene,
            int bankIndex,
            Bank bank) {
        Basis basis = basis(bank.direction);
        float cameraX = (float) (input.camera().renderX() - scene.originX());
        float cameraY = (float) (input.camera().renderY() - scene.originY());
        float cameraZ = (float) (input.camera().renderZ() - scene.originZ());
        boolean wrote = false;
        for (int cascade = 0; cascade < CASCADE_COUNT; cascade++) {
            float texelSize = texelSize(cascade);
            int newMinimumU = minimumCell(
                    dot(cameraX, cameraY, cameraZ, basis.ux, basis.uy, basis.uz),
                    texelSize);
            int newMinimumV = minimumCell(
                    dot(cameraX, cameraY, cameraZ, basis.vx, basis.vy, basis.vz),
                    texelSize);
            int deltaU = newMinimumU - bank.minimumU[cascade];
            int deltaV = newMinimumV - bank.minimumV[cascade];
            if (deltaU == 0 && deltaV == 0) {
                continue;
            }
            bank.minimumU[cascade] = newMinimumU;
            bank.minimumV[cascade] = newMinimumV;
            if (Math.abs(deltaU) >= RESOLUTION || Math.abs(deltaV) >= RESOLUTION) {
                clearBank(commandBuffer, bankIndex, true);
                restartBank(bank, input, scene);
                return true;
            }
            if (deltaU != 0) {
                Region strip = deltaU > 0
                        ? new Region(RESOLUTION - deltaU, 0, deltaU, RESOLUTION)
                        : new Region(0, 0, -deltaU, RESOLUTION);
                traceRegion(
                        commandBuffer,
                        pipeline,
                        input,
                        scene,
                        bankIndex,
                        bank,
                        cascade,
                        strip);
                wrote = true;
            }
            if (deltaV != 0) {
                int x = deltaU < 0 ? -deltaU : 0;
                int width = RESOLUTION - Math.abs(deltaU);
                Region strip = deltaV > 0
                        ? new Region(x, RESOLUTION - deltaV, width, deltaV)
                        : new Region(x, 0, width, -deltaV);
                traceRegion(
                        commandBuffer,
                        pipeline,
                        input,
                        scene,
                        bankIndex,
                        bank,
                        cascade,
                        strip);
                wrote = true;
            }
        }
        return wrote;
    }

    private boolean buildPrimaryTile(
            VkCommandBuffer commandBuffer,
            SunShadowPipeline pipeline,
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene,
            int bankIndex,
            Bank bank) {
        if (bank.primaryTile >= TILE_COUNT) {
            return false;
        }
        int tile = primaryTileForBuild(bank.primaryTile++);
        Region region = tileRegion(tile);
        for (int cascade = 0; cascade < CASCADE_COUNT; cascade++) {
            traceRegion(
                    commandBuffer,
                    pipeline,
                    input,
                    scene,
                    bankIndex,
                    bank,
                    cascade,
                    region);
            bank.dirtyTiles[cascade] &= ~(1 << tile);
        }
        return true;
    }

    private boolean buildOneDirtyTilePerCascade(
            VkCommandBuffer commandBuffer,
            SunShadowPipeline pipeline,
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene,
            int bankIndex,
            Bank bank) {
        boolean wrote = false;
        for (int cascade = 0; cascade < CASCADE_COUNT; cascade++) {
            int mask = bank.dirtyTiles[cascade];
            if (mask == 0) {
                continue;
            }
            int tile = Integer.numberOfTrailingZeros(mask);
            traceRegion(
                    commandBuffer,
                    pipeline,
                    input,
                    scene,
                    bankIndex,
                    bank,
                    cascade,
                    tileRegion(tile));
            bank.dirtyTiles[cascade] &= ~(1 << tile);
            wrote = true;
        }
        return wrote;
    }

    private void traceRegion(
            VkCommandBuffer commandBuffer,
            SunShadowPipeline pipeline,
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene,
            int bankIndex,
            Bank bank,
            int cascade,
            Region region) {
        if (region.empty()) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = RayTracingPushConstants.encode(stack, input, scene);
            Basis basis = basis(bank.direction);
            float texelSize = texelSize(cascade);
            float cameraX = (float) (input.camera().renderX() - scene.originX());
            float cameraY = (float) (input.camera().renderY() - scene.originY());
            float cameraZ = (float) (input.camera().renderZ() - scene.originZ());
            float cameraDepth = dot(
                    cameraX,
                    cameraY,
                    cameraZ,
                    bank.direction.x(),
                    bank.direction.y(),
                    bank.direction.z());
            float minimumU = bank.minimumU[cascade] * texelSize;
            float minimumV = bank.minimumV[cascade] * texelSize;
            float planeX = basis.ux * minimumU
                    + basis.vx * minimumV
                    + bank.direction.x() * (cameraDepth + HALF_TRACE_DISTANCE_METERS);
            float planeY = basis.uy * minimumU
                    + basis.vy * minimumV
                    + bank.direction.y() * (cameraDepth + HALF_TRACE_DISTANCE_METERS);
            float planeZ = basis.uz * minimumU
                    + basis.vz * minimumV
                    + bank.direction.z() * (cameraDepth + HALF_TRACE_DISTANCE_METERS);
            putVector(push, 0, basis.ux, basis.uy, basis.uz, texelSize);
            putVector(
                    push,
                    16,
                    basis.vx,
                    basis.vy,
                    basis.vz,
                    HALF_TRACE_DISTANCE_METERS);
            putVector(push, 32, planeX, planeY, planeZ, 0.0F);
            push.putInt(ShaderAbi.PUSH_OUTPUT_EXTENT_OFFSET, region.width);
            push.putInt(
                    ShaderAbi.PUSH_OUTPUT_EXTENT_OFFSET + Integer.BYTES,
                    region.height);
            int sunOffset = ShaderAbi.PUSH_SUN_DIRECTION_OFFSET;
            push.putFloat(sunOffset, bank.direction.x());
            push.putFloat(sunOffset + Float.BYTES, bank.direction.y());
            push.putFloat(sunOffset + 2 * Float.BYTES, bank.direction.z());
            // A view-derived ray cone would make cutout opacity change when the camera or FOV
            // changes without invalidating the corresponding world texel. LOD zero keeps the
            // cached visibility a function only of geometry, texture contents and sun direction.
            push.putInt(ShaderAbi.PUSH_RAY_CONE_OFFSET, 0);
            int pathOffset = ShaderAbi.PUSH_PATH_OFFSET;
            push.putInt(pathOffset, region.x);
            push.putInt(pathOffset + Integer.BYTES, region.y);
            push.putInt(
                    pathOffset + 2 * Integer.BYTES,
                    imageIndex(bankIndex, cascade));
            int storageOffset = Math.floorMod(bank.minimumU[cascade], RESOLUTION)
                    | (Math.floorMod(bank.minimumV[cascade], RESOLUTION) << 9);
            push.putInt(pathOffset + 3 * Integer.BYTES, storageOffset);
            pipeline.trace(
                    commandBuffer,
                    push.position(0).limit(ShaderAbi.PUSH_CONSTANT_SIZE),
                    region.width,
                    region.height);
        }
    }

    private Region projectChange(
            TerrainOccluderChange change,
            TerrainScene.ResidentSceneView scene,
            Basis basis,
            Bank bank,
            int cascade) {
        double minX = change.minimumX() - (double) scene.originX();
        double minY = change.minimumY() - (double) scene.originY();
        double minZ = change.minimumZ() - (double) scene.originZ();
        double maxX = change.maximumX() - (double) scene.originX();
        double maxY = change.maximumY() - (double) scene.originY();
        double maxZ = change.maximumZ() - (double) scene.originZ();
        double minimumU = projectedMinimum(
                minX, minY, minZ, maxX, maxY, maxZ, basis.ux, basis.uy, basis.uz);
        double maximumU = projectedMaximum(
                minX, minY, minZ, maxX, maxY, maxZ, basis.ux, basis.uy, basis.uz);
        double minimumV = projectedMinimum(
                minX, minY, minZ, maxX, maxY, maxZ, basis.vx, basis.vy, basis.vz);
        double maximumV = projectedMaximum(
                minX, minY, minZ, maxX, maxY, maxZ, basis.vx, basis.vy, basis.vz);
        float texelSize = texelSize(cascade);
        int x0 = (int) Math.floor(minimumU / texelSize) - bank.minimumU[cascade] - 1;
        int x1 = (int) Math.ceil(maximumU / texelSize) - bank.minimumU[cascade] + 1;
        int y0 = (int) Math.floor(minimumV / texelSize) - bank.minimumV[cascade] - 1;
        int y1 = (int) Math.ceil(maximumV / texelSize) - bank.minimumV[cascade] + 1;
        int clippedX0 = Math.max(x0, 0);
        int clippedY0 = Math.max(y0, 0);
        int clippedX1 = Math.min(x1, RESOLUTION);
        int clippedY1 = Math.min(y1, RESOLUTION);
        return new Region(
                clippedX0,
                clippedY0,
                Math.max(clippedX1 - clippedX0, 0),
                Math.max(clippedY1 - clippedY0, 0));
    }

    private void transitionAndClearAll(VkCommandBuffer commandBuffer, boolean initialized) {
        transitionForClear(commandBuffer, this.depths, initialized);
        clearImages(commandBuffer, this.depths);
        transferWriteToShaderAccess(commandBuffer, this.depths);
    }

    private void clearBank(
            VkCommandBuffer commandBuffer, int bank, boolean initialized) {
        VulkanImage[] bankImages = new VulkanImage[CASCADE_COUNT];
        for (int cascade = 0; cascade < CASCADE_COUNT; cascade++) {
            bankImages[cascade] = depth(bank, cascade);
        }
        transitionForClear(commandBuffer, bankImages, initialized);
        clearImages(commandBuffer, bankImages);
        transferWriteToShaderAccess(commandBuffer, bankImages);
    }

    private void transitionForClear(
            VkCommandBuffer commandBuffer,
            VulkanImage[] images,
            boolean initialized) {
        VulkanSync.imageBarriers(commandBuffer, images,
                initialized ? VK12.VK_IMAGE_LAYOUT_GENERAL : VK12.VK_IMAGE_LAYOUT_UNDEFINED,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                initialized ? COMPUTE_AND_RAY_STAGES : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                initialized
                        ? VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT
                        : 0L,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
    }

    private static void clearImages(
            VkCommandBuffer commandBuffer, VulkanImage[] images) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearColorValue color = VkClearColorValue.calloc(stack);
            color.float32(0, UNKNOWN_DEPTH);
            color.float32(1, 0.0F);
            color.float32(2, 0.0F);
            color.float32(3, 0.0F);
            VkImageSubresourceRange.Buffer range =
                    VkImageSubresourceRange.calloc(1, stack)
                            .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0)
                            .levelCount(1)
                            .baseArrayLayer(0)
                            .layerCount(1);
            for (VulkanImage image : images) {
                VK12.vkCmdClearColorImage(
                        commandBuffer,
                        image.image(),
                        VK12.VK_IMAGE_LAYOUT_GENERAL,
                        color,
                        range);
            }
        }
    }

    private static void transferWriteToShaderAccess(
            VkCommandBuffer commandBuffer, VulkanImage[] images) {
        VulkanSync.imageBarriers(commandBuffer, images,
                VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                COMPUTE_AND_RAY_STAGES,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private void rayWriteToShaderRead(VkCommandBuffer commandBuffer) {
        VulkanSync.imageBarriers(commandBuffer, this.depths,
                VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_WRITE_BIT, COMPUTE_AND_RAY_STAGES,
                VK12.VK_ACCESS_SHADER_READ_BIT);
    }

    private static void rayWriteToRayWrite(
            VkCommandBuffer commandBuffer, VulkanImage image) {
        VulkanSync.imageBarrier(commandBuffer, image.image(),
                VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private void shaderAccessToRayWrite(VkCommandBuffer commandBuffer) {
        VulkanSync.imageBarriers(commandBuffer, this.depths,
                VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                COMPUTE_AND_RAY_STAGES,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private static void startBank(
            Bank bank,
            SunDirection direction,
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene) {
        bank.valid = true;
        bank.complete = false;
        bank.direction = direction;
        bank.primaryTile = 0;
        java.util.Arrays.fill(bank.dirtyTiles, 0);
        initializeMinimumCells(bank, input, scene);
    }

    private static void restartBank(
            Bank bank,
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene) {
        bank.complete = false;
        bank.primaryTile = 0;
        java.util.Arrays.fill(bank.dirtyTiles, 0);
        initializeMinimumCells(bank, input, scene);
    }

    private static void initializeMinimumCells(
            Bank bank,
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene) {
        Basis basis = basis(bank.direction);
        float cameraX = (float) (input.camera().renderX() - scene.originX());
        float cameraY = (float) (input.camera().renderY() - scene.originY());
        float cameraZ = (float) (input.camera().renderZ() - scene.originZ());
        float cameraU = dot(cameraX, cameraY, cameraZ, basis.ux, basis.uy, basis.uz);
        float cameraV = dot(cameraX, cameraY, cameraZ, basis.vx, basis.vy, basis.vz);
        for (int cascade = 0; cascade < CASCADE_COUNT; cascade++) {
            float texelSize = texelSize(cascade);
            bank.minimumU[cascade] = minimumCell(cameraU, texelSize);
            bank.minimumV[cascade] = minimumCell(cameraV, texelSize);
        }
    }

    private static int minimumCell(float cameraCoordinate, float texelSize) {
        return Math.toIntExact((long) Math.floor(cameraCoordinate / texelSize)
                - RESOLUTION / 2L);
    }

    private static int buildingBank(State state) {
        for (int bank = 0; bank < BANK_COUNT; bank++) {
            if (state.banks[bank].valid && !state.banks[bank].complete) {
                return bank;
            }
        }
        return -1;
    }

    private static boolean hasDirtyTiles(Bank bank) {
        for (int mask : bank.dirtyTiles) {
            if (mask != 0) {
                return true;
            }
        }
        return false;
    }

    private static Region tileRegion(int tile) {
        int tileX = tile % (RESOLUTION / TILE_SIZE);
        int tileY = tile / (RESOLUTION / TILE_SIZE);
        return new Region(
                tileX * TILE_SIZE,
                tileY * TILE_SIZE,
                TILE_SIZE,
                TILE_SIZE);
    }

    private static void markDirtyTiles(Bank bank, int cascade, Region region) {
        int firstX = region.x / TILE_SIZE;
        int firstY = region.y / TILE_SIZE;
        int lastX = (region.x + region.width - 1) / TILE_SIZE;
        int lastY = (region.y + region.height - 1) / TILE_SIZE;
        for (int y = firstY; y <= lastY; y++) {
            for (int x = firstX; x <= lastX; x++) {
                bank.dirtyTiles[cascade] |= 1 << (y * (RESOLUTION / TILE_SIZE) + x);
            }
        }
    }

    private static Basis basis(SunDirection direction) {
        float ux;
        float uy;
        float uz;
        // Frisvad's orthonormal basis, permuted so its unavoidable seam is at world down. The
        // astronomical sun can cross ±X, so an axis-selection branch would flip the cache there.
        if (direction.y() < -0.9999999F) {
            ux = 1.0F;
            uy = 0.0F;
            uz = 0.0F;
        } else {
            float reciprocal = 1.0F / (1.0F + direction.y());
            ux = 1.0F
                    - direction.x() * direction.x() * reciprocal;
            uy = -direction.x();
            uz = -direction.x() * direction.z() * reciprocal;
        }
        float inverseLength =
                1.0F / (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux *= inverseLength;
        uy *= inverseLength;
        uz *= inverseLength;
        float vx = direction.y() * uz - direction.z() * uy;
        float vy = direction.z() * ux - direction.x() * uz;
        float vz = direction.x() * uy - direction.y() * ux;
        return new Basis(ux, uy, uz, vx, vy, vz);
    }

    private static float directionCosine(SunDirection first, SunDirection second) {
        return first.x() * second.x()
                + first.y() * second.y()
                + first.z() * second.z();
    }

    private static boolean sameDirection(
            SunDirection first, SunDirection second) {
        return Float.floatToIntBits(first.x()) == Float.floatToIntBits(second.x())
                && Float.floatToIntBits(first.y()) == Float.floatToIntBits(second.y())
                && Float.floatToIntBits(first.z()) == Float.floatToIntBits(second.z());
    }

    private static float dot(
            float ax,
            float ay,
            float az,
            float bx,
            float by,
            float bz) {
        return ax * bx + ay * by + az * bz;
    }

    private static double projectedMinimum(
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            float x,
            float y,
            float z) {
        return (x >= 0.0F ? minX : maxX) * x
                + (y >= 0.0F ? minY : maxY) * y
                + (z >= 0.0F ? minZ : maxZ) * z;
    }

    private static double projectedMaximum(
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            float x,
            float y,
            float z) {
        return (x >= 0.0F ? maxX : minX) * x
                + (y >= 0.0F ? maxY : minY) * y
                + (z >= 0.0F ? maxZ : minZ) * z;
    }

    private static void putVector(
            ByteBuffer buffer,
            int offset,
            float x,
            float y,
            float z,
            float w) {
        buffer.order(ByteOrder.nativeOrder())
                .putFloat(offset, x)
                .putFloat(offset + Float.BYTES, y)
                .putFloat(offset + 2 * Float.BYTES, z)
                .putFloat(offset + 3 * Float.BYTES, w);
    }

    private static int imageIndex(int bank, int cascade) {
        if (bank < 0 || bank >= BANK_COUNT
                || cascade < 0 || cascade >= CASCADE_COUNT) {
            throw new IllegalArgumentException("Invalid sun-shadow bank or cascade");
        }
        return bank * CASCADE_COUNT + cascade;
    }

    private static final class State {
        private final Bank[] banks = {new Bank(), new Bank()};
        private boolean initialized;
        private int activeBank;
        private int originX;
        private int originY;
        private int originZ;
        private long resetRevision;
        private long occluderRevision;
        private int contentVersion;

        private void reset(
                long nextResetRevision,
                long nextOccluderRevision,
                int nextOriginX,
                int nextOriginY,
                int nextOriginZ) {
            this.initialized = true;
            this.activeBank = 0;
            this.originX = nextOriginX;
            this.originY = nextOriginY;
            this.originZ = nextOriginZ;
            this.resetRevision = nextResetRevision;
            this.occluderRevision = nextOccluderRevision;
            for (Bank bank : this.banks) {
                bank.reset();
            }
        }

        private void copyFrom(State source) {
            this.initialized = source.initialized;
            this.activeBank = source.activeBank;
            this.originX = source.originX;
            this.originY = source.originY;
            this.originZ = source.originZ;
            this.resetRevision = source.resetRevision;
            this.occluderRevision = source.occluderRevision;
            this.contentVersion = source.contentVersion;
            for (int index = 0; index < this.banks.length; index++) {
                this.banks[index].copyFrom(source.banks[index]);
            }
        }
    }

    private static final class Bank {
        private final int[] minimumU = new int[CASCADE_COUNT];
        private final int[] minimumV = new int[CASCADE_COUNT];
        private final int[] dirtyTiles = new int[CASCADE_COUNT];
        private SunDirection direction = new SunDirection(0.0F, 1.0F, 0.0F);
        private int primaryTile;
        private boolean valid;
        private boolean complete;

        private void reset() {
            this.valid = false;
            this.complete = false;
            this.primaryTile = 0;
            java.util.Arrays.fill(this.minimumU, 0);
            java.util.Arrays.fill(this.minimumV, 0);
            java.util.Arrays.fill(this.dirtyTiles, 0);
        }

        private void copyFrom(Bank source) {
            this.direction = source.direction;
            this.primaryTile = source.primaryTile;
            this.valid = source.valid;
            this.complete = source.complete;
            System.arraycopy(
                    source.minimumU, 0, this.minimumU, 0, CASCADE_COUNT);
            System.arraycopy(
                    source.minimumV, 0, this.minimumV, 0, CASCADE_COUNT);
            System.arraycopy(
                    source.dirtyTiles, 0, this.dirtyTiles, 0, CASCADE_COUNT);
        }
    }

    private record Basis(
            float ux, float uy, float uz, float vx, float vy, float vz) {
    }

    private record Region(int x, int y, int width, int height) {
        private boolean empty() {
            return this.width <= 0 || this.height <= 0;
        }

        private boolean touches(Region other) {
            return this.x <= other.x + other.width
                    && other.x <= this.x + this.width
                    && this.y <= other.y + other.height
                    && other.y <= this.y + this.height;
        }

        private Region union(Region other) {
            int minimumX = Math.min(this.x, other.x);
            int minimumY = Math.min(this.y, other.y);
            int maximumX = Math.max(this.x + this.width, other.x + other.width);
            int maximumY = Math.max(this.y + this.height, other.y + other.height);
            return new Region(
                    minimumX,
                    minimumY,
                    maximumX - minimumX,
                    maximumY - minimumY);
        }
    }
}
