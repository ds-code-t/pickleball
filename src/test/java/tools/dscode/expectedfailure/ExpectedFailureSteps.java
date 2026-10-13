package tools.dscode.expectedfailure;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.GlobalState;
import io.cucumber.java.en.Given;
import tools.dscode.coredefinitions.ObjectRegistrationSteps;

import java.util.concurrent.ConcurrentHashMap;

import static tools.dscode.Values.getValue;
import static tools.dscode.Values.softFail;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;

/**
 * Glue for expected-failure scenarios. Observations are JVM-wide and keyed by
 * the running scenario id so a later JUnit assertion does not read a
 * ThreadLocal from another phase.
 */
public final class ExpectedFailureSteps {
    static final ConcurrentHashMap<String, Snapshot> OBSERVATIONS = new ConcurrentHashMap<>();

    static Snapshot current() {
        CurrentScenarioState state = GlobalState.getCurrentScenarioState();
        if (state == null) {
            throw new IllegalStateException("expected-failure glue requires a running scenario");
        }
        return OBSERVATIONS.computeIfAbsent(
                state.id.toString(),
                id -> new Snapshot(id, state.scenarioName)
        );
    }

    @Given("^RUN EXPECTED FAILURE REGISTER CLEANUP (.+)$")
    public static void registerCleanup(String token) {
        ObjectNode config = MAPPER.createObjectNode();
        String pathKey = "expected.failure.marker." + token;
        config.put(ObjectRegistrationSteps.pathKeyProperty, pathKey);
        config.put("constructor", "EXPECTED_FAILURE_MARKER");
        config.put(
                ObjectRegistrationSteps.cleanupProperty,
                "MARK_EXPECTED_FAILURE: " + token
        );
        ObjectRegistrationSteps.registerObjectAndConfig(pathKey, new Marker(token), config);
        Snapshot snapshot = current();
        snapshot.expectedCleanup = token;
    }

    @Given(ObjectRegistrationSteps.objAction + "MARK_EXPECTED_FAILURE: (.+)$")
    public static void markExpectedFailure(String token, Object registered) {
        if (!(registered instanceof Marker marker) || !token.equals(marker.token)) {
            throw new AssertionError("Registered-object cleanup did not receive the scenario object.");
        }
        CurrentScenarioState state = GlobalState.getCurrentScenarioState();
        if (state == null) {
            throw new AssertionError("Registered-object cleanup did not run for the scenario.");
        }
        Snapshot snapshot = current();
        snapshot.cleanupRan = true;
        snapshot.cleanupToken = token.trim();
        snapshot.flagsCaptured = true;
        snapshot.failedAtCleanup = state.isScenarioFailed();
        snapshot.completeAtCleanup = state.isScenarioComplete();
        if ("hard".equals(token.trim())
                && (!snapshot.failedAtCleanup || !snapshot.completeAtCleanup)) {
            throw new AssertionError(
                    "Registered-object cleanup did not observe a hard-failed scenario."
            );
        }
    }

    @Given("^RUN EXPECTED FAILURE SOFT FAIL$")
    public static void softFailStep() {
        softFail("release 216 observer");
        CurrentScenarioState state = GlobalState.getCurrentScenarioState();
        if (state == null
                || state.latestDeferredSoftFailure() == null
                || state.deferredScenarioFailure() == null
                || !state.isScenarioFailed()) {
            throw new AssertionError("softFail did not record a deferred failure.");
        }
        current().softFailSawDeferred = true;
    }

    @Given("^RUN EXPECTED FAILURE NOTE CONTINUED$")
    public static void noteContinued() {
        current().continuedAfterSoftFail = true;
    }

    @Given("^RUN EXPECTED FAILURE NOTE SOFT FLAG$")
    public static void noteSoftFlag() {
        String flag = String.valueOf(getValue("flagSoft"));
        current().flagSoft = flag;
        if (!"yes".equals(flag)) {
            throw new AssertionError("soft-failed flag did not run");
        }
    }

    static final class Snapshot {
        final String scenarioId;
        final String scenarioName;
        String expectedCleanup;
        String cleanupToken;
        boolean cleanupRan;
        boolean flagsCaptured;
        boolean failedAtCleanup;
        boolean completeAtCleanup;
        boolean softFailSawDeferred;
        boolean continuedAfterSoftFail;
        String flagSoft;

        Snapshot(String scenarioId, String scenarioName) {
            this.scenarioId = scenarioId;
            this.scenarioName = scenarioName;
        }
    }

    private static final class Marker {
        private final String token;

        private Marker(String token) {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("Cleanup token is required.");
            }
            this.token = token;
        }

        @Override
        public String toString() {
            return "expected-failure-marker";
        }
    }
}
