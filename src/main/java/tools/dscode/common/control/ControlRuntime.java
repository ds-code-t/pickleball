
package tools.dscode.common.control;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Minimal synchronous hook registry used by core interception points.
 * A handler may block inside {@link ControlHookHandler#onHook(ControlEvent)} to pause execution.
 */
public final class ControlRuntime {
    private static final String STUDIO_BRIDGE_SESSION_DIR = "PKB_STUDIO_BRIDGE_SESSION_DIR";
    private static final String STUDIO_BRIDGE_BOOTSTRAP =
            "tools.dscode.control.bridge.ControlBridgeBootstrap";

    private static final AtomicReference<ControlHookHandler> GLOBAL_HANDLER = new AtomicReference<>();
    private static final CopyOnWriteArrayList<ControlHookHandler> OBSERVERS =
            new CopyOnWriteArrayList<>();
    private static final AtomicBoolean BRIDGE_BOOTSTRAP_ATTEMPTED = new AtomicBoolean();

    private static final ThreadLocal<ControlHookHandler> THREAD_HANDLER = new ThreadLocal<>();
    private static final ThreadLocal<Throwable> LAST_HANDLER_FAILURE = new ThreadLocal<>();
    private static final ThreadLocal<Integer> DISPATCH_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> OBSERVER_DEPTH = ThreadLocal.withInitial(() -> 0);

    private ControlRuntime() {
    }

    public static void setHandler(ControlHookHandler handler) {
        GLOBAL_HANDLER.set(handler);
    }

    public static void clearHandler() {
        GLOBAL_HANDLER.set(null);
    }

    public static void setThreadHandler(ControlHookHandler handler) {
        if (handler == null) {
            THREAD_HANDLER.remove();
        } else {
            THREAD_HANDLER.set(handler);
        }
    }

    public static void clearThreadHandler() {
        THREAD_HANDLER.remove();
        LAST_HANDLER_FAILURE.remove();
    }

    /**
     * Adds an observation-only hook listener without replacing the normal global/thread handler.
     *
     * <p>Observer decisions are ignored and {@link ControlHookHandler#onValue(ControlValueEvent)}
     * is not invoked. Observers run outside the primary handler's re-entrancy guard so an
     * observer may dispatch work that should still be visible to the primary handler. Nested
     * observer dispatch caused by that work is suppressed.</p>
     */
    public static void addObserver(ControlHookHandler observer) {
        if (observer != null) {
            OBSERVERS.addIfAbsent(observer);
        }
    }

    public static void removeObserver(ControlHookHandler observer) {
        if (observer != null) {
            OBSERVERS.remove(observer);
        }
    }

    public static boolean hasHandler() {
        ensureStudioBridge();
        return !isDispatching() && (currentHandler() != null || !OBSERVERS.isEmpty());
    }

    public static boolean isDispatching() {
        return DISPATCH_DEPTH.get() > 0;
    }

    public static Throwable getLastHandlerFailure() {
        return LAST_HANDLER_FAILURE.get();
    }

    public static ControlDecision fire(
            ControlHook hook,
            String signature,
            Object target,
            Object... arguments
    ) {
        ensureStudioBridge();
        if (isDispatching()) {
            return ControlDecision.CONTINUE;
        }

        ControlEvent event = new ControlEvent(hook, signature, target, arguments);
        ControlDecision decision = firePrimary(event);
        notifyObservers(event);
        return decision;
    }

    /**
     * Gives a handler the opportunity to replace a single intercepted value.
     * Handler failures are isolated and the original value is retained.
     */
    public static Object transform(
            ControlHook hook,
            String role,
            String signature,
            Object target,
            Object value,
            Object... arguments
    ) {
        ensureStudioBridge();
        if (isDispatching()) {
            return value;
        }
        ControlHookHandler handler = currentHandler();
        if (handler == null) {
            return value;
        }

        enterDispatch();
        try {
            Object transformed = handler.onValue(
                    new ControlValueEvent(
                            hook,
                            role,
                            signature,
                            target,
                            value,
                            arguments
                    )
            );
            LAST_HANDLER_FAILURE.remove();
            return transformed;
        } catch (Throwable failure) {
            LAST_HANDLER_FAILURE.set(failure);
            return value;
        } finally {
            exitDispatch();
        }
    }

    public static <T> T withThreadHandler(ControlHookHandler handler, Supplier<T> action) {
        ControlHookHandler previous = THREAD_HANDLER.get();
        setThreadHandler(handler);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                clearThreadHandler();
            } else {
                THREAD_HANDLER.set(previous);
            }
        }
    }

    private static ControlDecision firePrimary(ControlEvent event) {
        ControlHookHandler handler = currentHandler();
        if (handler == null) {
            return ControlDecision.CONTINUE;
        }

        enterDispatch();
        try {
            ControlDecision decision = handler.onHook(event);
            LAST_HANDLER_FAILURE.remove();
            return decision == null ? ControlDecision.CONTINUE : decision;
        } catch (Throwable failure) {
            LAST_HANDLER_FAILURE.set(failure);
            return ControlDecision.CONTINUE;
        } finally {
            exitDispatch();
        }
    }

    private static void notifyObservers(ControlEvent event) {
        if (OBSERVERS.isEmpty() || OBSERVER_DEPTH.get() > 0) {
            return;
        }

        OBSERVER_DEPTH.set(OBSERVER_DEPTH.get() + 1);
        try {
            for (ControlHookHandler observer : OBSERVERS) {
                try {
                    observer.onHook(event);
                } catch (Throwable ignored) {
                    // Observation-only infrastructure must not change Pickleball execution.
                }
            }
        } finally {
            int remaining = OBSERVER_DEPTH.get() - 1;
            if (remaining <= 0) {
                OBSERVER_DEPTH.remove();
            } else {
                OBSERVER_DEPTH.set(remaining);
            }
        }
    }

    private static ControlHookHandler currentHandler() {
        ControlHookHandler handler = THREAD_HANDLER.get();
        return handler == null ? GLOBAL_HANDLER.get() : handler;
    }

    private static void enterDispatch() {
        DISPATCH_DEPTH.set(DISPATCH_DEPTH.get() + 1);
    }

    private static void exitDispatch() {
        int remaining = DISPATCH_DEPTH.get() - 1;
        if (remaining <= 0) {
            DISPATCH_DEPTH.remove();
        } else {
            DISPATCH_DEPTH.set(remaining);
        }
    }

    private static void ensureStudioBridge() {
        if (BRIDGE_BOOTSTRAP_ATTEMPTED.get()) {
            return;
        }

        String sessionDirectory = System.getenv(STUDIO_BRIDGE_SESSION_DIR);
        if (sessionDirectory == null || sessionDirectory.isBlank()) {
            BRIDGE_BOOTSTRAP_ATTEMPTED.compareAndSet(false, true);
            return;
        }

        if (!BRIDGE_BOOTSTRAP_ATTEMPTED.compareAndSet(false, true)) {
            return;
        }

        try {
            Class<?> bootstrap = Class.forName(STUDIO_BRIDGE_BOOTSTRAP);
            bootstrap.getMethod("startFromEnvironment").invoke(null);
        } catch (InvocationTargetException failure) {
            reportBridgeBootstrapFailure(failure.getCause());
        } catch (Throwable failure) {
            reportBridgeBootstrapFailure(failure);
        }
    }

    private static void reportBridgeBootstrapFailure(Throwable failure) {
        String message = failure == null || failure.getMessage() == null
                ? String.valueOf(failure)
                : failure.getMessage();
        System.err.println("Pickleball Studio control bridge could not start: " + message);
    }
}
