package tools.dscode.engine.match;

/** How a matched step is executed. Stubs do not run the step. */
public enum StepKind {
    GLUE,
    DYNAMIC,
    BLOCK_CONDITIONAL,
    RUN,
    SERVICE_CALL,
    OVERRIDE,
    UNDEFINED,
    AMBIGUOUS
}
