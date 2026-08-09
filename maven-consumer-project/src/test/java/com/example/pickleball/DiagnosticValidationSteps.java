package com.example.pickleball;

import io.cucumber.java.en.Given;
import tools.dscode.common.reporting.logging.LogForwarder;

import static tools.dscode.common.reporting.logging.LogForwarder.logDebug;
import static tools.dscode.common.reporting.logging.LogForwarder.logInfo;
import static tools.dscode.common.reporting.logging.LogForwarder.logTrace;
import static tools.dscode.common.reporting.logging.LogForwarder.logWarn;

public final class DiagnosticValidationSteps {

    @Given("emit diagnostic log markers {string}")
    public static void emitDiagnosticLogMarkers(String marker) {
        logTrace(marker + " TRACE duplicate");
        logTrace(marker + " TRACE duplicate");
        logDebug(marker + " DEBUG");
        logInfo(marker + " INFO");
        logWarn(marker + " WARN");
    }

    @Given("capture diagnostic screenshot {string}")
    public static void captureDiagnosticScreenshot(String name) {
        LogForwarder.closestEntryToStep().screenshot(name);
    }
}
