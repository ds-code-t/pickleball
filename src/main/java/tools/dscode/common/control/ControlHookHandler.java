package tools.dscode.common.control;

/** Synchronous controller hook. Lambdas remain valid because only onHook is abstract. */
@FunctionalInterface
public interface ControlHookHandler {
    ControlDecision onHook(ControlEvent event);

    /**
     * Optional value interceptor. Return the value that Pickleball should use.
     * The default preserves existing behavior exactly.
     */
    default Object onValue(ControlValueEvent event) {
        return event.value();
    }
}
