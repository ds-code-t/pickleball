package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureRecordTest {
    @Test
    void wrapperCauseBecomesTheMessageAndTheStackHead() {
        Throwable error = new RuntimeException("Step 'Then stay' failed",
                new AssertionError("expected <true> but was <false>"));
        FailureRecord record = FailureRecord.fromThrowable(error, "Then stay");
        assertEquals("Then stay", record.stepText());
        assertEquals("expected <true> but was <false>", record.message());
        assertEquals("java.lang.AssertionError: expected <true> but was <false>", record.stackHead());
        assertTrue(record.causeChain().getFirst().contains("Step 'Then stay' failed"), record.causeChain().toString());
        assertFalse(record.toMap().containsValue(null));
    }

    @Test
    void undefinedStepMessageIsKept() {
        Throwable error = new IllegalStateException("Undefined step: Then missing");
        FailureRecord record = FailureRecord.fromThrowable(error, "Then missing");
        assertEquals("Undefined step: Then missing", record.message());
        assertTrue(record.stackHead().startsWith("java.lang.IllegalStateException: Undefined step: Then missing"));
    }

    @Test
    void nestedWorkerErrorAndDiagnosticFailureUseTheSameRecord() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("error", Map.of(
                "type", "io.cucumber.junit.UndefinedStepException",
                "message", "Undefined step: Then missing",
                "stackTrace", "io.cucumber.junit.UndefinedStepException: Undefined step: Then missing\n\tat cucumber.runtime.StepDefinitionMatch"
        ));
        nested.put("runtime", Map.of("phraseText", "missing", "stepText", "Then missing"));
        FailureRecord worker = FailureRecord.fromWorker(nested);
        assertEquals("missing", worker.phrase());
        assertEquals("Undefined step: Then missing", worker.message());
        assertEquals("io.cucumber.junit.UndefinedStepException: Undefined step: Then missing", worker.stackHead());

        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("type", "failure");
        diagnostic.put("failures", List.of(Map.of(
                "class", "io.cucumber.core.exception.CucumberException",
                "message", "Step 'Then stay' failed",
                "stackTrace", List.of("at tools.dscode.StepExtension.resolveAndClone(StepExtension.java:1)"),
                "causes", List.of(Map.of(
                        "class", "java.lang.AssertionError",
                        "message", "expected true"
                ))
        )));
        FailureRecord fromEvent = FailureRecord.fromWorker(diagnostic);
        assertEquals("expected true", fromEvent.message());
        assertEquals("java.lang.AssertionError: expected true", fromEvent.stackHead());
        assertNull(FailureRecord.fromWorker(Map.of("type", "failure")));
        Map<String, Object> colon = new LinkedHashMap<>();
        colon.put("message", "Step: 'Then missing' failed");
        colon.put("errorMessage", "Failed to find PickleStepDefinitionMatch for step text: 'Then missing'");
        FailureRecord colonRecord = FailureRecord.fromWorker(colon);
        assertEquals("Failed to find PickleStepDefinitionMatch for step text: 'Then missing'", colonRecord.message());
        assertFalse(colonRecord.message().startsWith("Step:"));
    }

    @Test
    void stackHeadIsTheFirstFrameOutsideTheFrameworkAndCauseIsTheTopCausedBy() {
        AssertionError cause = new AssertionError("expected <true> but was <false>");
        cause.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("tools.dscode.StepExtension", "resolve", "StepExtension.java", 3),
                new StackTraceElement("com.example.ShopSteps", "buy", "ShopSteps.java", 42)
        });
        RuntimeException wrapper = new RuntimeException("Step 'Then buy' failed", cause);
        wrapper.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("io.cucumber.core.runner.PickleStepTestStep", "run", "PickleStepTestStep.java", 80)
        });
        FailureRecord record = FailureRecord.fromThrowable(wrapper, "Then buy");
        assertEquals("expected <true> but was <false>", record.message());
        assertEquals("at com.example.ShopSteps.buy(ShopSteps.java:42)", record.stackHead());
        assertEquals("Caused by: java.lang.AssertionError: expected <true> but was <false>", record.cause());
        assertFalse(record.toMap().containsKey("causeChain"));
        assertEquals(record.cause(), record.toMap().get("cause"));
        FailureRecord.verbose(true);
        try {
            assertTrue(record.toMap().containsKey("causeChain"));
        } finally {
            FailureRecord.verbose(false);
        }

        String stack = """
                java.lang.RuntimeException: Step 'Then buy' failed
                \tat io.cucumber.core.runner.PickleStepTestStep.run(PickleStepTestStep.java:80)
                Caused by: java.lang.AssertionError: expected <true> but was <false>
                \tat com.example.ShopSteps.buy(ShopSteps.java:42)
                \tat tools.dscode.StepExtension.resolve(StepExtension.java:3)
                """;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "expected <true> but was <false>");
        body.put("stackTrace", stack);
        body.put("cause", stack);
        FailureRecord worker = FailureRecord.fromWorker(body);
        assertEquals("at com.example.ShopSteps.buy(ShopSteps.java:42)", worker.stackHead());
        assertEquals("Caused by: java.lang.AssertionError: expected <true> but was <false>", worker.cause());
        assertFalse(worker.cause().contains("PickleStepTestStep"));
        assertFalse(worker.cause().contains("\n"));
    }
}
