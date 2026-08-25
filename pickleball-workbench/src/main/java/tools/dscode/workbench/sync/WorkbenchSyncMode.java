package tools.dscode.workbench.sync;

/** How much of the consumer build wrapper a Workbench synchronization invoked. */
public enum WorkbenchSyncMode {
    /** Maven `test-compile` / Gradle `testClasses` plus classpath metadata. */
    FULL,
    /** Resource processing only; Java sources and dependencies were unchanged. */
    RESOURCES_ONLY,
    /** Wrapper skipped; input fingerprints matched the last recorded snapshot. */
    SKIPPED
}
