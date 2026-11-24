package tools.dscode.common.agentfast;

import tools.dscode.control.protocol.AgentFastLog;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The start host's two lanes. Page work runs on one command thread so CDP
 * calls stay ordered. A blocking wait polls off that thread and only hops
 * back for a short page mutation. {@code /health} never takes the command lane.
 */
public final class HostLanes {
    private static final AtomicInteger WAITS = new AtomicInteger();
    private static final AtomicInteger COMMAND_DEPTH = new AtomicInteger();
    private static final ThreadLocal<Boolean> ON_COMMAND = ThreadLocal.withInitial(() -> false);
    private static final ExecutorService COMMAND = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pkb-command-lane");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService HTTP = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "pkb-live-http");
        thread.setDaemon(true);
        return thread;
    });

    private HostLanes() {
    }

    /** Accepts health and verbs while a wait is parked on another thread. */
    public static Executor httpExecutor() {
        return HTTP;
    }

    public static int waits() {
        return Math.max(0, WAITS.get());
    }

    public static boolean commandBusy() {
        return COMMAND_DEPTH.get() > 0;
    }

    public static void enterWait() {
        int now = WAITS.incrementAndGet();
        log("enter waits=" + now);
    }

    public static void leaveWait() {
        int now = WAITS.decrementAndGet();
        if (now < 0) {
            WAITS.compareAndSet(now, 0);
            now = 0;
        }
        log("leave waits=" + now);
    }

    /**
     * Runs {@code work} on the command lane. A caller that is already on that
     * lane runs inline, so a wait can hop in for one CDP call without deadlocking.
     */
    public static <T> T onCommand(Callable<T> work) {
        if (Boolean.TRUE.equals(ON_COMMAND.get())) {
            COMMAND_DEPTH.incrementAndGet();
            try {
                return call(work);
            } finally {
                COMMAND_DEPTH.decrementAndGet();
            }
        }
        try {
            return COMMAND.submit(() -> {
                ON_COMMAND.set(true);
                COMMAND_DEPTH.incrementAndGet();
                try {
                    return call(work);
                } finally {
                    COMMAND_DEPTH.decrementAndGet();
                    ON_COMMAND.remove();
                }
            }).get();
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause == null ? failure : cause);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static <T> T call(Callable<T> work) {
        try {
            return work.call();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void log(String message) {
        Path project = LiveBrowserHost.project();
        if (project == null || project.toString().isBlank() || ".".equals(project.toString())) return;
        AgentFastLog.log(project, "wait", message);
    }
}
