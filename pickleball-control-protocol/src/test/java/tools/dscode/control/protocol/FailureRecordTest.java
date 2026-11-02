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
    }
}
