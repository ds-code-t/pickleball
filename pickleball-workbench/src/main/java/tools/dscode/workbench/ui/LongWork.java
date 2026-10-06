package tools.dscode.workbench.ui;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs a long load off the caller thread and keeps a status the window can show.
 * The status names the work while it runs, clears when it finishes, and names
 * the error when it fails.
 */
final class LongWork {
    static final String SYNCING_THE_PROJECT = "Syncing the project";
    static final String STARTING_THE_RUN = "Starting the run";
    static final String LOADING_THE_PROJECT = "Loading the project";
    static final String STOPPING_THE_RUN = "Stopping the run";

    private final AtomicReference<String> status = new AtomicReference<>();
    private final AtomicBoolean failure = new AtomicBoolean();

    String status() {
        return status.get();
    }

    boolean failed() {
        return failure.get();
    }

    void finish() {
        failure.set(false);
        status.set(null);
    }

    static Thread thread(Runnable runnable) {
        Thread thread = new Thread(runnable, "pickleball-workbench-load");
        thread.setDaemon(true);
        return thread;
    }

    <T> void start(
            String name,
            Supplier<T> work,
            Consumer<T> onSuccess,
            Consumer<Throwable> onFailure,
            Executor executor,
            Consumer<Runnable> onCallerThread
    ) {
        failure.set(false);
        status.set(name);
        executor.execute(() -> {
            try {
                T value = work.get();
                onCallerThread.accept(() -> {
                    try {
                        onSuccess.accept(value);
                    } catch (Throwable thrown) {
                        markFailure(name, thrown);
                        onFailure.accept(thrown);
                    }
                });
            } catch (Throwable thrown) {
                onCallerThread.accept(() -> {
                    markFailure(name, thrown);
                    onFailure.accept(thrown);
                });
            }
        });
    }

    private void markFailure(String name, Throwable thrown) {
        failure.set(true);
        String detail = thrown == null ? "unknown error" : thrown.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = thrown.getClass().getSimpleName();
        }
        status.set(name + " failed: " + detail);
    }
}
