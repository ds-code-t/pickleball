package tools.dscode.common.control;

/** Decision returned by a synchronous control hook. */
public enum ControlDecision {
    CONTINUE,
    SKIP;

    public boolean skip() {
        return this == SKIP;
    }
}
