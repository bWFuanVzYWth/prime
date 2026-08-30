package dev.prime.binding.streamline;

/** sl::PCLHotKey (int16_t), also used by ReflexOptions.virtualKey */
public enum HotKey {
    USE_PING_MESSAGE((short) 0),
    VK_F13((short) 0x7C),
    VK_F14((short) 0x7D),
    VK_F15((short) 0x7E);

    public final short value;

    HotKey(short value) {
        this.value = value;
    }

    public static HotKey fromValue(short value) {
        for (HotKey key : values()) {
            if (key.value == value) {
                return key;
            }
        }
        throw new IllegalArgumentException("Unknown sl::PCLHotKey value: " + value);
    }
}
