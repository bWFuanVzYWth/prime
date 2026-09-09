// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import java.util.Arrays;

/**
 * Render-thread-owned committed keys for atmosphere LUT contents.
 *
 * <p>The candidate array is reusable scratch. A recorded candidate becomes observable only after
 * the command buffer that generated the matching LUTs is submitted.
 */
final class AtmosphereLutHistory {
    static final int STATIC = 1;
    static final int SKY = 1 << 1;
    static final int AERIAL = 1 << 2;

    private int skyEyeRadiusBits;
    private int skySunElevationBits;
    private int[] aerialKey;
    private int[] candidateAerialKey;
    private int pendingEyeRadiusBits;
    private int pendingSunElevationBits;
    private int pendingChanges;
    private boolean aerialKeyValid;
    private boolean staticPrepared;
    private boolean pending;

    AtmosphereLutHistory(int aerialKeySize) {
        if (aerialKeySize <= 0) {
            throw new IllegalArgumentException("Atmosphere aerial key must not be empty");
        }
        this.aerialKey = new int[aerialKeySize];
        this.candidateAerialKey = new int[aerialKeySize];
    }

    boolean staticPrepared() {
        return this.staticPrepared;
    }

    void staticSubmitted() {
        requireNoPending();
        if (this.staticPrepared) {
            throw new IllegalStateException("Static atmosphere LUTs are already prepared");
        }
        this.staticPrepared = true;
    }

    int[] beginCandidate() {
        requireNoPending();
        return this.candidateAerialKey;
    }

    int prepareCandidate(int eyeRadiusBits, int sunElevationBits) {
        requireNoPending();
        int changes = 0;
        if (!this.staticPrepared) {
            changes |= STATIC | SKY | AERIAL;
        } else {
            if (eyeRadiusBits != this.skyEyeRadiusBits
                    || sunElevationBits != this.skySunElevationBits) {
                changes |= SKY;
            }
            if (!this.aerialKeyValid
                    || !Arrays.equals(this.aerialKey, this.candidateAerialKey)) {
                changes |= AERIAL;
            }
        }
        if (changes == 0) {
            return 0;
        }
        this.pendingEyeRadiusBits = eyeRadiusBits;
        this.pendingSunElevationBits = sunElevationBits;
        this.pendingChanges = changes;
        this.pending = true;
        return changes;
    }

    void commit() {
        requirePending();
        if ((this.pendingChanges & STATIC) != 0) {
            this.staticPrepared = true;
        }
        if ((this.pendingChanges & SKY) != 0) {
            this.skyEyeRadiusBits = this.pendingEyeRadiusBits;
            this.skySunElevationBits = this.pendingSunElevationBits;
        }
        if ((this.pendingChanges & AERIAL) != 0) {
            int[] previousKey = this.aerialKey;
            this.aerialKey = this.candidateAerialKey;
            this.candidateAerialKey = previousKey;
            this.aerialKeyValid = true;
        }
        finish();
    }

    void abandon() {
        requirePending();
        finish();
    }

    private void finish() {
        this.pendingChanges = 0;
        this.pending = false;
    }

    private void requireNoPending() {
        if (this.pending) {
            throw new IllegalStateException(
                    "Previous atmosphere LUT candidate has not been committed or abandoned");
        }
    }

    private void requirePending() {
        if (!this.pending) {
            throw new IllegalStateException("No atmosphere LUT candidate is pending");
        }
    }
}
