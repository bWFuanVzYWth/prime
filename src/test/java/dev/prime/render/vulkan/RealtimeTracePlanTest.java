// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import static dev.prime.render.vulkan.GeneratedShaderPrograms.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.prime.render.BounceSettings;
import dev.prime.render.shader.ShaderAbi;
import org.junit.jupiter.api.Test;

final class RealtimeTracePlanTest {
    private enum Vertex { EMPTY, TRACEABLE, HIT, LIGHT_SELECTED, LIT }

    @Test
    void secondaryConsumersFollowPublicationAndReleaseTheirInputBeforeReuse() {
        RaygenSchedule schedule = GeneratedShaderPrograms.schedule("realtime.standard", ".rgen.spv");
        for (int minimum = 1; minimum <= BounceSettings.MAXIMUM_FIXED_COUNT; ++minimum) {
            for (int maximum = 1; maximum <= BounceSettings.MAXIMUM_COUNT; ++maximum) {
                RealtimeTracePlan plan = RealtimeTracePlan.forBounces(minimum, maximum);
                Vertex[] queues = {Vertex.TRACEABLE, Vertex.EMPTY};
                int vertices = 0;
                boolean landingPublished = false;
                for (int i = 0; i < plan.size(); ++i) {
                    var dispatch = plan.dispatch(i);
                    assertTrue(dispatch.group() >= 0 && dispatch.group() < schedule.groupCount());
                    if (dispatch.group() == REALTIME_STANDARD_LANDING_SCATTER) {
                        landingPublished = true;
                        continue;
                    }
                    // Primary owns an aliased command slot until its last consumer releases it.
                    if (!landingPublished) continue;
                    int slot = switch (dispatch.queue()) {
                        case ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0 -> 0;
                        case ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1 -> 1;
                        default -> -1;
                    };
                    if (slot < 0) continue;
                    // The SBT queue selector must address the same slot as indirect dispatch.
                    assertEquals(slot, schedule.control(dispatch.group()));
                    switch (dispatch.group()) {
                        case REALTIME_STANDARD_BRIDGE_TRACE_0, REALTIME_STANDARD_BRIDGE_TRACE_1 -> {
                            assertEquals(Vertex.TRACEABLE, queues[slot]);
                            assertNotEquals(RealtimeTracePlan.Barrier.NONE, dispatch.barrier());
                            queues[slot] = Vertex.HIT;
                        }
                        case REALTIME_STANDARD_LIGHT_SELECT_0, REALTIME_STANDARD_LIGHT_SELECT_1 -> {
                            assertEquals(Vertex.HIT, queues[slot]);
                            assertNotEquals(RealtimeTracePlan.Barrier.NONE, dispatch.barrier());
                            queues[slot] = Vertex.LIGHT_SELECTED;
                        }
                        case REALTIME_STANDARD_DIRECT_0, REALTIME_STANDARD_DIRECT_1 -> {
                            assertEquals(Vertex.LIGHT_SELECTED, queues[slot]);
                            assertEquals(RealtimeTracePlan.Barrier.NEXT_STEP, dispatch.barrier());
                            queues[slot] = Vertex.LIT;
                        }
                        case REALTIME_STANDARD_SCATTER_0, REALTIME_STANDARD_SCATTER_1 -> {
                            assertEquals(Vertex.LIT, queues[slot]);
                            assertEquals(Vertex.EMPTY, queues[1 - slot]);
                            assertEquals(RealtimeTracePlan.Barrier.NEXT_STEP, dispatch.barrier());
                            queues[slot] = Vertex.EMPTY;
                            queues[1 - slot] = Vertex.TRACEABLE;
                            ++vertices;
                        }
                        default -> fail("Secondary queue has an undeclared consumer");
                    }
                }
                assertEquals(Math.max(minimum, maximum) - 1, vertices);
                for (Vertex state : queues) assertTrue(state == Vertex.EMPTY || state == Vertex.TRACEABLE);
                assertEquals(plan.size(), RealtimeRayTracingPipeline.dispatchCount(minimum, maximum));
                assertThrows(IndexOutOfBoundsException.class, () -> plan.dispatch(-1));
                assertThrows(IndexOutOfBoundsException.class, () -> plan.dispatch(plan.size()));
            }
        }
    }

    @Test
    void guideScratchIsDrainedBeforeReuseAndOutputsFollowTransport() {
        RealtimeTracePlan plan = RealtimeTracePlan.forBounces(2, BounceSettings.MAXIMUM_COUNT);
        boolean guidePending = false;
        boolean landingPublished = false;
        boolean resolved = false;
        for (int i = 0; i < plan.size(); ++i) {
            var dispatch = plan.dispatch(i);
            switch (dispatch.group()) {
                case REALTIME_STANDARD_SURFACE_SPLIT, REALTIME_STANDARD_DELTA_WALK_0, REALTIME_STANDARD_DELTA_WALK_1 -> {
                    assertFalse(guidePending, "A new detached guide would overwrite an undrained owner");
                    assertFalse(landingPublished);
                    guidePending = true;
                }
                case REALTIME_STANDARD_GUIDE_DELTA_WALK_0, REALTIME_STANDARD_GUIDE_DELTA_WALK_1 -> {
                    assertEquals(ShaderAbi.WAVEFRONT_GUIDE_QUEUE, dispatch.queue());
                    assertEquals(RealtimeTracePlan.Barrier.NEXT_STEP, dispatch.barrier());
                    guidePending = false;
                }
                case REALTIME_STANDARD_LANDING_SCATTER -> {
                    assertFalse(guidePending);
                    landingPublished = true;
                }
                case REALTIME_STANDARD_BRIDGE_TRACE_0, REALTIME_STANDARD_BRIDGE_TRACE_1 -> {
                    assertTrue(landingPublished);
                    assertFalse(resolved);
                }
                case REALTIME_STANDARD_BRANCH_RESOLVE -> {
                    assertTrue(landingPublished);
                    assertFalse(guidePending);
                    assertEquals(RealtimeTracePlan.Barrier.RESOLVE, dispatch.barrier());
                    resolved = true;
                }
                case REALTIME_STANDARD_NOISY_OUTPUT_RESOLVE -> assertTrue(resolved);
                default -> { }
            }
        }
        assertTrue(resolved);
    }
}
