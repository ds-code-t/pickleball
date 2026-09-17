package tools.dscode.coredefinitions;

import io.cucumber.core.runner.StepExtension;
import io.cucumber.java.en.Given;
import tools.dscode.common.assertions.AssertionClauseSplitter;
import tools.dscode.common.control.ControlExecutionScope;
import tools.dscode.common.exceptions.SoftRuntimeException;
import tools.dscode.common.treeparsing.preparsing.ParsedLine;

import java.util.ArrayList;
import java.util.List;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.reporting.logging.LogForwarder.logDebug;
import static tools.dscode.common.reporting.logging.LogForwarder.logError;
import static tools.dscode.common.reporting.logging.LogForwarder.logFail;
import static tools.dscode.common.reporting.logging.LogForwarder.logInfo;
import static tools.dscode.common.reporting.logging.LogForwarder.logTrace;
import static tools.dscode.common.reporting.logging.LogForwarder.logWarn;

public class LogAndAssertSteps {

    @Given("^(TRACE|DEBUG|INFO|WARN|ERROR|FAIL):\\s*(.*)$")
    public void authorLogOrFail(String token, String text) {
        String message = text == null ? "" : text;
        switch (token) {
            case "TRACE" -> logTrace(message);
            case "DEBUG" -> logDebug(message);
            case "INFO" -> logInfo(message);
            case "WARN" -> logWarn(message);
            case "ERROR" -> {
                logError(message);
                throw new RuntimeException(message);
            }
            case "FAIL" -> {
                logFail(message);
                throw new RuntimeException(message);
            }
            default -> throw new IllegalArgumentException("Unsupported log token: " + token);
        }
    }

    @Given("^ASSERT:\\s*(.*)$")
    public void hardAssert(String payload) {
        evaluateClauses(payload, true);
    }

    @Given("^SOFT ASSERT:\\s*(.*)$")
    public void softAssert(String payload) {
        evaluateClauses(payload, false);
    }

    private static void evaluateClauses(String payload, boolean hard) {
        List<String> clauses = AssertionClauseSplitter.split(payload);
        if (clauses.isEmpty()) {
            return;
        }

        String verb = hard ? ", ensure " : ", verify ";
        String label = hard ? "ASSERT" : "SOFT ASSERT";
        StepExtension parent = getRunningStep();
        List<String> failedClauses = new ArrayList<>();
        DynamicSteps dynamicSteps = new DynamicSteps();

        for (String clause : clauses) {
            try {
                runSyntheticDynamicStep(parent, dynamicSteps, verb + clause);
            } catch (SoftRuntimeException exception) {
                logError(label + " failed: " + clause);
                failedClauses.add(clause);
                if (hard) {
                    throw new RuntimeException(label + " failed: " + clause, exception);
                }
            } catch (RuntimeException exception) {
                logError(label + " failed: " + clause);
                if (hard) {
                    throw new RuntimeException(label + " failed: " + clause, exception);
                }
                failedClauses.add(clause);
            }
        }

        if (!hard && !failedClauses.isEmpty()) {
            throw new SoftRuntimeException(
                    failedClauses.stream()
                            .map(clause -> label + " failed: " + clause)
                            .reduce((left, right) -> left + "; " + right)
                            .orElse(label + " failed")
            );
        }
    }

    private static void runSyntheticDynamicStep(
            StepExtension parent,
            DynamicSteps dynamicSteps,
            String stepText
    ) {
        StepExtension child = parent.modifyStepExtension(stepText);
        child.parentStep = parent;
        child.nextSibling = null;
        child.previousSibling = null;
        ControlExecutionScope.withStep(child, () -> {
            child.lineData = ParsedLine.createParsedLine(child);
            child.lineData.setInheritance(child);
            dynamicSteps.executeDynamicStep(stepText);
            return null;
        });
    }
}
