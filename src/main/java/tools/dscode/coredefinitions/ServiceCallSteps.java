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
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.mappings.MappingProcessor.getRunMap;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.reporting.logging.LogForwarder.logInfo;
import static tools.dscode.coredefinitions.ServiceCallScenarios.CALL_NAME;

public class ServiceCallSteps extends CoreSteps {

    @Given("^ENDPOINT:(.*)$")
    public static void endpoint(String endpoint) {
        put("request.endpoint", endpoint.trim());
    }

    @Given("^METHOD:(.*)$")
    public static void method(String method) {
        put("request.method", method.trim().toUpperCase(Locale.ROOT));
    }

    @Given("^HEADERS$")
    public static void headers(DataTable dataTable) {
        put(
                "request.headers",
                dataTable.asMaps(String.class, String.class).getFirst()
        );
    }

    @Given("^BODY(?::(.*))?$")
    public static void body(String type, String body) {
        put("request.body", body.replace("~[~", "<").replace("~]~", ">"));
        if (type != null && !type.isBlank()) {
            put("request.config.contentType", contentType(type));
        }
    }

    @Given("^REQUEST CONFIG(?:URATION)?$")
    public static void requestConfiguration(DataTable dataTable) {
        dataTable.asMap(String.class, String.class)
                .forEach((key, value) -> put("request.config." + key, typed(value)));
    }

    @Given("^(?:EXECUTE|SEND) SERVICE CALL$")
    public static void executeServiceCall() {
        ScenarioStep scenario = scenarioStep(getRunningStep());
        NodeMap nodeMap = scenario.getDefaultStepNodeMap();
        String callName = callName(scenario);
        JsonNode request = requestNode(nodeMap, callName);
        String method = request.path("method").asText("GET");
        String endpoint = request.path("endpoint").asText("").trim();
        long started = System.nanoTime();

        ObjectNode responseNode;
        try (PrintStream restLog = new PrintStream(
                new LogInfoOutputStream(),
                true,
                StandardCharsets.UTF_8
        )) {
            if (endpoint.isBlank()) {
                throw new IllegalArgumentException(
                        "No endpoint was defined for service call " + callName
                );
            }

            logInfo("REST Assured service call: " + callName);
            logInfo("Service call request: " + request);

            RequestSpecification specification = RestAssuredUtil.buildRequest(request);
            RestAssuredUtil.logRequestAndResponse(specification, restLog);

            Response response = RestAssuredUtil.execute(
                    specification,
                    method,
                    endpoint
            );
            responseNode = (ObjectNode) RestAssuredUtil.extractResponse(response);

            logInfo("REST Assured service call completed in " + elapsedMillis(started) + " ms");
        } catch (Exception exception) {
            logInfo("REST Assured service call failed after "
                    + elapsedMillis(started)
                    + " ms: "
                    + exception);

            responseNode = MAPPER.createObjectNode();
            responseNode.put("error", exception.getMessage());
            responseNode.set("headers", MAPPER.createObjectNode());
            responseNode.put("body", "");
        }

        responseNode.put("method", method);
        nodeMap.put(callName + ".response", responseNode);
    }

    @Given("^MAP SERVICE RESPONSE$")
    public static void mapServiceResponse(DataTable dataTable) {
        NodeMap source = scenarioStep(getRunningStep()).getDefaultStepNodeMap();
        for (List<String> row : dataTable.cells()) {
            getRunMap().put(row.get(1), source.get(row.get(0)));
        }
    }

    public static ScenarioStep scenarioStep(StepBase step) {
        if (step instanceof ScenarioStep scenarioStep) {
            return scenarioStep;
        }
        return scenarioStep(step.parentStep);
    }

    private static JsonNode requestNode(NodeMap nodeMap, String callName) {
        Object value = nodeMap.get(callName + ".request");
        return value instanceof JsonNode node ? node : MAPPER.valueToTree(value);
    }

    private static void put(String path, Object value) {
        ScenarioStep scenario = scenarioStep(getRunningStep());
        scenario.getDefaultStepNodeMap().put(callName(scenario) + "." + path, value);
    }

    private static String callName(ScenarioStep scenario) {
        Object value = scenario.getDefaultStepNodeMap().get(CALL_NAME);
        if (value == null) {
            value = scenario.getDefaultStepNodeMap().get("SCENARIO NAME");
        }
        return value instanceof JsonNode node ? node.asText() : String.valueOf(value);
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static Object typed(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return MAPPER.readTree(value);
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String contentType(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "json" -> "application/json";
            case "xml", "soap" -> "text/xml";
            case "yaml" -> "application/yaml";
            case "html" -> "text/html";
            case "text" -> "text/plain";
            default -> value.trim();
        };
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
