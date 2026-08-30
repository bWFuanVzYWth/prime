package dev.prime.binding.streamline;

/** sl::RenderAPI */
public enum RenderApi {
    D3D11(0),
    D3D12(1),
    VULKAN(2);

    public final int value;

    RenderApi(int value) {
        this.value = value;
    }

    public static RenderApi fromValue(int value) {
        for (RenderApi api : values()) {
            if (api.value == value) {
                return api;
            }
        }
        throw new IllegalArgumentException("Unknown sl::RenderAPI value: " + value);
    }
}
