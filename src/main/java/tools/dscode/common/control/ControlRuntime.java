package tools.dscode.common.control;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Minimal synchronous hook registry used by core interception points.
 * A handler may block inside {@link ControlHookHandler#onHook(ControlEvent)} to pause execution.
 */
public final class ControlRuntime {
    private static final AtomicReference<ControlHookHandler> GLOBAL_HANDLER = new AtomicReference<>();
    private static final ThreadLocal<ControlHookHandler> THREAD_HANDLER = new ThreadLocal<>();
    private static final ThreadLocal<Throwable> LAST_HANDLER_FAILURE = new ThreadLocal<>();
    private static final ThreadLocal<Integer> DISPATCH_DEPTH = ThreadLocal.withInitial(() -> 0);

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

    public static boolean hasHandler() {
        return !isDispatching() && currentHandler() != null;
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
        if (isDispatching()) {
            return ControlDecision.CONTINUE;
        }
        ControlHookHandler handler = currentHandler();
        if (handler == null) {
            return ControlDecision.CONTINUE;
        }

        enterDispatch();
        try {
            ControlDecision decision = handler.onHook(
                    new ControlEvent(hook, signature, target, arguments)
            );
            LAST_HANDLER_FAILURE.remove();
            return decision == null ? ControlDecision.CONTINUE : decision;
        } catch (Throwable failure) {
            LAST_HANDLER_FAILURE.set(failure);
            return ControlDecision.CONTINUE;
        } finally {
            exitDispatch();
        }
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
}
