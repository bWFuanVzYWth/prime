package dev.prime.binding.streamline;

/** sl::PCLHotKey (int16_t), also used by ReflexOptions.virtualKey */
public enum HotKey {
    VK_F13((short) 0x7C);

    public final short value;

    HotKey(short value) {
        this.value = value;
    }
}
