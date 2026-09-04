package dev.prime.render.vulkan;

import dev.prime.render.FrameCamera;
import dev.prime.render.RayConeParameters;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryStack;

/** Minimal semantic encoder for the primary-ray renderer's shared trace ABI. */
final class PrimaryRayPushConstants {
    private PrimaryRayPushConstants() {}

    static ByteBuffer encode(
            MemoryStack stack,
            FrameCamera camera,
            int width,
            int height,
            TerrainScene.ResidentSceneView scene) {
        ByteBuffer buffer = stack.calloc(ShaderAbi.PUSH_CONSTANT_SIZE)
                .order(ByteOrder.nativeOrder());
        camera.inverseViewProjection().get(
                ShaderAbi.PUSH_INVERSE_VIEW_PROJECTION_OFFSET, buffer);
        int cameraOffset = ShaderAbi.PUSH_CAMERA_POSITION_OFFSET;
        buffer.putFloat(cameraOffset, (float) (camera.renderX() - scene.originX()));
        buffer.putFloat(
                cameraOffset + Float.BYTES,
                (float) (camera.renderY() - scene.originY()));
        buffer.putFloat(
                cameraOffset + 2 * Float.BYTES,
                (float) (camera.renderZ() - scene.originZ()));
        buffer.putLong(
                ShaderAbi.PUSH_SECTION_TABLE_ADDRESS_OFFSET,
                scene.sectionTableAddress());
        buffer.putInt(ShaderAbi.PUSH_OUTPUT_EXTENT_OFFSET, width);
        buffer.putInt(ShaderAbi.PUSH_OUTPUT_EXTENT_OFFSET + Integer.BYTES, height);
        RayConeParameters rayCone = RayConeParameters.fromProjection(
                camera.projection().m00(), camera.projection().m11(), width, height, 0.0F);
        buffer.putInt(
                ShaderAbi.PUSH_RAY_CONE_OFFSET,
                RayTracingPushConstants.packRayCone(rayCone));
        return buffer.position(0).limit(ShaderAbi.PUSH_CONSTANT_SIZE);
    }
}
