package tools.dscode.engine.model;

/**
 * Pickleball-owned step and scenario status. Cucumber's status enum is
 * converted only at the adapter edge.
 */
public enum Status {
    PASSED,
    SKIPPED,
    PENDING,
    UNDEFINED,
    AMBIGUOUS,
    FAILED;

    public boolean isOk() {
        return this == PASSED || this == SKIPPED;
    }
}
