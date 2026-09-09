// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class RealtimeGuideBindingsTest {
    @Test
    void nextStepBarrierPublishesGuidesAndDeduplicatesAliases() {
        var bindings = RealtimeRayTracingPipeline.ImageBinding.values();
        long[] images = new long[bindings.length];
        Arrays.setAll(images, i -> 100L + i);
        var sources = new RealtimeRayTracingPipeline.ImageBinding[] {
            RealtimeRayTracingPipeline.ImageBinding.MATERIAL,
            RealtimeRayTracingPipeline.ImageBinding.SPECULAR_MATERIAL,
            RealtimeRayTracingPipeline.ImageBinding.REFLECTION_MATERIAL,
            RealtimeRayTracingPipeline.ImageBinding.REFLECTION_SPECULAR_MATERIAL,
            RealtimeRayTracingPipeline.ImageBinding.SUN_LIGHTING
        };
        long[] barrier = RealtimeRayTracingPipeline.nextStepInputImages(images);
        for (var source : sources) {
            assertEquals(1, Arrays.stream(barrier).filter(image -> image == images[source.ordinal()]).count());
        }
        images[sources[4].ordinal()] = images[sources[1].ordinal()];
        long alias = images[sources[1].ordinal()];
        assertEquals(1, Arrays.stream(RealtimeRayTracingPipeline.nextStepInputImages(images))
                .filter(image -> image == alias).count());
    }
}
