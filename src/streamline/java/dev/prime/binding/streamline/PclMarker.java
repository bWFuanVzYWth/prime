// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.binding.streamline;

/** sl::PCLMarker (uint32_t). Value 6 (eInputSample) is deprecated in the SDK and intentionally omitted. */
public enum PclMarker {
    SIMULATION_START(0),
    SIMULATION_END(1),
    RENDER_SUBMIT_START(2),
    RENDER_SUBMIT_END(3),
    PRESENT_START(4),
    PRESENT_END(5),
    TRIGGER_FLASH(7),
    PC_LATENCY_PING(8);

    public final int value;

    PclMarker(int value) {
        this.value = value;
    }
}
