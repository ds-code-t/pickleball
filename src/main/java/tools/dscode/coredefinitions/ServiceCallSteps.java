package tools.dscode.coredefinitions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.core.runner.StepBase;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.servicecalls.RestAssuredUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.GlobalConstants.ALWAYS_RUN;
import static tools.dscode.common.mappings.MappingProcessor.getRunMap;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.reporting.logging.LogForwarder.logInfo;
import static tools.dscode.common.variables.RunVars.resolveFromVars;

/**
 * Cucumber-facing service-call definitions.
 *
 * <p>Reusable service-call scenarios build their complete working object in
 * their default ScenarioStep NodeMap. REQUEST and CONFIGURATION are inputs;
 * RESPONSE is written by EXECUTE SERVICE CALL. A synthetic finalizer sibling
 * copies the completed root object into the calling scenario's RunMap.</p>
 */
public class ServiceCallSteps extends CoreSteps {

    static final String DEFAULT_CALLS_PATH = "src/test/resources/calls";
    static final String CALL_KEY = "Call Key";
    static final String SCENARIO_NAME = "SCENARIO NAME";

    static final String REQUEST = "REQUEST";
    static final String CONFIGURATION = "CONFIGURATION";
    static final String RESPONSE = "RESPONSE";

    private static final String FINALIZER_STEP_PREFIX =
            "_RUN_SERVICE_CALL_FINALIZER key64:";

    @Given("^(?:\"([^\"]+)\"\\s+)?SERVICE CALLS?:?(.*)?$")
    public static void serviceCalls(
            String inlineServiceCallObjectName,
            String inlineTags,
            DataTable dataTable
    ) {
        StepExtension triggerStep = getRunningStep();

        ModularScenarios.populateRunScenariosStep(
                triggerStep,
                inlineTags,
                dataTable,
                callsPath(),
                "service call",
                null,
                (scenarioStep, passedValues) -> createFinalizer(
                        triggerStep,
                        scenarioStep,
                        passedValues,
                        inlineServiceCallObjectName
                )
        );
    }

    @Given("^EXECUTE SERVICE CALL$")
    public static void executeServiceCall() {
        ScenarioStep scenarioStep = scenarioStep(getRunningStep());
        NodeMap serviceCallMap = scenarioStep.getDefaultStepNodeMap();
        ObjectNode serviceCallObject = serviceCallMap.getRoot();

        // This empty object remains available if validation/execution throws or
        // REST Assured returns no Response instance.
        serviceCallObject.set(RESPONSE, MAPPER.createObjectNode());

        ObjectNode request = requiredObject(serviceCallMap, REQUEST);
        ObjectNode configuration = optionalObject(serviceCallMap, CONFIGURATION);

        String method = request.path("method")
                .asText("GET")
                .trim()
                .toUpperCase(Locale.ROOT);
        if (method.isBlank()) {
            method = "GET";
        }

        String endpoint = request.path("endpoint").asText("").trim();
        if (endpoint.isBlank()) {
            throw new IllegalArgumentException(
                    "The service-call REQUEST must contain a non-blank endpoint"
            );
        }

        long started = System.nanoTime();

        try (PrintStream restLog = new PrintStream(
                new LogInfoOutputStream(),
                true,
                StandardCharsets.UTF_8
        )) {
            logInfo("REST Assured service call request: " + request);
            if (!configuration.isEmpty()) {
                logInfo("REST Assured service call configuration: " + configuration);
            }

            RequestSpecification specification = RestAssuredUtil.buildRequest(
                    request,
                    configuration
            );
            RestAssuredUtil.logRequestAndResponse(specification, restLog);

            Response response = RestAssuredUtil.execute(specification, method, endpoint);
            ObjectNode responseNode = RestAssuredUtil.extractResponse(response);

            // Preserve {} when there was no Response at all.
            if (!responseNode.isEmpty()) {
                responseNode.put("method", method);
            }
            serviceCallObject.set(RESPONSE, responseNode);

            logInfo(
                    "REST Assured service call completed in "
                            + elapsedMillis(started)
                            + " ms"
            );
        } catch (RuntimeException exception) {
            logInfo(
                    "REST Assured service call failed after "
                            + elapsedMillis(started)
                            + " ms: "
                            + exception
            );
            throw exception;
        }
    }

    /**
     * Internal synthetic step. It is inserted immediately after its component
     * ScenarioStep and marked ALWAYS_RUN.
     */
    @Given("^_RUN_SERVICE_CALL_FINALIZER key64:([A-Za-z0-9_-]+)$")
    public static void finalizeServiceCall(String encodedCallKey) {
        StepExtension finalizerStep = getRunningStep();

        if (!(finalizerStep.previousSibling instanceof ScenarioStep serviceCallScenario)) {
            throw new IllegalStateException(
                    "The service-call finalizer must immediately follow a ScenarioStep"
            );
        }

        String callKey = decodeKey(encodedCallKey).trim();
        if (callKey.isBlank()) {
            throw new IllegalStateException("The service-call finalizer has no object key");
        }

        ObjectNode serviceCallObject = serviceCallScenario
                .getDefaultStepNodeMap()
                .getRoot();

        JsonNode response = serviceCallObject.get(RESPONSE);
        if (response == null || response.isNull()) {
            serviceCallObject.set(RESPONSE, MAPPER.createObjectNode());
        } else if (!response.isObject()) {
            throw new IllegalStateException(
                    "The service-call RESPONSE property must be an object"
            );
        }

        // Ordinary NodeMap put behavior intentionally handles repeated keys.
        getRunMap().put(callKey, serviceCallObject);
    }

    public static ScenarioStep scenarioStep(StepBase step) {
        if (step instanceof ScenarioStep scenarioStep) {
            return scenarioStep;
        }

        if (step == null || step.parentStep == null) {
            throw new IllegalStateException(
                    "No parent ScenarioStep is available for the running service-call step"
            );
        }

        return scenarioStep(step.parentStep);
    }

    private static StepExtension createFinalizer(
            StepExtension triggerStep,
            ScenarioStep scenarioStep,
            Map<String, String> passedValues,
            String inlineServiceCallObjectName
    ) {
        String scenarioName = scenarioName(scenarioStep.getDefaultStepNodeMap());
        String tableCallKey = resolve(scenarioStep, passedValues.get(CALL_KEY));
        String inlineCallKey = resolve(scenarioStep, inlineServiceCallObjectName);
        String resolvedCallKey = firstNonBlank(
                tableCallKey,
                inlineCallKey,
                scenarioName
        );

        if (resolvedCallKey.isBlank()) {
            throw new IllegalStateException(
                    "No Call Key, inline service-call object name, or scenario name is available"
            );
        }

        StepExtension finalizer = triggerStep.modifyStepExtension(
                FINALIZER_STEP_PREFIX + encodeKey(resolvedCallKey)
        );
        finalizer.childSteps.clear();
        finalizer.attachedSteps.clear();
        finalizer.previousSibling = null;
        finalizer.nextSibling = null;
        finalizer.setNestingLevel(scenarioStep.getNestingLevel());
        finalizer.addStepFlags(ALWAYS_RUN);
        return finalizer;
    }

    private static ObjectNode requiredObject(NodeMap parent, String fieldName) {
        Object value = parent.get(fieldName);
        if (value == null) {
            throw new IllegalStateException(
                    "The service-call object is missing the " + fieldName + " object"
            );
        }
        JsonNode node = asJsonNode(value);
        if (node instanceof ObjectNode objectNode) {
            return objectNode;
        }
        if (node == null || node.isNull()) {
            throw new IllegalStateException(
                    "The service-call object is missing the " + fieldName + " object"
            );
        }
        throw new IllegalStateException(
                "The service-call " + fieldName
                        + " property must be an object but was "
                        + node.getNodeType()
                        + ": "
                        + value
                        + ". Complete service-call root: "
                        + parent.getRoot()
        );
    }

    private static ObjectNode optionalObject(NodeMap parent, String fieldName) {
        Object value = parent.get(fieldName);
        if (value == null) {
            return MAPPER.createObjectNode();
        }
        JsonNode node = asJsonNode(value);
        if (node == null || node.isNull()) {
            return MAPPER.createObjectNode();
        }
        if (node instanceof ObjectNode objectNode) {
            return objectNode;
        }
        throw new IllegalStateException(
                "The service-call " + fieldName + " property must be an object"
        );
    }

    /** Coerces a NodeMap {@code get} result into a JsonNode for object checks. */
    private static JsonNode asJsonNode(Object value) {
        if (value instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        return MAPPER.valueToTree(value);
    }

    private static String resolve(ScenarioStep scenarioStep, String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return "";
        }
        return normalize(
                scenarioStep.getStepParsingMap().resolveWholeText(normalized)
        );
    }

    private static String scenarioName(NodeMap scenarioMap) {
        JsonNode direct = scenarioMap.getRoot().get(SCENARIO_NAME);
        if (direct != null && !direct.isNull()) {
            if (direct.isArray()) {
                return direct.isEmpty()
                        ? ""
                        : normalize(direct.get(direct.size() - 1).asText(""));
            }
            return normalize(direct.asText(""));
        }

        Object value = scenarioMap.get(SCENARIO_NAME);
        if (value instanceof JsonNode node) {
            return normalize(node.asText(""));
        }
        return value == null ? "" : normalize(String.valueOf(value));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private static String callsPath() {
        Object configuredPath = resolveFromVars("pkb_callspath");
        return configuredPath == null || configuredPath.toString().isBlank()
                ? DEFAULT_CALLS_PATH
                : configuredPath.toString().trim();
    }

    private static String encodeKey(String key) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeKey(String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new IllegalArgumentException("Encoded service-call key cannot be blank");
        }
        try {
            return new String(
                    Base64.getUrlDecoder().decode(encodedKey),
                    StandardCharsets.UTF_8
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Invalid encoded service-call key: " + encodedKey,
                    exception
            );
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static final class LogInfoOutputStream extends OutputStream {
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        @Override
        public void write(int value) {
            if (value == '\n') {
                logLine();
            } else if (value != '\r') {
                line.write(value);
            }
        }

        @Override
        public void flush() {
            logLine();
        }

        @Override
        public void close() throws IOException {
            logLine();
            super.close();
        }

        private void logLine() {
            if (line.size() > 0) {
                logInfo(line.toString(StandardCharsets.UTF_8));
                line.reset();
            }
        }
    }
}
