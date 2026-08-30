package dev.prime.binding.streamline;

/** sl::DLSSGQueueParallelismMode */
public enum DlssgQueueParallelismMode {
    BLOCK_PRESENTING_CLIENT_QUEUE(0),
    BLOCK_NO_CLIENT_QUEUES(1);

    public final int value;

    DlssgQueueParallelismMode(int value) {
        this.value = value;
    }

    public static DlssgQueueParallelismMode fromValue(int value) {
        for (DlssgQueueParallelismMode mode : values()) {
            if (mode.value == value) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown sl::DLSSGQueueParallelismMode value: " + value);
    }
}
