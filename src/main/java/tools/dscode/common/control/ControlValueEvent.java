package tools.dscode.common.control;

import java.util.Objects;

/**
 * Mutable-value interception event used where a controller may replace one value
 * while leaving the underlying Pickleball operation intact.
 */
public record ControlValueEvent(
        ControlHook hook,
        String role,
        String signature,
        Object target,
        Object value,
        Object[] arguments
) {
    public ControlValueEvent {
        Objects.requireNonNull(hook, "hook");
        role = role == null ? "" : role;
        signature = signature == null ? "" : signature;
        arguments = arguments == null ? new Object[0] : arguments.clone();
    }

    @Override
    public Object[] arguments() {
        return arguments.clone();
    }
}
