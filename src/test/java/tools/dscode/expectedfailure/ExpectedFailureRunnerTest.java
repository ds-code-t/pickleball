package tools.dscode.expectedfailure;

import io.cucumber.core.runner.CurrentScenarioState;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import tools.dscode.common.exceptions.SoftRuntimeException;
import tools.dscode.testengine.DynamicSuiteEngine;
import tools.dscode.testengine.PickleballRunner;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs the expected-failure feature through the pickleball Cucumber engine.
 * The feature is not tagged {@code @all}.
 */
class ExpectedFailureRunnerTest {
    private static final String HARD = "Registered-object cleanup runs when the scenario hard-fails";
    private static final String SOFT_CLEANUP = "Registered-object cleanup runs when the scenario soft-fails";
    private static final String SOFT_FLAG = "softFail continues the step and runs the soft-failed flag";

    @Test
    void failureRescueIsNotAConsumerApi() {
        assertThrows(
                NoSuchMethodException.class,
                () -> CurrentScenarioState.class.getMethod("absorbRecordedScenarioFailure")
        );
        assertThrows(
                NoSuchMethodException.class,
                () -> CurrentScenarioState.class.getMethod("discardDeferredSoftFailure", Throwable.class)
        );
    }

    @Test
    void hardAndSoftFailuresStayFailedAndStillCleanUp() {
        Properties before = new Properties();
        before.putAll(System.getProperties());
        ExpectedFailureSteps.OBSERVATIONS.clear();
        try {
            System.setProperty("pkb_features", "classpath:expected-failure");
            System.setProperty("cucumber.features", "classpath:expected-failure");
            System.setProperty("pkb_glue", "tools.dscode.expectedfailure");
            System.setProperty("cucumber.glue", "tools.dscode.expectedfailure");
            System.setProperty("pkb_tags", "@expected-failure");
            System.setProperty("cucumber.filter.tags", "@expected-failure");
            System.setProperty("pkb_loglevel", "error");
            System.setProperty("cucumber.execution.parallel.enabled", "false");
            System.setProperty(
                    "junit.platform.discovery.includeEngines",
                    DynamicSuiteEngine.ENGINE_ID
            );
            resetRunner();

            List<Finished> finished = new ArrayList<>();
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectClasspathResource("expected-failure"))
                    .filters(EngineFilter.includeEngines(DynamicSuiteEngine.ENGINE_ID))
                    .build();
            Launcher launcher = LauncherFactory.create();
            launcher.execute(request, new TestExecutionListener() {
                @Override
                public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult result) {
                    if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL
                            || testIdentifier.isTest()) {
                        finished.add(new Finished(
                                testIdentifier.getDisplayName(),
                                testIdentifier.isTest(),
                                result
                        ));
                    }
                }
            });

            assertScenario(finished, HARD, "release 216 hard failure", false);
            assertScenario(finished, SOFT_CLEANUP, "release 216 observer", true);
            assertScenario(finished, SOFT_FLAG, "release 216 observer", true);

            ExpectedFailureSteps.Snapshot hard = observation(HARD);
            assertEquals("hard", hard.expectedCleanup);
            assertTrue(hard.cleanupRan, "Registered-object cleanup did not run for the scenario.");
            assertEquals("hard", hard.cleanupToken);
            assertTrue(hard.flagsCaptured);
            assertTrue(
                    hard.failedAtCleanup && hard.completeAtCleanup,
                    "Registered-object cleanup did not observe a hard-failed scenario."
            );

            ExpectedFailureSteps.Snapshot softCleanup = observation(SOFT_CLEANUP);
            assertEquals("failure", softCleanup.expectedCleanup);
            assertTrue(softCleanup.cleanupRan, "Registered-object cleanup did not run for the scenario.");
            assertEquals("failure", softCleanup.cleanupToken);
            assertTrue(softCleanup.softFailSawDeferred, "softFail did not record a deferred failure.");

            ExpectedFailureSteps.Snapshot softFlag = observation(SOFT_FLAG);
            assertTrue(softFlag.softFailSawDeferred, "softFail did not record a deferred failure.");
            assertTrue(softFlag.continuedAfterSoftFail, "the step after softFail did not run");
            assertEquals("yes", softFlag.flagSoft, "soft-failed flag did not run");
        } finally {
            System.setProperties(before);
            resetRunner();
            ExpectedFailureSteps.OBSERVATIONS.clear();
        }
    }

    private static void assertScenario(
            List<Finished> finished,
            String scenarioName,
            String expectedMessage,
            boolean soft
    ) {
        Finished match = null;
        for (Finished finishedTest : finished) {
            if (finishedTest.test && finishedTest.displayName.contains(scenarioName)) {
                match = finishedTest;
                break;
            }
        }
        if (match == null) {
            fail("No Cucumber scenario result for '" + scenarioName + "'. Saw " + finished);
        }
        assertEquals(
                TestExecutionResult.Status.FAILED,
                match.result.getStatus(),
                scenarioName + " " + match.result
        );
        Throwable thrown = match.result.getThrowable().orElse(null);
        assertNotNull(thrown, scenarioName);
        assertTrue(messageEquals(thrown, expectedMessage), () -> scenarioName + " failure was " + describe(thrown));
        assertEquals(soft, hasSoft(thrown), () -> scenarioName + " soft/hard mismatch: " + describe(thrown));
    }

    private static ExpectedFailureSteps.Snapshot observation(String scenarioName) {
        for (ExpectedFailureSteps.Snapshot snapshot : ExpectedFailureSteps.OBSERVATIONS.values()) {
            if (scenarioName.equals(snapshot.scenarioName)) {
                return snapshot;
            }
        }
        fail("No cleanup observation for '" + scenarioName + "'. Saw " + ExpectedFailureSteps.OBSERVATIONS);
        return null;
    }

    private static boolean messageEquals(Throwable thrown, String expected) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (expected.equals(current.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSoft(Throwable thrown) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (current instanceof SoftRuntimeException) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Throwable thrown) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (text.length() > 0) {
                text.append(" caused by ");
            }
            text.append(current.getClass().getName()).append(": ").append(current.getMessage());
        }
        return text.toString();
    }

    private static void resetRunner() {
        try {
            Field field = PickleballRunner.class.getDeclaredField("INSTANCE");
            field.setAccessible(true);
            field.set(null, null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Finished(String displayName, boolean test, TestExecutionResult result) {
        @Override
        public String toString() {
            Throwable thrown = result.getThrowable().orElse(null);
            return displayName + " test=" + test + " " + result.getStatus()
                    + (thrown == null ? "" : " " + describe(thrown));
        }
    }
}
