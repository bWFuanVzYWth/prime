package dev.prime.binding.streamline;

/** sl::RenderAPI */
public enum RenderApi {
    D3D12(1),
    VULKAN(2);

    public final int value;

    RenderApi(int value) {
        this.value = value;
    }
}
