package dev.prime.binding.streamline;

/** sl::ReflexMode */
public enum ReflexMode {
    OFF(0),
    LOW_LATENCY(1),
    LOW_LATENCY_WITH_BOOST(2);

    public final int value;

    ReflexMode(int value) {
        this.value = value;
    }
}
