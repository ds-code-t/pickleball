package tools.dscode.workbench.ui;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Last-window policy for the Swing process.
 * A dedicated {@code ui} process exits when its last window closes so the next
 * {@code open-window} does not leave a second JVM beside a closed window.
 * A window opened inside a live session controller ({@code open-ui}) does not
 * exit that process, and a headless run keeps going.
 */
public final class WorkbenchWindowLifecycle {
    public static final String EXIT_ON_LAST_WINDOW = "pickleball.workbench.ui.exitOnLastWindow";

    private static final AtomicInteger openFrames = new AtomicInteger();
    private static volatile IntConsumer exit = System::exit;

    private WorkbenchWindowLifecycle() {
    }

    /** The standalone {@code ui} command. Closing the last window ends this JVM. */
    public static void armDedicatedProcess() {
        System.setProperty(EXIT_ON_LAST_WINDOW, "true");
    }

    /** {@code open-ui} inside a session controller. Closing the window must not exit it. */
    public static void armSharedController() {
        System.setProperty(EXIT_ON_LAST_WINDOW, "false");
    }

    public static boolean dedicatedProcess() {
        return Boolean.parseBoolean(System.getProperty(EXIT_ON_LAST_WINDOW, "false"));
    }

    static void opened() {
        openFrames.incrementAndGet();
    }

    static void closed() {
        int remaining = openFrames.decrementAndGet();
        if (remaining > 0) return;
        if (remaining < 0) {
            openFrames.set(0);
            return;
        }
        if (!dedicatedProcess()) return;
        exit.accept(0);
    }

    static void finish(Runnable disposeAction) {
        try {
            if (disposeAction != null) disposeAction.run();
        } finally {
            closed();
        }
    }

    static void exitForTests(IntConsumer hook) {
        exit = hook == null ? System::exit : hook;
    }

    static void resetForTests() {
        openFrames.set(0);
        exit = System::exit;
        System.clearProperty(EXIT_ON_LAST_WINDOW);
    }

    static int openFramesForTests() {
        return Math.max(0, openFrames.get());
    }
}
