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
    PC_LATENCY_PING(8),
    OUT_OF_BAND_RENDER_SUBMIT_START(9),
    OUT_OF_BAND_RENDER_SUBMIT_END(10),
    OUT_OF_BAND_PRESENT_START(11),
    OUT_OF_BAND_PRESENT_END(12),
    CONTROLLER_INPUT_SAMPLE(13),
    DELTA_T_CALCULATION(14),
    LATE_WARP_PRESENT_START(15),
    LATE_WARP_PRESENT_END(16),
    CAMERA_CONSTRUCTED(17),
    LATE_WARP_RENDER_SUBMIT_START(18),
    LATE_WARP_RENDER_SUBMIT_END(19),
    VENDOR_INTERNAL_ASYNC_PRESENT_START(20),
    VENDOR_INTERNAL_ASYNC_PRESENT_END(21),
    NUM_PRESENTS_IN_BATCH(22);

    public final int value;

    PclMarker(int value) {
        this.value = value;
    }
}
