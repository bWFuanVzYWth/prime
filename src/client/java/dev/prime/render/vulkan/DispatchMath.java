// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

/** Integer workgroup sizing shared by compute passes. */
public final class DispatchMath {
    private DispatchMath() {
    }

    public static int divideRoundUp(int value, int divisor) {
        return Math.max(1, (value + divisor - 1) / divisor);
    }
}
