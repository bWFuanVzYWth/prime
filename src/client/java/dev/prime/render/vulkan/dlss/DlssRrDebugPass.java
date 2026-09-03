package dev.prime.render.vulkan.dlss;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.diagnostic.RrInputView;
import dev.prime.render.vulkan.ImageDiagnosticPass;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Diagnostic-only visualizer over the exact images submitted to DLSS RR. */
final class DlssRrDebugPass implements Destroyable {
    private static final int OUTPUT = 0;
    private static final int INPUT_COLOR = 1;
    private static final int DIFFUSE_ALBEDO = 2;
    private static final int SPECULAR_ALBEDO = 3;
    private static final int NORMAL_ROUGHNESS = 4;
    private static final int DEPTH = 5;
    private static final int MOTION = 6;
    private static final int SPECULAR_MOTION = 7;
    private static final int SPECULAR_HIT_DISTANCE = 8;
    private static final int RESPONSIVITY = 9;

    private final ImageDiagnosticPass.OutputPair outputs;

    private DlssRrDebugPass(ImageDiagnosticPass.OutputPair outputs) {
        this.outputs = outputs;
    }

    static DlssRrDebugPass create(
            VulkanContext context,
            DlssRrTargets targets,
            VulkanImage displayOutput,
            VulkanImage hdrOutput) {
        VulkanImage[] sources = {
            targets.rrOutput(),
            targets.inputColor(),
            targets.material(),
            targets.specularMaterial(),
            targets.rrNormalRoughness(),
            targets.viewZ(),
            targets.motion(),
            targets.specularMotion(),
            targets.specularHitDistance(),
            targets.responsivity()
        };
        return new DlssRrDebugPass(
                ImageDiagnosticPass.OutputPair.create(
                        context, displayOutput, hdrOutput, sources));
    }

    void record(VkCommandBuffer commandBuffer, RrInputView view) {
        if (view == RrInputView.OFF) return;
        if (view == RrInputView.GRID) {
            List<ImageDiagnosticPass.View> views = List.of(
                    descriptor(RrInputView.DENOISED_OUTPUT),
                    descriptor(RrInputView.NOISY_INPUT),
                    descriptor(RrInputView.DIFFUSE_ALBEDO),
                    descriptor(RrInputView.SPECULAR_ALBEDO),
                    descriptor(RrInputView.NORMAL),
                    descriptor(RrInputView.ROUGHNESS),
                    descriptor(RrInputView.LINEAR_DEPTH),
                    descriptor(RrInputView.MOTION),
                    descriptor(RrInputView.SPECULAR_MOTION),
                    descriptor(RrInputView.SPECULAR_HIT_DISTANCE),
                    descriptor(RrInputView.RESPONSIVITY));
            this.outputs.sdr().recordGrid(commandBuffer, 4, views);
            this.outputs.hdr().recordGrid(commandBuffer, 4, views);
            return;
        }
        ImageDiagnosticPass.View descriptor = descriptor(view);
        this.outputs.sdr().recordFull(commandBuffer, descriptor);
        this.outputs.hdr().recordFull(commandBuffer, descriptor);
    }

    private static ImageDiagnosticPass.View descriptor(RrInputView view) {
        return switch (view) {
            case DENOISED_OUTPUT -> view(OUTPUT, ImageDiagnosticPass.RADIANCE);
            case NOISY_INPUT -> view(INPUT_COLOR, ImageDiagnosticPass.RADIANCE);
            case DIFFUSE_ALBEDO -> view(DIFFUSE_ALBEDO, ImageDiagnosticPass.ALBEDO);
            case SPECULAR_ALBEDO -> view(SPECULAR_ALBEDO, ImageDiagnosticPass.ALBEDO);
            case NORMAL -> view(NORMAL_ROUGHNESS, ImageDiagnosticPass.NORMAL);
            case ROUGHNESS -> view(NORMAL_ROUGHNESS, ImageDiagnosticPass.ROUGHNESS);
            case LINEAR_DEPTH -> view(DEPTH, ImageDiagnosticPass.DEPTH);
            case MOTION -> view(MOTION, ImageDiagnosticPass.MOTION);
            case SPECULAR_MOTION -> view(SPECULAR_MOTION, ImageDiagnosticPass.MOTION);
            case SPECULAR_HIT_DISTANCE ->
                    view(SPECULAR_HIT_DISTANCE, ImageDiagnosticPass.HIT_R);
            case RESPONSIVITY -> view(RESPONSIVITY, ImageDiagnosticPass.SIGNED);
            case OFF, GRID -> throw new IllegalArgumentException("RR view has no single image");
        };
    }

    private static ImageDiagnosticPass.View view(int source, int presentation) {
        return new ImageDiagnosticPass.View(source, presentation);
    }

    @Override
    public void destroy() {
        this.outputs.destroy();
    }
}
