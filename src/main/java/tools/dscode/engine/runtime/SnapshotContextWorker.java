package tools.dscode.engine.runtime;

/**
 * Cold JVM for one snapshot context. The warm loader is not on this process.
 * The process stays up until the parent destroys it, which frees the pool slot.
 */
public final class SnapshotContextWorker {
    private SnapshotContextWorker() {
    }

    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(Long.MAX_VALUE);
    }
}
