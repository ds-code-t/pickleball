package com.example.pickleball;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.StepData;
import io.cucumber.core.runner.StepExtension;
import org.junit.jupiter.api.Test;
import tools.dscode.common.exceptions.StepCreationException;
import tools.dscode.common.gherkinoperations.DynamicExecution;
import tools.dscode.control.api.ControlCallResult;
import tools.dscode.control.api.ControlCallStatus;
import tools.dscode.control.api.DynamicControl;

import org.openqa.selenium.remote.RemoteWebDriver;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.reporting.logging.Level;
import tools.dscode.common.reporting.logging.LogForwarder;
import tools.dscode.coredefinitions.BrowserSteps;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.common.util.Reflect.setProperty;

public class AreaBControlFlowChecks {

    @Test
    void untilExhaustedByCountHardFails() {
        Throwable exhausted = runUntil(", until \"\" equals \"done\":", 0, Duration.ofHours(1));
        String detail = detail(exhausted);
        assertTrue(detail.contains("Until loop exhausted"), detail);
        assertTrue(detail.contains("stepRepeatMaxCount"), detail);
        assertFalse(requireScenario().isScenarioFailed());
    }

    @Test
    void untilExhaustedByTimeHardFails() {
        Throwable exhausted = runUntil(", until \"\" equals \"done\":", 100_000, Duration.ofMillis(1));
        String detail = detail(exhausted);
        assertTrue(detail.contains("Step exceeded pkb_stepMaxTime"), detail);
        assertFalse(detail.contains("Until loop exhausted"), detail);
        assertFalse(requireScenario().isScenarioFailed());
    }

    @Test
    void elementWaitIgnoresRepeatCountAndFailsOnTime() {
        navigateHome();
        ControlCallResult<Object> result = waitForMissingElement(0, Duration.ofMillis(1));
        String detail = detail(result);
        assertEquals(ControlCallStatus.FAILED, result.status(), detail);
        assertTrue(detail.contains("Step exceeded pkb_stepMaxTime"), detail);
        assertFalse(detail.contains("stepRepeatMaxCount"), detail);
        assertFalse(requireScenario().isScenarioFailed());
    }

    @Test
    void elementWaitHonorsRepeatTime() {
        navigateHome();
        ControlCallResult<Object> result = waitForMissingElement(100_000, Duration.ofMillis(1));
        String detail = detail(result);
        assertEquals(ControlCallStatus.FAILED, result.status(), detail);
        assertTrue(detail.contains("Step exceeded pkb_stepMaxTime"), detail);
        assertFalse(detail.contains("stepRepeatMaxCount"), detail);
        assertFalse(requireScenario().isScenarioFailed());
    }

    @Test
    void softWaitContinuesWhenTheGherkinTimePassesWithoutAWarning() {
        navigateHome();
        Duration previousTimeout = StepData.globalTimeoutSeconds;
        StepData.globalTimeoutSeconds = Duration.ofSeconds(40);
        try {
            assertSoftWaitSucceeds(", wait the \"No Such Area B\" Button, or 0 seconds");
            assertSoftWaitSucceeds(", wait 0 seconds, or the \"No Such Area B\" Button");
        } finally {
            StepData.globalTimeoutSeconds = previousTimeout;
        }
    }

    @Test
    void longerGherkinWaitCannotExtendTheRunVarCap() {
        navigateHome();
        ControlCallResult<Object> result = waitForStep(
                ", wait the \"No Such Area B\" Button, or 1 hour",
                100_000,
                Duration.ofMillis(1)
        );
        String detail = detail(result);
        assertEquals(ControlCallStatus.FAILED, result.status(), detail);
        assertTrue(detail.contains("Step exceeded pkb_stepMaxTime"), detail);
        assertFalse(requireScenario().isScenarioFailed());
    }

    @Test
    void untilConditionSkipsPageSyncAndImplicitWait() {
        navigateHome();
        RemoteWebDriver driver = BrowserSteps.getCurrentDriver();
        Duration previousImplicit = driver.manage().timeouts().getImplicitWaitTimeout();
        try {
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(12));
            long started = System.nanoTime();
            Throwable exhausted = runUntil(
                    ", until the \"No Such Area B\" Button is displayed:",
                    1,
                    Duration.ofMinutes(5)
            );
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            String detail = detail(exhausted);
            assertTrue(detail.contains("pkb_stepRepeatMaxCount"), detail);
            assertTrue(elapsedMs < 6000, "until condition took " + elapsedMs + "ms");
            assertEquals(Duration.ofSeconds(12), driver.manage().timeouts().getImplicitWaitTimeout());
        } finally {
            driver.manage().timeouts().implicitlyWait(previousImplicit);
        }
    }

    @Test
    void emptyCommaStepIsAClearError() {
        CurrentScenarioState state = requireScenario();
        ControlCallResult<Object> result = DynamicControl.executeStep(",");
        String detail = detail(result);
        assertEquals(ControlCallStatus.FAILED, result.status(), detail);
        assertTrue(detail.contains("no phrase"), detail);
        assertFalse(detail.contains("IndexOutOfBoundsException"), detail);
        assertFalse(state.isScenarioFailed());
    }

    @Test
    void softFailThrowsAndLaterObserverStepsStillRun() {
        CurrentScenarioState state = requireScenario();
        ControlCallResult<Object> result = DynamicControl.executeStep(
                "SOFT FAIL SCENARIO \"area b soft warning\""
        );
        String detail = detail(result);
        assertEquals(ControlCallStatus.FAILED, result.status(), detail);
        assertTrue(detail.contains("area b soft warning"), detail);
        assertFalse(state.isScenarioFailed());
        ControlCallResult<Object> save = DynamicControl.executeStep(
                ", save \"continued\" as \"areaBSoftObserver\""
        );
        assertTrue(save.successful(), () -> String.valueOf(save.error()));
        ControlCallResult<Object> ensure = DynamicControl.executeStep(
                ", ensure \"<areaBSoftObserver>\" equals \"continued\""
        );
        assertTrue(ensure.successful(), () -> String.valueOf(ensure.error()));
        assertFalse(state.isScenarioFailed());
    }

    @Test
    void blankDynamicArgumentIsNotReportedAsWithArgument() {
        StepCreationException blank = assertStepCreation("this step does not exist", "");
        assertFalse(blank.getMessage().contains("with argument"), blank.getMessage());
        StepCreationException present = assertStepCreation("this step does not exist", "arg");
        assertTrue(present.getMessage().contains("with argument 'arg'"), present.getMessage());
    }

    @Test
    void alwaysRunSiblingRunsAfterUntilExhaustsAndAPlainSiblingDoesNot() {
        CurrentScenarioState state = requireScenario();
        StepExtension previousStep = state.getCurrentStep();
        int previousIterations = StepData.globalMaxIterations;
        Duration previousTimeout = StepData.globalTimeoutSeconds;
        int previousFailures = state.stepFailures.size();
        StepData.globalMaxIterations = 0;
        StepData.globalTimeoutSeconds = Duration.ofHours(1);
        save("untouched", "untilAlwaysSibling");
        save("untouched", "untilPlainSibling");
        try {
            StepExtension untilStep = observedStep(", until \"\" equals \"done\":");
            StepExtension always = observedStep(
                    "ALWAYS RUN: , save \"ran\" as \"untilAlwaysSibling\""
            );
            StepExtension plain = observedStep(", save \"ran\" as \"untilPlainSibling\"");
            chain(state, untilStep, always, plain);
            RuntimeException exhausted = assertThrows(
                    RuntimeException.class,
                    () -> state.runStep(untilStep)
            );
            String detail = detail(exhausted);
            assertTrue(detail.contains("Until loop exhausted"), detail);
            assertTrue(state.isScenarioFailed(), detail);
            assertSaved("untilAlwaysSibling", "ran");
            assertSaved("untilPlainSibling", "untouched");
        } finally {
            StepData.globalMaxIterations = previousIterations;
            StepData.globalTimeoutSeconds = previousTimeout;
            while (state.stepFailures.size() > previousFailures) {
                state.stepFailures.remove(state.stepFailures.size() - 1);
            }
            restoreScenario(state, previousStep);
        }
    }

    @Test
    void flaggedChildrenRunAfterAHardFailAndASiblingIsSkipped() {
        CurrentScenarioState state = requireScenario();
        StepExtension previousStep = state.getCurrentStep();
        save("untouched", "flagHardAlwaysNested");
        save("untouched", "flagHardAlwaysInline");
        save("untouched", "flagHardFailNested");
        save("untouched", "flagHardFailInline");
        save("untouched", "flagHardSoftNested");
        save("untouched", "flagHardSoftInline");
        save("untouched", "flagHardSibling");
        try {
            // No @all scenario can end failed: the suite has no expected-failure tag.
            // DynamicControl contains the real failure, the same way the soft-fail observer does.
            // runStep would publish that failure and fail the parent scenario, so the runner
            // flags a hard failure sets are applied here and the flag steps go through runStep.
            ControlCallResult<Object> failed = DynamicControl.executeStep("FAIL SCENARIO \"flag proof\"");
            assertEquals(ControlCallStatus.FAILED, failed.status(), detail(failed));
            assertFalse(state.isScenarioFailed());
            setProperty(state, "isScenarioHardFail", true);
            setProperty(state, "isScenarioSoftFail", false);
            setProperty(state, "isScenarioComplete", true);
            StepExtension alwaysNested = flagWithNestedChild(
                    "ALWAYS RUN:",
                    ", save \"ran\" as \"flagHardAlwaysNested\""
            );
            StepExtension alwaysInline = observedStep(
                    "ALWAYS RUN: , save \"ran\" as \"flagHardAlwaysInline\""
            );
            assertEquals(1, alwaysInline.childSteps.size());
            StepExtension failedNested = flagWithNestedChild(
                    "RUN IF SCENARIO FAILED:",
                    ", save \"ran\" as \"flagHardFailNested\""
            );
            StepExtension failedInline = observedStep(
                    "RUN IF SCENARIO FAILED: , save \"ran\" as \"flagHardFailInline\""
            );
            assertEquals(1, failedInline.childSteps.size());
            StepExtension softNested = flagWithNestedChild(
                    "RUN IF SCENARIO SOFT FAILED:",
                    ", save \"ran\" as \"flagHardSoftNested\""
            );
            StepExtension softInline = observedStep(
                    "RUN IF SCENARIO SOFT FAILED: , save \"ran\" as \"flagHardSoftInline\""
            );
            StepExtension sibling = observedStep(", save \"ran\" as \"flagHardSibling\"");
            chain(state, alwaysNested, alwaysInline, failedNested, failedInline, softNested, softInline, sibling);
            state.runStep(alwaysNested);
            assertSaved("flagHardAlwaysNested", "ran");
            assertSaved("flagHardAlwaysInline", "ran");
            assertSaved("flagHardFailNested", "ran");
            assertSaved("flagHardFailInline", "ran");
            assertSaved("flagHardSoftNested", "untouched");
            assertSaved("flagHardSoftInline", "untouched");
            assertSaved("flagHardSibling", "untouched");
        } finally {
            restoreScenario(state, previousStep);
        }
    }

    @Test
    void softFailedChildrenRunAfterASoftFailAndAHardFailedChildDoesNot() {
        CurrentScenarioState state = requireScenario();
        StepExtension previousStep = state.getCurrentStep();
        save("untouched", "flagSoftNested");
        save("untouched", "flagSoftInline");
        save("untouched", "flagSoftFailNested");
        save("untouched", "flagSoftHardNested");
        save("untouched", "flagSoftSibling");
        try {
            ControlCallResult<Object> failed = DynamicControl.executeStep(
                    "SOFT FAIL SCENARIO \"flag soft\""
            );
            assertEquals(ControlCallStatus.FAILED, failed.status(), detail(failed));
            assertFalse(state.isScenarioFailed());
            setProperty(state, "isScenarioHardFail", false);
            setProperty(state, "isScenarioSoftFail", true);
            setProperty(state, "isScenarioComplete", false);
            StepExtension softNested = flagWithNestedChild(
                    "RUN IF SCENARIO SOFT FAILED:",
                    ", save \"ran\" as \"flagSoftNested\""
            );
            StepExtension softInline = observedStep(
                    "RUN IF SCENARIO SOFT FAILED: , save \"ran\" as \"flagSoftInline\""
            );
            assertEquals(1, softInline.childSteps.size());
            StepExtension failedNested = flagWithNestedChild(
                    "RUN IF SCENARIO FAILED:",
                    ", save \"ran\" as \"flagSoftFailNested\""
            );
            StepExtension hardNested = flagWithNestedChild(
                    "RUN IF SCENARIO HARD FAILED:",
                    ", save \"ran\" as \"flagSoftHardNested\""
            );
            StepExtension sibling = observedStep(", save \"ran\" as \"flagSoftSibling\"");
            chain(state, softNested, softInline, failedNested, hardNested, sibling);
            state.runStep(softNested);
            assertSaved("flagSoftNested", "ran");
            assertSaved("flagSoftInline", "ran");
            assertSaved("flagSoftFailNested", "ran");
            assertSaved("flagSoftHardNested", "untouched");
            // A soft failure does not mark the scenario complete, so an unflagged sibling still runs.
            assertSaved("flagSoftSibling", "ran");
        } finally {
            restoreScenario(state, previousStep);
        }
    }

    private static void save(String value, String key) {
        ControlCallResult<Object> result = DynamicControl.executeStep(
                ", save \"" + value + "\" as \"" + key + "\""
        );
        assertTrue(result.successful(), () -> String.valueOf(result.error()));
    }

    private static void assertSaved(String key, String expected) {
        ControlCallResult<Object> result = DynamicControl.executeStep(
                ", ensure \"<" + key + ">\" equals \"" + expected + "\""
        );
        assertTrue(result.successful(), () -> key + " expected " + expected + " but " + result.error());
    }

    private static StepExtension observedStep(String stepText) {
        ControlCallResult<StepExtension> created = DynamicControl.createStep(stepText);
        assertTrue(created.successful(), () -> stepText + " " + created.error());
        return created.value();
    }

    private static void runObserved(CurrentScenarioState state, String stepText) {
        StepExtension step = observedStep(stepText);
        step.parentStep = state.getCurrentStep();
        state.runStep(step);
    }

    private static StepExtension flagWithNestedChild(String flagText, String childText) {
        StepExtension flag = observedStep(flagText);
        StepExtension child = flag.createNewStepExtension(childText);
        flag.addChildStep(child);
        return flag;
    }

    private static void chain(CurrentScenarioState state, StepExtension... steps) {
        StepExtension parent = state.getCurrentStep();
        assertNotNull(parent);
        StepExtension previous = null;
        for (StepExtension step : steps) {
            step.parentStep = parent;
            if (previous != null) {
                previous.nextSibling = step;
                step.previousSibling = previous;
            }
            previous = step;
        }
    }

    private static void restoreScenario(CurrentScenarioState state, StepExtension previousStep) {
        state.stepFailures.clear();
        state.endCurrentScenario = false;
        setProperty(state, "currentStep", previousStep);
        setProperty(state, "isScenarioHardFail", false);
        setProperty(state, "isScenarioSoftFail", false);
        setProperty(state, "isScenarioComplete", false);
    }

    private static Throwable runUntil(String stepText, int maxIterations, Duration maxTime) {
        CurrentScenarioState state = requireScenario();
        StepExtension previousStep = state.getCurrentStep();
        boolean previousEnd = state.endCurrentScenario;
        int previousFailures = state.stepFailures.size();
        int previousIterations = StepData.globalMaxIterations;
        Duration previousTimeout = StepData.globalTimeoutSeconds;
        StepData.globalMaxIterations = maxIterations;
        StepData.globalTimeoutSeconds = maxTime;
        try {
            ControlCallResult<StepExtension> created = DynamicControl.createStep(stepText);
            assertTrue(created.successful(), () -> String.valueOf(created.error()));
            state.runStep(created.value());
            throw new AssertionError("until loop returned without exhausting its limit");
        } catch (Throwable thrown) {
            if (thrown instanceof AssertionError && thrown.getMessage() != null
                    && thrown.getMessage().startsWith("until loop returned")) {
                throw thrown;
            }
            return thrown;
        } finally {
            StepData.globalMaxIterations = previousIterations;
            StepData.globalTimeoutSeconds = previousTimeout;
            while (state.stepFailures.size() > previousFailures) {
                state.stepFailures.remove(state.stepFailures.size() - 1);
            }
            state.endCurrentScenario = previousEnd;
            setProperty(state, "currentStep", previousStep);
            setProperty(state, "isScenarioHardFail", false);
            setProperty(state, "isScenarioSoftFail", false);
            setProperty(state, "isScenarioComplete", false);
        }
    }

    private static void navigateHome() {
        ControlCallResult<Object> result = DynamicControl.executeStep("navigate to: URL.home");
        assertTrue(result.successful(), () -> String.valueOf(result.error()));
    }

    private static ControlCallResult<Object> waitForMissingElement(int maxIterations, Duration maxTime) {
        return waitForStep(", wait the \"No Such Area B\" Button", maxIterations, maxTime);
    }

    private static ControlCallResult<Object> waitForStep(String step, int maxIterations, Duration maxTime) {
        int previousIterations = StepData.globalMaxIterations;
        Duration previousTimeout = StepData.globalTimeoutSeconds;
        StepData.globalMaxIterations = maxIterations;
        StepData.globalTimeoutSeconds = maxTime;
        try {
            return DynamicControl.executeStep(step);
        } finally {
            StepData.globalMaxIterations = previousIterations;
            StepData.globalTimeoutSeconds = previousTimeout;
        }
    }

    private static void assertSoftWaitSucceeds(String step) {
        Set<String> before = entryIds();
        ControlCallResult<Object> result = DynamicControl.executeStep(step);
        String detail = detail(result);
        assertTrue(result.successful(), detail);
        assertFalse(requireScenario().isScenarioFailed(), detail);
        String log = newTexts(before);
        assertFalse(log.contains("Skipping Phrase"), log);
        assertFalse(log.contains("pkb_stepMaxTime"), log);
        assertFalse(newLevels(before).contains(Level.WARN), log);
    }

    private static Set<String> entryIds() {
        Set<String> ids = new HashSet<>();
        collectIds(LogForwarder.getDefaultEntry(), ids);
        return ids;
    }

    private static void collectIds(Entry entry, Set<String> ids) {
        if (entry == null || !ids.add(entry.id)) {
            return;
        }
        for (Entry child : entry.children) {
            collectIds(child, ids);
        }
    }

    private static String newTexts(Set<String> before) {
        StringBuilder joined = new StringBuilder();
        appendNew(LogForwarder.getDefaultEntry(), before, joined, new HashSet<>(), null);
        return joined.toString();
    }

    private static Set<Level> newLevels(Set<String> before) {
        Set<Level> levels = new HashSet<>();
        appendNew(LogForwarder.getDefaultEntry(), before, new StringBuilder(), new HashSet<>(), levels);
        return levels;
    }

    private static void appendNew(
            Entry entry,
            Set<String> before,
            StringBuilder joined,
            Set<String> seen,
            Set<Level> levels
    ) {
        if (entry == null || !seen.add(entry.id)) {
            return;
        }
        if (!before.contains(entry.id)) {
            if (entry.text != null) {
                if (!joined.isEmpty()) {
                    joined.append('\n');
                }
                joined.append(entry.text);
            }
            if (levels != null && entry.level != null) {
                levels.add(entry.level);
            }
        }
        for (Entry child : entry.children) {
            appendNew(child, before, joined, seen, levels);
        }
    }

    private static StepCreationException assertStepCreation(String stepText, String argument) {
        try {
            DynamicExecution.runCustomStep(stepText, argument);
        } catch (StepCreationException exception) {
            return exception;
        } catch (RuntimeException exception) {
            throw new AssertionError(
                    "expected StepCreationException but got " + exception,
                    exception
            );
        }
        throw new AssertionError("expected step creation to fail for '" + stepText + "'");
    }

    private static String detail(Throwable thrown) {
        assertNotNull(thrown);
        StringBuilder text = new StringBuilder();
        Throwable current = thrown;
        while (current != null) {
            text.append(current.getClass().getName()).append(": ");
            text.append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return text.toString();
    }

    private static String detail(ControlCallResult<Object> result) {
        if (result.error() == null) {
            return String.valueOf(result.status());
        }
        return result.error().message() + "\n" + result.error().stackTrace();
    }

    private static CurrentScenarioState requireScenario() {
        CurrentScenarioState state = getCurrentScenarioState();
        assertNotNull(state);
        assertFalse(state.isScenarioFailed(), () -> String.valueOf(getProperty(state, "isScenarioHardFail")));
        return state;
    }
}
