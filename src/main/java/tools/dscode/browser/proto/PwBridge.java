package tools.dscode.browser.proto;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Page;
import tools.dscode.browser.PlaywrightConnection;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Playwright inside the protocol host. Playwright for Java is single-threaded and delivers events only while one of
 * its calls runs, so every call goes through one thread, and a pump keeps listeners (console, requests, routes)
 * flowing between commands while any are installed.
 */
public final class PwBridge implements AutoCloseable {
    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pkb-playwright");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService pump = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pkb-playwright-pump");
        t.setDaemon(true);
        return t;
    });
    private final String endpoint;
    private volatile PlaywrightConnection connection;
    private volatile int listeners;
    private volatile boolean holdDialogs;
    private final List<BrowserContext> held = new java.util.ArrayList<>();
    /** A context listener that answers nothing: while it is installed Playwright does not auto-dismiss a dialog. */
    private final java.util.function.Consumer<Dialog> keepOpen = dialog -> {
    };

    public PwBridge(String endpoint) {
        this.endpoint = endpoint;
        pump.scheduleWithFixedDelay(this::pumpOnce, 200, 100, TimeUnit.MILLISECONDS);
    }

    public <T> T call(Function<PlaywrightConnection, T> work) {
        Future<T> future = thread.submit(() -> {
            if (connection == null) connection = PlaywrightConnection.connect(endpoint);
            syncDialogHold();
            return work.apply(connection);
        });
        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("error: Playwright call did not finish: " + failure + "\nfix: check the browser");
        }
    }

    /** The page whose URL contains {@code url}, else the first web page. Call inside {@link #call}. */
    public static Page page(PlaywrightConnection connection, String url) {
        List<Page> pages = connection.pages();
        if (url != null && !url.isBlank()) for (Page page : pages) if (page.url().contains(url)) return page;
        for (Page page : pages) if (!page.url().startsWith("devtools://") && !page.url().startsWith("chrome-extension://")) return page;
        return connection.page(null);
    }

    /**
     * A Playwright client with no dialog listener auto-dismisses every dialog it sees, before a {@code dialogs}
     * policy on CDP or BiDi can answer it. While a policy is set the bridge keeps a no-op context listener, so the
     * policy (or the person) answers the dialog. Takes effect now when attached, else on the first call.
     */
    public void holdDialogs(boolean on) {
        holdDialogs = on;
        if (connection == null) return;
        try {
            thread.submit(this::syncDialogHold).get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    /** Runs on the Playwright thread. */
    private void syncDialogHold() {
        if (connection == null) return;
        if (holdDialogs) {
            for (BrowserContext context : connection.browser().contexts()) {
                if (held.contains(context)) continue;
                context.onDialog(keepOpen);
                held.add(context);
            }
        } else if (!held.isEmpty()) {
            for (BrowserContext context : held) {
                try {
                    context.offDialog(keepOpen);
                } catch (RuntimeException ignored) {
                    // The context is gone.
                }
            }
            held.clear();
        }
    }

    public void listening(boolean on) {
        listeners = Math.max(0, listeners + (on ? 1 : -1));
    }

    private void pumpOnce() {
        if (listeners == 0 || connection == null) return;
        try {
            thread.submit(() -> {
                List<Page> pages = connection.pages();
                if (!pages.isEmpty()) pages.get(0).waitForTimeout(20);
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // The next tick tries again.
        }
    }

    @Override
    public void close() {
        pump.shutdownNow();
        thread.shutdownNow();
    }
}
