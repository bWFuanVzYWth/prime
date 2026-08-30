package dev.prime.binding.streamline;

/** sl::FeatureRequirementFlags (uint32_t bitmask) */
public enum FeatureRequirementFlag {
    D3D11_SUPPORTED(1),
    D3D12_SUPPORTED(1 << 1),
    VULKAN_SUPPORTED(1 << 2),
    VSYNC_OFF_REQUIRED(1 << 3),
    HARDWARE_SCHEDULING_REQUIRED(1 << 4);

    public final int mask;

    FeatureRequirementFlag(int mask) {
        this.mask = mask;
    }
}
