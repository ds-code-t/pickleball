//package io.cucumber.core.runner;
//
//import io.cucumber.core.eventbus.EventBus;
//import io.cucumber.core.gherkin.messages.GherkinMessagesStep;
//import io.cucumber.plugin.event.HookTestStep;
//import io.cucumber.plugin.event.Location;
//import io.cucumber.plugin.event.Result;
//import io.cucumber.plugin.event.Status;
//import io.cucumber.plugin.event.TestStep;
//
//import java.net.URI;
//import java.time.Duration;
//import java.time.Instant;
//import java.util.List;
//import java.util.Objects;
//import java.util.UUID;
//
//import static io.cucumber.query.Query.setCustomStepMap;
//import static tools.dscode.extensions.ScenarioStep.createScenarioStep;
//import static io.cucumber.core.runner.ScenarioState.getBus;
//import static io.cucumber.core.runner.ScenarioState.getTestCaseState;
//
///**
// * Delegates all io.cucumber.plugin.event.TestCase calls to a wrapped
// * io.cucumber.core.runner.TestCase instance. Lives in the same package so it
// * can also forward package-private methods like run(EventBus).
// */
//public final class TestCaseExtension extends TestCase implements io.cucumber.plugin.event.TestCase {
//
//    private final TestCase delegate;
//    private final TestCaseState testCaseState;
//    private final EventBus bus;
//
//    public TestCaseExtension(TestCase delegate) {
//        super(delegate.getId(),
//            delegate.testSteps,
//            delegate.beforeHooks,
//            delegate.afterHooks,
//            delegate.pickle,
//            false);
//        this.delegate = Objects.requireNonNull(delegate, "delegate");
//        this.bus = getBus();
//        this.testCaseState = new TestCaseState(bus, bus.generateId(), delegate);
//    }
//
//    /**
//     * Convenience: forwards to the package-private TestCase#run(EventBus).
//     */
//    public void run(EventBus bus) {
//        printDebug("@@runTextCaseExtension");
//        ExecutionMode nextExecutionMode = delegate.executionMode;
//        emitTestCaseMessage(bus);
//
//        Instant start = bus.getInstant();
//        UUID executionId = bus.generateId();
//        emitTestCaseStarted(bus, start, executionId);
//
//        for (HookTestStep before : beforeHooks) {
//            nextExecutionMode = ((io.cucumber.core.runner.TestStep) before)
//                    .run(delegate, bus, testCaseState, executionMode)
//                    .next(nextExecutionMode);
//        }
//
//        printDebug("@@testCaseState: " + testCaseState);
//        printDebug("@@getTestCaseState(): " + getTestCaseState());
//        ScenarioStep root = createScenarioStep(this, true);
//        printDebug("@@root-newPickleStepTestStep1: " + root.getStepText());
//        printDebug("@@root-newPickleStepTestStep2: " + ((GherkinMessagesStep) root.step).getText());
//        printDebug("@@root-newPickleStepTestStep3: " + ((GherkinMessagesStep) root.step).pickleStep.text);
//
//        printDebug("@@root nextExecutionMode: " + nextExecutionMode);
//        printDebug("@@g root.getClass(): " + root.getClass());
//        printDebug("@@g root.delegate.getClass(): " + root.delegate.getClass());
//        printDebug("@@root: " + root.getStepText());
//        printDebug("@@root delgate " + root.delegate.getStepText());
//        printDebug("@@root definitionMatch " + root.definitionMatch);
//        // root.executeStep(delegate, bus, testCaseState, nextExecutionMode);
//        // root.putOverride(StepExtension.KEY_STEP_TEXT, ROOT_STEP);
//        testSteps.get(2).run(delegate, bus, testCaseState, nextExecutionMode);
//
//        root.run(delegate, bus, testCaseState, nextExecutionMode);
//        root.delegate.run(delegate, bus, testCaseState, nextExecutionMode);
//
//        testSteps.get(2).run(delegate, bus, testCaseState, nextExecutionMode);
//        for (PickleStepTestStep step : testSteps) {
//            nextExecutionMode = step
//                    .run(delegate, bus, testCaseState, nextExecutionMode)
//                    .next(nextExecutionMode);
//        }
//
//        for (HookTestStep after : afterHooks) {
//            nextExecutionMode = ((io.cucumber.core.runner.TestStep) after)
//                    .run(delegate, bus, testCaseState, executionMode)
//                    .next(nextExecutionMode);
//        }
//
//        Instant stop = bus.getInstant();
//        Duration duration = Duration.between(start, stop);
//        Status status = Status.valueOf(testCaseState.getStatus().name());
//        Result result = new Result(status, duration, testCaseState.getError());
//        emitTestCaseFinished(bus, executionId, stop, result);
//    }
//
//    public void registerStep(PickleStepTestStep pickleStepTestStep) {
//        io.cucumber.messages.types.TestStep testStep = createTestStep(pickleStepTestStep);
//        setCustomStepMap(testStep);
//    }
//
//    @Override
//    public Integer getLine() {
//        return delegate.getLine();
//    }
//
//    @Override
//    public Location getLocation() {
//        return delegate.getLocation();
//    }
//
//    @Override
//    public String getKeyword() {
//        return delegate.getKeyword();
//    }
//
//    @Override
//    public String getName() {
//        return delegate.getName();
//    }
//
//    @Override
//    public String getScenarioDesignation() {
//        return delegate.getScenarioDesignation();
//    }
//
//    @Override
//    public List<String> getTags() {
//        return delegate.getTags();
//    }
//
//    @Override
//    public List<TestStep> getTestSteps() {
//        return delegate.getTestSteps();
//    }
//
//    @Override
//    public URI getUri() {
//        return delegate.getUri();
//    }
//
//    @Override
//    public UUID getId() {
//        return delegate.getId();
//    }
//
//    // Optional but handy:
//    @Override
//    public String toString() {
//        return "DelegatingTestCase{" + delegate + "}";
//    }
//
//    @Override
//    public int hashCode() {
//        return delegate.hashCode();
//    }
//
//    @Override
//    public boolean equals(Object obj) {
//        if (this == obj)
//            return true;
//        if (!(obj instanceof TestCaseExtension other))
//            return false;
//        return delegate.equals(other.delegate);
//    }
//}
