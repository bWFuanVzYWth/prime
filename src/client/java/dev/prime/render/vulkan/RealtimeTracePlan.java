// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import static dev.prime.render.vulkan.GeneratedShaderPrograms.*;

import dev.prime.render.BounceSettings;
import dev.prime.render.shader.ShaderAbi;
import java.util.Objects;

/** Immutable command order; recording and pass accounting consume the same plan. */
final class RealtimeTracePlan {
    enum Barrier { NONE, WAVEFRONT, PRIMARY_DIRECT, PRIMARY, NEXT_STEP, RESOLVE }

    record Dispatch(int group, int queue, Barrier barrier) {
        boolean indirect() { return queue >= 0; }
    }

    private static final Dispatch[] PRIMARY = {
        new Dispatch(REALTIME_STANDARD_CAMERA_TRACE, -1, Barrier.NONE),
        new Dispatch(REALTIME_STANDARD_VISIBLE_DIRECT, ShaderAbi.WAVEFRONT_AREA_QUEUE, Barrier.PRIMARY_DIRECT),
        new Dispatch(REALTIME_STANDARD_SURFACE_SPLIT, ShaderAbi.WAVEFRONT_PRIMARY_QUEUE, Barrier.PRIMARY),
        // Every producer may detach a guide into the same pixel scratch. Drain before reuse,
        // including the complementary guide published by surface split.
        new Dispatch(REALTIME_STANDARD_GUIDE_DELTA_WALK_0, ShaderAbi.WAVEFRONT_GUIDE_QUEUE, Barrier.NEXT_STEP),
        new Dispatch(REALTIME_STANDARD_DELTA_WALK_0, ShaderAbi.WAVEFRONT_TRACE_QUEUE_0, Barrier.NEXT_STEP),
        new Dispatch(REALTIME_STANDARD_GUIDE_DELTA_WALK_0, ShaderAbi.WAVEFRONT_GUIDE_QUEUE, Barrier.NEXT_STEP),
        new Dispatch(REALTIME_STANDARD_DELTA_WALK_1, ShaderAbi.WAVEFRONT_TRACE_QUEUE_1, Barrier.NEXT_STEP),
        new Dispatch(REALTIME_STANDARD_GUIDE_DELTA_WALK_1, ShaderAbi.WAVEFRONT_GUIDE_QUEUE, Barrier.NEXT_STEP),
        new Dispatch(REALTIME_STANDARD_LANDING_LIGHT_SELECT, ShaderAbi.WAVEFRONT_PRIMARY_QUEUE, Barrier.NEXT_STEP),
        new Dispatch(REALTIME_STANDARD_LANDING_DIRECT, ShaderAbi.WAVEFRONT_PRIMARY_QUEUE, Barrier.NEXT_STEP),
        new Dispatch(REALTIME_STANDARD_LANDING_SCATTER, ShaderAbi.WAVEFRONT_PRIMARY_QUEUE, Barrier.NEXT_STEP)
    };
    private static final Dispatch[][] SECONDARY = {
        {
            new Dispatch(REALTIME_STANDARD_BRIDGE_TRACE_0, ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0, Barrier.WAVEFRONT),
            new Dispatch(REALTIME_STANDARD_LIGHT_SELECT_0, ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0, Barrier.WAVEFRONT),
            new Dispatch(REALTIME_STANDARD_DIRECT_0, ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0, Barrier.NEXT_STEP),
            new Dispatch(REALTIME_STANDARD_SCATTER_0, ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0, Barrier.NEXT_STEP)
        },
        {
            new Dispatch(REALTIME_STANDARD_BRIDGE_TRACE_1, ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1, Barrier.WAVEFRONT),
            new Dispatch(REALTIME_STANDARD_LIGHT_SELECT_1, ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1, Barrier.WAVEFRONT),
            new Dispatch(REALTIME_STANDARD_DIRECT_1, ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1, Barrier.NEXT_STEP),
            new Dispatch(REALTIME_STANDARD_SCATTER_1, ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1, Barrier.NEXT_STEP)
        }
    };
    private static final Dispatch[] OUTPUT = {
        new Dispatch(REALTIME_STANDARD_BRANCH_RESOLVE, ShaderAbi.WAVEFRONT_TRANSPARENT_RESOLVE_QUEUE, Barrier.RESOLVE),
        new Dispatch(REALTIME_STANDARD_NOISY_OUTPUT_RESOLVE, -1, Barrier.NONE)
    };
    private static final RealtimeTracePlan[] PLANS = new RealtimeTracePlan[BounceSettings.MAXIMUM_COUNT];
    static {
        for (int i = 0; i < PLANS.length; ++i) PLANS[i] = new RealtimeTracePlan(i + 1);
    }

    private final int secondaryDispatches;

    private RealtimeTracePlan(int bounces) {
        this.secondaryDispatches = (bounces - 1) * SECONDARY[0].length;
    }

    static RealtimeTracePlan forBounces(int minimum, int maximum) {
        int limit = Math.max(BounceSettings.validateFixedCount(minimum), BounceSettings.validateCount(maximum));
        return PLANS[limit - 1];
    }

    int size() {
        return PRIMARY.length + this.secondaryDispatches + OUTPUT.length;
    }

    // No per-frame lists, commands or closures are allocated while recording.
    Dispatch dispatch(int index) {
        Objects.checkIndex(index, this.size());
        if (index < PRIMARY.length) return PRIMARY[index];
        int secondary = index - PRIMARY.length;
        if (secondary < this.secondaryDispatches) {
            int round = secondary / SECONDARY[0].length;
            return SECONDARY[round & 1][secondary % SECONDARY[0].length];
        }
        return OUTPUT[secondary - this.secondaryDispatches];
    }
}
