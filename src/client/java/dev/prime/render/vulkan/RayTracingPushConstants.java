package dev.prime.render.vulkan;

import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.AtmosphereCoordinates;
import dev.prime.render.IntegratorSettings;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryStack;

/** Deterministic ABI encoder that binds semantic input to one resident scene. */
public final class RayTracingPushConstants {
    private RayTracingPushConstants() {
    }

    public static ByteBuffer encode(
            MemoryStack stack,
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene) {
        ByteBuffer buffer =
                stack.malloc(ShaderAbi.PUSH_CONSTANT_SIZE).order(ByteOrder.nativeOrder());
        write(input, scene, buffer);
        return buffer.position(0).limit(ShaderAbi.PUSH_CONSTANT_SIZE);
    }

    public static void write(
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene,
            ByteBuffer buffer) {
        if (buffer.capacity() < ShaderAbi.PUSH_CONSTANT_SIZE) {
            throw new IllegalArgumentException("Ray-tracing push-constant buffer is too small");
        }
        input.camera().inverseViewProjection().get(
                ShaderAbi.PUSH_INVERSE_VIEW_PROJECTION_OFFSET, buffer);
        int cameraOffset = ShaderAbi.PUSH_CAMERA_POSITION_OFFSET;
        buffer.putFloat(
                cameraOffset,
                (float) (input.camera().renderX() - scene.originX()));
        buffer.putFloat(
                cameraOffset + Float.BYTES,
                (float) (input.camera().renderY() - scene.originY()));
        buffer.putFloat(
                cameraOffset + 2 * Float.BYTES,
                (float) (input.camera().renderZ() - scene.originZ()));
        buffer.putFloat(
                ShaderAbi.PUSH_ATMOSPHERE_EYE_RADIUS_KM_OFFSET,
                AtmosphereCoordinates.eyeRadiusKm(input.camera().y()));
        buffer.putLong(
                ShaderAbi.PUSH_SECTION_TABLE_ADDRESS_OFFSET,
                scene.sectionTableAddress());
        buffer.putInt(ShaderAbi.PUSH_OUTPUT_EXTENT_OFFSET, input.width());
        buffer.putInt(
                ShaderAbi.PUSH_OUTPUT_EXTENT_OFFSET + Integer.BYTES,
                input.height());
        int sunOffset = ShaderAbi.PUSH_SUN_DIRECTION_OFFSET;
        buffer.putFloat(sunOffset, input.sunDirection().x());
        buffer.putFloat(sunOffset + Float.BYTES, input.sunDirection().y());
        buffer.putFloat(sunOffset + 2 * Float.BYTES, input.sunDirection().z());
        buffer.putInt(ShaderAbi.PUSH_RAY_CONE_OFFSET, input.packedRayCone());
        int pathOffset = ShaderAbi.PUSH_PATH_OFFSET;
        buffer.putInt(
                pathOffset,
                IntegratorSettings.packSampleControl(
                        input.sampleIndex(),
                        input.astronomy().settings(),
                        input.material().seamlessGlass(),
                        input.material().airGap(),
                        input.material().vanillaPbrPresets(),
                        input.lighting().transparentNeeMode()));
        buffer.putInt(
                pathOffset + Integer.BYTES,
                IntegratorSettings.packSampleEpoch(
                        input.sampleEpoch(), input.historyValid()));
        buffer.putInt(
                pathOffset + 2 * Integer.BYTES,
                IntegratorSettings.packPathControl(
                        input.maximumBounces(),
                        input.jitterPhase(),
                        input.astronomy().settings(),
                        input.cameraInWater(),
                        input.transparentGuideMode()));
        buffer.putInt(
                pathOffset + 3 * Integer.BYTES,
                IntegratorSettings.packMaterialLightingControl(
                        input.lighting().sunQuarterSteps(),
                        input.lighting().starQuarterSteps(),
                        input.lighting().blockLightQuarterSteps(),
                        input.material().roughnessSteps(),
                        input.shInput()));
    }
}
