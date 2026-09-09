// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.binding.streamline;

/** sl::PreferenceFlags (uint64_t bitmask) */
public enum PreferenceFlag {
    DISABLE_CL_STATE_TRACKING(1L),
    USE_FRAME_BASED_RESOURCE_TAGGING(1L << 7);

    public final long mask;

    PreferenceFlag(long mask) {
        this.mask = mask;
    }
}
