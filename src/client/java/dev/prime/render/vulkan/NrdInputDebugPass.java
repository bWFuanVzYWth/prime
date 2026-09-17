// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import dev.prime.render.diagnostic.NrdInputView;
import dev.prime.render.vulkan.nrd.PreparedNrdFrame;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Diagnostic-only one-to-one presentation of both REBLUR instances and SIGMA inputs. */
final class NrdInputDebugPass implements Destroyable {
    private static final int OUTPUT = 0;
    private static final int PRIMARY_MOTION = 1;
    private static final int PRIMARY_NORMAL_ROUGHNESS = 2;
    private static final int PRIMARY_VIEW_Z = 3;
    private static final int PRIMARY_DIFFUSE_SH0 = 4;
    private static final int PRIMARY_DIFFUSE_SH1 = 5;
    private static final int PRIMARY_SPECULAR_SH0 = 6;
    private static final int PRIMARY_SPECULAR_SH1 = 7;
    private static final int REFLECTION_MOTION = 8;
    private static final int REFLECTION_NORMAL_ROUGHNESS = 9;
    private static final int REFLECTION_VIEW_Z = 10;
    private static final int REFLECTION_DIFFUSE_SH0 = 11;
    private static final int REFLECTION_DIFFUSE_SH1 = 12;
    private static final int REFLECTION_SPECULAR_SH0 = 13;
    private static final int REFLECTION_SPECULAR_SH1 = 14;
    private static final int SUN_PENUMBRA = 15;

    private final ImageDiagnosticPass.OutputPair outputs;

    private NrdInputDebugPass(ImageDiagnosticPass.OutputPair outputs) {
        this.outputs = outputs;
    }

    static NrdInputDebugPass create(
            VulkanContext context,
            VulkanImage sceneColor,
            PreparedNrdFrame prepared,
            VulkanImage displayOutput,
            VulkanImage hdrOutput) {
        PreparedNrdFrame.Branch primary = prepared.primary();
        PreparedNrdFrame.Branch reflection = prepared.reflection();
        VulkanImage[] sources = {
            sceneColor,
            primary.motion(),
            primary.normalRoughness(),
            primary.viewZ(),
            primary.noisyDiffuse(),
            primary.noisyDiffuseSh1(),
            primary.noisySpecular(),
            primary.noisySpecularSh1(),
            reflection.motion(),
            reflection.normalRoughness(),
            reflection.viewZ(),
            reflection.noisyDiffuse(),
            reflection.noisyDiffuseSh1(),
            reflection.noisySpecular(),
            reflection.noisySpecularSh1(),
            prepared.sunPenumbra()
        };
        return new NrdInputDebugPass(
                ImageDiagnosticPass.OutputPair.create(
                        context, displayOutput, hdrOutput, sources));
    }

    void record(VkCommandBuffer commandBuffer, NrdInputView view) {
        switch (view) {
            case OFF -> {
                return;
            }
            case PRIMARY_GRID -> recordGrid(commandBuffer, 3, primaryGrid());
            case REFLECTION_GRID -> recordGrid(commandBuffer, 3, reflectionGrid());
            case SIGMA_GRID -> recordGrid(commandBuffer, 2, sigmaGrid());
            default -> recordFull(commandBuffer, descriptor(view));
        }
    }

    private void recordGrid(
            VkCommandBuffer commandBuffer,
            int columns,
            List<ImageDiagnosticPass.View> views) {
        this.outputs.sdr().recordGrid(commandBuffer, columns, views);
        this.outputs.hdr().recordGrid(commandBuffer, columns, views);
    }

    private void recordFull(
            VkCommandBuffer commandBuffer, ImageDiagnosticPass.View view) {
        this.outputs.sdr().recordFull(commandBuffer, view);
        this.outputs.hdr().recordFull(commandBuffer, view);
    }

    static List<ImageDiagnosticPass.View> primaryGrid() {
        return List.of(
                descriptor(NrdInputView.DENOISED_OUTPUT),
                descriptor(NrdInputView.PRIMARY_MOTION),
                descriptor(NrdInputView.PRIMARY_NORMAL_ROUGHNESS),
                descriptor(NrdInputView.PRIMARY_VIEW_Z),
                descriptor(NrdInputView.PRIMARY_DIFFUSE_SH0),
                descriptor(NrdInputView.PRIMARY_DIFFUSE_SH1),
                descriptor(NrdInputView.PRIMARY_SPECULAR_SH0),
                descriptor(NrdInputView.PRIMARY_SPECULAR_SH1));
    }

    static List<ImageDiagnosticPass.View> reflectionGrid() {
        return List.of(
                descriptor(NrdInputView.DENOISED_OUTPUT),
                descriptor(NrdInputView.REFLECTION_MOTION),
                descriptor(NrdInputView.REFLECTION_NORMAL_ROUGHNESS),
                descriptor(NrdInputView.REFLECTION_VIEW_Z),
                descriptor(NrdInputView.REFLECTION_DIFFUSE_SH0),
                descriptor(NrdInputView.REFLECTION_DIFFUSE_SH1),
                descriptor(NrdInputView.REFLECTION_SPECULAR_SH0),
                descriptor(NrdInputView.REFLECTION_SPECULAR_SH1));
    }

    static List<ImageDiagnosticPass.View> sigmaGrid() {
        return List.of(
                descriptor(NrdInputView.DENOISED_OUTPUT),
                descriptor(NrdInputView.SIGMA_NORMAL_ROUGHNESS),
                descriptor(NrdInputView.SIGMA_VIEW_Z),
                descriptor(NrdInputView.SIGMA_PENUMBRA));
    }

    static ImageDiagnosticPass.View descriptor(NrdInputView view) {
        return switch (view) {
            case DENOISED_OUTPUT -> view(OUTPUT, ImageDiagnosticPass.RADIANCE);
            case PRIMARY_MOTION -> raw(PRIMARY_MOTION);
            case PRIMARY_NORMAL_ROUGHNESS -> raw(PRIMARY_NORMAL_ROUGHNESS);
            case PRIMARY_VIEW_Z -> raw(PRIMARY_VIEW_Z);
            case PRIMARY_DIFFUSE_SH0 -> raw(PRIMARY_DIFFUSE_SH0);
            case PRIMARY_DIFFUSE_SH1 -> raw(PRIMARY_DIFFUSE_SH1);
            case PRIMARY_SPECULAR_SH0 -> raw(PRIMARY_SPECULAR_SH0);
            case PRIMARY_SPECULAR_SH1 -> raw(PRIMARY_SPECULAR_SH1);
            case REFLECTION_MOTION -> raw(REFLECTION_MOTION);
            case REFLECTION_NORMAL_ROUGHNESS -> raw(REFLECTION_NORMAL_ROUGHNESS);
            case REFLECTION_VIEW_Z -> raw(REFLECTION_VIEW_Z);
            case REFLECTION_DIFFUSE_SH0 -> raw(REFLECTION_DIFFUSE_SH0);
            case REFLECTION_DIFFUSE_SH1 -> raw(REFLECTION_DIFFUSE_SH1);
            case REFLECTION_SPECULAR_SH0 -> raw(REFLECTION_SPECULAR_SH0);
            case REFLECTION_SPECULAR_SH1 -> raw(REFLECTION_SPECULAR_SH1);
            case SIGMA_NORMAL_ROUGHNESS -> raw(PRIMARY_NORMAL_ROUGHNESS);
            case SIGMA_VIEW_Z -> raw(PRIMARY_VIEW_Z);
            case SIGMA_PENUMBRA -> raw(SUN_PENUMBRA);
            case OFF, PRIMARY_GRID, REFLECTION_GRID, SIGMA_GRID ->
                    throw new IllegalArgumentException("NRD view has no single image");
        };
    }

    private static ImageDiagnosticPass.View raw(int source) {
        return view(source, ImageDiagnosticPass.RAW);
    }

    private static ImageDiagnosticPass.View view(int source, int presentation) {
        return new ImageDiagnosticPass.View(source, presentation);
    }

    @Override
    public void destroy() {
        this.outputs.destroy();
    }
}
