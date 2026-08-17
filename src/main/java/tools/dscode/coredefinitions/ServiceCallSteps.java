package tools.dscode.coredefinitions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.core.runner.StepBase;
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
import java.util.Locale;
import java.util.Map;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.mappings.MappingProcessor.getRunMap;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.reporting.logging.LogForwarder.logInfo;
/**
 * Cucumber-facing service-call definitions.
 *
 * <p>{@link ModularScenarios} owns reusable-scenario selection and execution.
 * This class retains the service-call convenience wrapper and executes the
 * REQUEST assembled in the selected ScenarioStep.</p>
 */
public class ServiceCallSteps extends CoreSteps {
    static final String SCENARIO_NAME = "SCENARIO NAME";
    static final String REQUEST = "REQUEST";
    static final String CONFIGURATION = "CONFIGURATION";
    static final String RESPONSE = "RESPONSE";
    static final String PARENT = "PARENT";
    /**
     * @deprecated Use {@code RUN ["key"] SERVICE CALL(S):...}.
     */
    @Deprecated(forRemoval = true)
    @Given("^(?:\"([^\"]+)\"\\s+)?SERVICE CALL(S)?:?(.*)?$")
    public static void serviceCalls(
            String inlineServiceCallObjectName,
            String pluralFlag,
            String inlineArgs,
            DataTable dataTable
    ) {
        ModularScenarios.populateRunScenariosStep(
                getRunningStep(),
                pluralFlag,
                inlineArgs,
                dataTable,
                ModularScenarios.RunType.SERVICE_CALL,
                "service-call scenario",
                "SERVICE CALL",
                (scenarioStep, passedValues) -> registerServiceCallReference(
                        scenarioStep,
                        passedValues,
                        inlineServiceCallObjectName
                )
        );
    }

    @Given("^CALL:(.*)$")
    public static Object inlineCall(
            String inlineArgs,
            DataTable dataTable
    ) {
        return ModularScenarios.runSingleScenario(
                inlineArgs,
                dataTable,
                ModularScenarios.RunType.SERVICE_CALL
        );
    }
    @Given("^EXECUTE SERVICE CALL$")
    public static void executeServiceCall() {
        ScenarioStep scenarioStep = scenarioStep(getRunningStep());
        NodeMap serviceCallMap = scenarioStep.getDefaultStepNodeMap();
        ObjectNode serviceCallObject = serviceCallMap.getRoot();
        // RUN/CALL result storage happens after the selected scenario completes.
        // Initialize RESPONSE on the selected scenario root before validation.
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
    /**
     * Resolves the service-call key and registers the ScenarioStep's root
     * ObjectNode by reference for the deprecated SERVICE CALL compatibility step.
     */
    private static void registerServiceCallReference(
            ScenarioStep scenarioStep,
            Map<String, String> passedValues,
            String inlineServiceCallObjectName
    ) {
        String scenarioName = scenarioName(scenarioStep.getDefaultStepNodeMap());
        String tableRunKey = resolve(
                scenarioStep,
                passedValues.get(ModularScenarios.RUN_KEY)
        );
        String inlineCallKey = resolve(scenarioStep, inlineServiceCallObjectName);
        String resolvedCallKey = firstNonBlank(
                tableRunKey,
                inlineCallKey,
                scenarioName
        );
        if (resolvedCallKey.isBlank()) {
            throw new IllegalStateException(
                    "No RunKey, inline service-call object name, or scenario name is available"
            );
        }

        ObjectNode serviceCallObject = scenarioStep
                .getDefaultStepNodeMap()
                .getRoot();

        getRunMap().putReference(resolvedCallKey, serviceCallObject);
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
                "The service-call " + fieldName
                        + " property must be an object"
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
