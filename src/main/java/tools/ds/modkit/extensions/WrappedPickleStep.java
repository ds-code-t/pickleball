//// WrappedPickleStep.java
//package tools.ds.modkit.wrap;
//
//import io.cucumber.plugin.event.PickleStepTestStep;
//import io.cucumber.plugin.event.TestStep;
//import io.cucumber.plugin.event.Argument;
//import io.cucumber.plugin.event.Step;
//import io.cucumber.plugin.event.StepArgument;
//import io.cucumber.core.eventbus.EventBus;
//import tools.ds.modkit.patches.PickleStepRunPatch;
//import tools.ds.modkit.util.Reflect;
//
//import java.net.URI;
//import java.util.List;
//import java.util.UUID;
//
//public final class WrappedPickleStep implements PickleStepTestStep, TestStep {
//
//    // Simple stand-ins you control
//    public enum ExecMode { RUN, DRY_RUN, SKIP }
//    public enum StepStatus { PASSED, FAILED, SKIPPED, UNKNOWN }
//
//    private final PickleStepTestStep delegate;
//
//    // telemetry
//    private ExecMode lastExecMode = null;
//    private StepStatus status = StepStatus.UNKNOWN;
//    private long startedNanos = 0L;
//    private long durationNanos = 0L;
//    private Throwable error;
//
//    public WrappedPickleStep(PickleStepTestStep delegate) {
//        this.delegate = delegate;
//    }
//
//    // ---- Forward every public interface method directly ----
//    @Override public String getPattern() { return delegate.getPattern(); }
//    @Override public Step getStep() { return delegate.getStep(); }
//    @Override public List<Argument> getDefinitionArgument() { return delegate.getDefinitionArgument(); }
//    @Override @Deprecated public StepArgument getStepArgument() { return delegate.getStepArgument(); }
//    @Override @Deprecated public int getStepLine() { return delegate.getStepLine(); }
//    @Override public URI getUri() { return delegate.getUri(); }
//    @Override @Deprecated public String getStepText() { return delegate.getStepText(); }
//    @Override public String getCodeLocation() { return delegate.getCodeLocation(); }
//    @Override public UUID getId() { return delegate.getId(); }
//
//    // ---- Metadata accessors you can read after execution ----
//    public ExecMode getLastExecMode() { return lastExecMode; }
//    public StepStatus getStatus() { return status; }
//    public long getStartedNanos() { return startedNanos; }
//    public long getDurationNanos() { return durationNanos; }
//    public Throwable getError() { return error; }
//
//    /**
//     * Execute the ORIGINAL PickleStepTestStep#run by temporarily
//     * enabling the interceptor's "allow original" flag, and using
//     * Reflect.invokeAnyMethod to avoid depending on Cucumber's private types.
//     *
//     * @param testCase  io.cucumber.plugin.event.TestCase (public)
//     * @param bus       io.cucumber.core.eventbus.EventBus (public)
//     * @param state     io.cucumber.core.backend.TestCaseState (public)
//     * @param nativeExecutionMode  the private ExecutionMode instance you got from upstream
//     * @return the same (private) ExecutionMode object returned by the original
//     */
//    public Object runOriginal(Object testCase, EventBus bus, Object state, Object nativeExecutionMode) {
//        // Toggle your interceptor to call @SuperCall
//        try {
//            startedNanos = System.nanoTime();
//
//            // Call the original body; Reflect will choose the right run(...) by name/arity
//            Object retMode = Reflect.invokeAnyMethod(delegate, "run",
//                    testCase, bus, state, nativeExecutionMode);
//
//            durationNanos = System.nanoTime() - startedNanos;
//
//            // Map private ExecutionMode → our ExecMode via enum name()
//            String name = (String) Reflect.invokeAnyMethod(retMode, "name"); // RUN/DRY_RUN/SKIP
//            lastExecMode = mapExecMode(name);
//
//            // Try to derive status from state if available
//            Object st = Reflect.invokeAnyMethod(state, "getStatus"); // usually a public enum
//            String stName = (st != null) ? st.toString() : null;
//            status = mapStatus(stName);
//
//            // capture any error field if exposed
//            error = (Throwable) Reflect.invokeAnyMethod(state, "getError");
//
//            return retMode;
//        } finally {
//        }
//    }
//
//    private static ExecMode mapExecMode(String n) {
//        if ("RUN".equals(n)) return ExecMode.RUN;
//        if ("DRY_RUN".equals(n)) return ExecMode.DRY_RUN;
//        if ("SKIP".equals(n)) return ExecMode.SKIP;
//        return null;
//    }
//
//    private static StepStatus mapStatus(String n) {
//        if (n == null) return StepStatus.UNKNOWN;
//        switch (n) {
//            case "PASSED":  return StepStatus.PASSED;
//            case "FAILED":  return StepStatus.FAILED;
//            case "SKIPPED": return StepStatus.SKIPPED;
//            default:        return StepStatus.UNKNOWN;
//        }
//    }
//}
