package dev.prime.streamline;

import dev.prime.binding.streamline.DlssgStatus;

/** Last observed DLSS-G capability and runtime status. */
record StreamlineFrameGenerationSupport(
        int status, int maximumGeneratedFrameCount, int minimumWidthOrHeight) {
    StreamlineFrameGenerationSupport {
        maximumGeneratedFrameCount = Math.max(0, maximumGeneratedFrameCount);
        minimumWidthOrHeight = Math.max(0, minimumWidthOrHeight);
    }

    static StreamlineFrameGenerationSupport unavailable() {
        return new StreamlineFrameGenerationSupport(0, 0, 0);
    }

    boolean featureAvailable() {
        return this.maximumGeneratedFrameCount > 0;
    }

    boolean runtimeCanRetry() {
        return this.featureAvailable()
                && (this.status & ~DlssgStatus.FAIL_RESOLUTION_TOO_LOW.mask) == 0;
    }

    boolean supportsFrame(
            int depthWidth,
            int depthHeight,
            int motionWidth,
            int motionHeight,
            int colorWidth,
            int colorHeight) {
        if (!runtimeCanRetry()
                || depthWidth <= 0
                || depthHeight <= 0
                || motionWidth <= 0
                || motionHeight <= 0
                || colorWidth <= 0
                || colorHeight <= 0) {
            return false;
        }
        int smallest = Math.min(
                Math.min(Math.min(depthWidth, depthHeight), Math.min(motionWidth, motionHeight)),
                Math.min(colorWidth, colorHeight));
        return smallest >= this.minimumWidthOrHeight;
    }
}
