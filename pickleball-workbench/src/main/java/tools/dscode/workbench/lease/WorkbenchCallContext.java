package tools.dscode.workbench.lease;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Identifies whether the current thread is acting as the human UI adapter or as
 * an attached AI agent. Adapters set this around {@code WorkbenchServices} calls
 * so lease checks stay in Workbench rather than Swing or MCP.
 */
public final class WorkbenchCallContext {
    private static final ThreadLocal<WorkbenchLeaseHolder> HOLDER =
            ThreadLocal.withInitial(() -> WorkbenchLeaseHolder.HUMAN);

    private WorkbenchCallContext() {
    }

    public static WorkbenchLeaseHolder current() {
        return HOLDER.get();
    }

    public static void runAs(WorkbenchLeaseHolder holder, Runnable action) {
        callAs(holder, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T callAs(WorkbenchLeaseHolder holder, Supplier<T> action) {
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(action, "action");
        WorkbenchLeaseHolder previous = HOLDER.get();
        HOLDER.set(holder);
        try {
            return action.get();
        } finally {
            HOLDER.set(previous);
        }
    }
}
