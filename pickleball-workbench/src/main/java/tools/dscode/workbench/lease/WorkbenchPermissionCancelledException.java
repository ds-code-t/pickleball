package tools.dscode.workbench.lease;

/** Raised when Take control (or release) aborts an in-flight agent permission wait. */
public final class WorkbenchPermissionCancelledException extends IllegalStateException {
    public WorkbenchPermissionCancelledException() {
        super("The human took control before the permission request was answered.");
    }

    public WorkbenchPermissionCancelledException(String message) {
        super(message);
    }
}
