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

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.reporting.logging.LogForwarder.logInfo;

public class ServiceCallSteps extends CoreSteps {

    @Given("^ENDPOINT:(.*)$")
    public static void endpoint(String endpoint) {
        requestNode().put("endpoint", endpoint.trim());
    }

    @Given("^METHOD:(.*)$")
    public static void method(String method) {
        requestNode().put("method", method.trim().toUpperCase(Locale.ROOT));
    }

    @Given("^HEADERS$")
    public static void headers(DataTable dataTable) {
        requestNode().set(
            "headers",
            MAPPER.valueToTree(
                dataTable.asMaps(String.class, String.class).getFirst()
            )
        );
    }

    @Given("^BODY(?::(.*))?$")
    public static void body(String type, String body) {
        ObjectNode request = requestNode();
        request.put("body", body.replace("~[~", "<").replace("~]~", ">"));

        if (type != null && !type.isBlank()) {
            ServiceCallContext.objectChild(request, "config")
                .put("contentType", contentType(type));
        }
    }

    @Given("^REQUEST CONFIG(?:URATION)?$")
    public static void requestConfiguration(DataTable dataTable) {
        ObjectNode config = ServiceCallContext.objectChild(
            requestNode(),
            "config"
        );

        dataTable.asMap(String.class, String.class).forEach((key, value) -> {
            JsonNode typedValue = MAPPER.valueToTree(typed(value));
            config.set(key, typedValue);
        });
    }

    @Given("^(?:EXECUTE|SEND) SERVICE CALL$")
    public static void executeServiceCall() {
        ScenarioStep scenario = scenarioStep(getRunningStep());
        NodeMap nodeMap = scenario.getDefaultStepNodeMap();
        String callName = ServiceCallContext.callName(nodeMap);
        ObjectNode request = ServiceCallContext.request(nodeMap);

        String method = request.path("method").asText("GET");
        String endpoint = request.path("endpoint").asText("").trim();
        long started = System.nanoTime();
        ObjectNode extractedResponse;

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

            extractedResponse = (ObjectNode) RestAssuredUtil.extractResponse(response);
            logInfo(
                "REST Assured service call completed in "
                    + elapsedMillis(started)
                    + " ms"
            );
        } catch (Exception exception) {
            logInfo(
                "REST Assured service call failed after "
                    + elapsedMillis(started)
                    + " ms: "
                    + exception
            );

            extractedResponse = MAPPER.createObjectNode();
            extractedResponse.put("error", exception.getMessage());
            extractedResponse.set("headers", MAPPER.createObjectNode());
            extractedResponse.put("body", "");
        }

        extractedResponse.put("method", method);

        // Mutate the existing shared response node instead of replacing it.
        // Both the scenario-step named call and the run-map call history
        // reference this same ObjectNode.
        ServiceCallContext.replaceContents(
            ServiceCallContext.response(nodeMap),
            extractedResponse
        );
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

    private static ObjectNode requestNode() {
        ScenarioStep scenario = scenarioStep(getRunningStep());
        return ServiceCallContext.request(scenario.getDefaultStepNodeMap());
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
