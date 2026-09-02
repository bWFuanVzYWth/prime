package dev.prime.binding.streamline;

/** sl::DLSSGQueueParallelismMode */
public enum DlssgQueueParallelismMode {
    BLOCK_PRESENTING_CLIENT_QUEUE(0);

    public final int value;

    DlssgQueueParallelismMode(int value) {
        this.value = value;
    }
}
