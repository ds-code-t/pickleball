package tools.dscode.common.control;

import java.util.Objects;

/** Live in-JVM hook event. External controllers should serialize snapshots rather than this object graph. */
public record ControlEvent(
        ControlHook hook,
        String signature,
        Object target,
        Object[] arguments
) {
    public ControlEvent {
        Objects.requireNonNull(hook, "hook");
        signature = signature == null ? "" : signature;
        arguments = arguments == null ? new Object[0] : arguments.clone();
    }

    @Override
    public Object[] arguments() {
        return arguments.clone();
    }
}
