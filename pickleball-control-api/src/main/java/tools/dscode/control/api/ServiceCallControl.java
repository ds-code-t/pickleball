package tools.dscode.control.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.treeparsing.parsedComponents.Phrase;
import tools.dscode.coredefinitions.ServiceCallSteps;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.reporting.logging.LogForwarder.getDefaultEntry;
import static tools.dscode.common.reporting.logging.LogForwarder.setDefaultEntry;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.common.util.Reflect.setProperty;

/** Retry-friendly direct execution of existing Pickleball service-call scenarios. */
public final class ServiceCallControl {
    private static final int MAX_EVIDENCE_BYTES = 256 * 1024;

    private ServiceCallControl() {
    }

    public static ControlCallResult<ServiceCallEvidence> execute(String selector) {
        if (selector == null || selector.isBlank()) {
            return ControlCallResult.unavailable("Service-call selector must not be blank.");
        }

        CurrentScenarioState state = getCurrentScenarioState();
        if (state == null) {
            return ControlCallResult.unavailable(
                    "Service-call control requires an active Pickleball scenario."
            );
        }

        String normalizedSelector = selector.trim();
        try (ScenarioStateScope scope = new ScenarioStateScope(state, normalizedSelector)) {
            Object value;
            try {
                value = ServiceCallSteps.inlineCall(normalizedSelector, null);
            } catch (Throwable failure) {
                scope.recordFailure(failure);
                return ControlCallResult.failed(failure);
            }

            Throwable failure = scope.failure();
            if (failure != null) {
                return ControlCallResult.failed(failure);
            }

            return ControlCallResult.success(evidence(normalizedSelector, value));
        } catch (Throwable failure) {
            return ControlCallResult.failed(failure);
        }
    }

    private static ServiceCallEvidence evidence(String selector, Object value) {
        JsonNode root = value instanceof JsonNode node
                ? node
                : MAPPER.valueToTree(value);
        JsonNode request = child(root, "REQUEST");
        JsonNode configuration = child(root, "CONFIGURATION");
        JsonNode response = child(root, "RESPONSE");
        Integer statusCode = response != null && response.has("statusCode")
                ? response.path("statusCode").asInt()
                : null;
        return new ServiceCallEvidence(
                selector,
                bounded(request),
                bounded(configuration),
                bounded(response),
                statusCode
        );
    }

    private static JsonNode child(JsonNode root, String name) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode value = root.get(name);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isArray()) {
            return value.isEmpty() ? null : value.get(value.size() - 1);
        }
        return value;
    }

    private static BoundedJsonEvidence bounded(JsonNode value) {
        if (value == null) {
            return new BoundedJsonEvidence(null, 0, false);
        }
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_EVIDENCE_BYTES) {
            return new BoundedJsonEvidence(value.deepCopy(), bytes.length, false);
        }
        String text = value.toString();
        int chars = Math.min(text.length(), MAX_EVIDENCE_BYTES / 2);
        return new BoundedJsonEvidence(
                TextNode.valueOf(text.substring(0, chars) + "\n...[truncated]"),
                bytes.length,
                true
        );
    }

    /**
     * Runs a nested CALL with normal Pickleball scenario semantics while keeping
     * its cursor, failure state, and logging isolated from the paused outer scenario.
     */
    private static final class ScenarioStateScope implements AutoCloseable {
        private final CurrentScenarioState state;
        private final StepExtension previousStep;
        private final Phrase previousPhrase;
        private final boolean previousEndCurrentScenario;
        private final StepExtension previousRunAndEndStep;
        private final List<Throwable> previousStepFailures;
        private final boolean previousHardFail;
        private final boolean previousSoftFail;
        private final boolean previousComplete;
        private final Entry previousScenarioLog;
        private final Entry previousDefaultEntry;
        private final Entry controlLog;

        private ScenarioStateScope(CurrentScenarioState state, String selector) {
            this.state = state;
            this.previousStep = state.getCurrentStep();
            this.previousPhrase = state.currentPhrase;
            this.previousEndCurrentScenario = state.endCurrentScenario;
            this.previousRunAndEndStep = state.runAndEndStep;
            this.previousStepFailures = state.stepFailures;
            this.previousHardFail = booleanProperty(state, "isScenarioHardFail");
            this.previousSoftFail = booleanProperty(state, "isScenarioSoftFail");
            this.previousComplete = booleanProperty(state, "isScenarioComplete");
            this.previousScenarioLog = state.scenarioLog;
            this.previousDefaultEntry = getDefaultEntry();
            this.controlLog = previousScenarioLog
                    .child("Detached service call: " + selector)
                    .tags("Control", "Detached", "ServiceCall")
                    .start();

            state.scenarioLog = controlLog;
            state.stepFailures = new ArrayList<>();
            state.endCurrentScenario = false;
            state.runAndEndStep = null;
            setProperty(state, "isScenarioHardFail", false);
            setProperty(state, "isScenarioSoftFail", false);
            setProperty(state, "isScenarioComplete", false);
            setDefaultEntry(controlLog);
        }

        private Throwable failure() {
            return state.stepFailures.isEmpty() ? null : state.stepFailures.getFirst();
        }

        private void recordFailure(Throwable failure) {
            if (failure != null && state.stepFailures.isEmpty()) {
                state.stepFailures.add(failure);
            }
            if (failure != null && controlLog.stoppedAt == null) {
                controlLog.fail(failure.getMessage());
            }
        }

        @Override
        public void close() {
            if (controlLog.stoppedAt == null) {
                controlLog.stop();
            }
            state.scenarioLog = previousScenarioLog;
            state.stepFailures = previousStepFailures;
            state.endCurrentScenario = previousEndCurrentScenario;
            state.runAndEndStep = previousRunAndEndStep;
            state.currentPhrase = previousPhrase;
            setProperty(state, "currentStep", previousStep);
            setProperty(state, "isScenarioHardFail", previousHardFail);
            setProperty(state, "isScenarioSoftFail", previousSoftFail);
            setProperty(state, "isScenarioComplete", previousComplete);
            setDefaultEntry(previousDefaultEntry);
        }

        private static boolean booleanProperty(CurrentScenarioState state, String name) {
            return Boolean.TRUE.equals(getProperty(state, name));
        }
    }
}
