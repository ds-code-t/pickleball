package tools.dscode.common.reporting.diagnostic;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.datatable.DataTable;
import io.cucumber.plugin.event.Result;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.RemoteWebDriver;
import tools.dscode.common.reporting.logging.BaseConverter;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.reporting.logging.Level;
import tools.dscode.common.reporting.logging.Status;
import tools.dscode.coredefinitions.BrowserSteps;

import java.util.concurrent.CompletableFuture;

import static tools.dscode.common.reporting.logging.LogForwarder.logInfo;

public privileged aspect DiagnosticRuntimeAspect {
    before(CurrentScenarioState state):
            execution(void io.cucumber.core.runner.CurrentScenarioState.startScenarioRun()) && this(state) {
        if (DiagnosticRuntime.isDiagnostic()) DiagnosticRuntime.startScenario(state);
    }

    after(CurrentScenarioState state) returning:
            call(tools.dscode.common.reporting.logging.Entry tools.dscode.common.reporting.logging.Entry.start(String[]))
            && withincode(void io.cucumber.core.runner.CurrentScenarioState.startScenarioRun())
            && this(state) {
        logInfo(ScenarioIdentity.from(state).infoText());
    }

    after(CurrentScenarioState state) returning:
            execution(void io.cucumber.core.runner.CurrentScenarioState.startScenarioRun()) && this(state) {
        ReportRetentionPolicy.recordScenario(state.isScenarioFailed(), false);
        DiagnosticRuntime.endScenario(state, false, null);
    }

    after(CurrentScenarioState state) throwing(Throwable error):
            execution(void io.cucumber.core.runner.CurrentScenarioState.startScenarioRun()) && this(state) {
        ReportRetentionPolicy.recordScenario(state.isScenarioFailed(), true);
        DiagnosticRuntime.endScenario(state, true, error);
    }

    before(CurrentScenarioState state):
            execution(void io.cucumber.core.runner.CurrentScenarioState.scenarioRunCleanUp()) && this(state) {
        if (DiagnosticRuntime.isDiagnostic() && state.isScenarioFailed()) {
            try {
                DiagnosticRuntime.captureScreenshot(BrowserSteps.getCurrentDriver(), "Scenario Failure");
            } catch (Throwable ignored) {
            }
        }
    }

    before(CurrentScenarioState state, StepExtension step):
            execution(void io.cucumber.core.runner.CurrentScenarioState.runStep(io.cucumber.core.runner.StepExtension))
            && this(state) && args(step) {
        if (step instanceof ScenarioStep scenarioStep && scenarioStep.parentStep != null) {
            ScenarioIdentity caller = ScenarioIdentity.from(state);
            ScenarioIdentity callee = ScenarioIdentity.from(scenarioStep);
            logInfo("Nested scenario source: callerUri='" + caller.featureUri()
                    + "', callerScenario='" + caller.scenarioName()
                    + "', callerLine=" + caller.scenarioLine()
                    + ", calleeUri='" + callee.featureUri()
                    + "', calleeScenario='" + callee.scenarioName()
                    + "', calleeLine=" + callee.scenarioLine()
                    + (callee.exampleLine() == null ? "" : ", calleeExampleLine=" + callee.exampleLine())
                    + ", calleeExactSourceKey=" + callee.exactSourceKey());
            DiagnosticRuntime.beginNested(scenarioStep);
        }
    }

    after(CurrentScenarioState state, StepExtension step):
            execution(void io.cucumber.core.runner.CurrentScenarioState.runStep(io.cucumber.core.runner.StepExtension))
            && this(state) && args(step) {
        if (step instanceof ScenarioStep scenarioStep && scenarioStep.parentStep != null) {
            DiagnosticRuntime.endNested(state.isScenarioFailed());
        }
    }

    before(StepExtension step):
            execution(io.cucumber.plugin.event.Result io.cucumber.core.runner.StepExtension+.run())
            && this(step) {
        DiagnosticRuntime.beginStep(step);
    }

    after(StepExtension step):
            set(* io.cucumber.core.runner.StepBase.executingPickleStepTestStep)
            && target(step) {
        DiagnosticRuntime.bindStepDefinition(step);
    }

    after(StepExtension step) returning(Result result):
            execution(io.cucumber.plugin.event.Result io.cucumber.core.runner.StepExtension+.run())
            && this(step) {
        DiagnosticRuntime.endStep(step, result, null);
    }

    after(StepExtension step) throwing(Throwable error):
            execution(io.cucumber.plugin.event.Result io.cucumber.core.runner.StepExtension+.run())
            && this(step) {
        DiagnosticRuntime.endStep(step, null, error);
    }

    after() returning(RemoteWebDriver driver):
            execution(public static org.openqa.selenium.remote.RemoteWebDriver tools.dscode.common.driver.DriverConstruction.create*(..)) {
        if (driver != null) DiagnosticRuntime.observeCapability("browser.webdriver.initialized");
    }

    after() returning(RemoteWebDriver driver):
            execution(public static org.openqa.selenium.remote.RemoteWebDriver tools.dscode.coredefinitions.BrowserSteps.getCurrentDriver()) {
        if (driver != null) DiagnosticRuntime.observeCapability("browser.webdriver.used");
    }

    before(): (execution(public static void tools.dscode.coredefinitions.BrowserSteps.navigateWithBlocker(..))
            || execution(public * tools.dscode.coredefinitions.BrowserSteps.navigate(..))) {
        DiagnosticRuntime.observeCapability("browser.navigation");
    }

    before(): (execution(* tools.dscode.common.seleniumextensions.ElementWrapper.*(..))
            || execution(* tools.dscode.common.domoperations.HumanInteractions.*(..))) {
        DiagnosticRuntime.observeCapability("browser.dom");
    }

    before(): execution(public static void tools.dscode.coredefinitions.ServiceCallSteps.executeServiceCall()) {
        DiagnosticRuntime.observeCapability("service.http");
    }

    before(): (execution(public static Object tools.dscode.coredefinitions.ServiceCallSteps.inlineCall(..))
            || execution(public static void tools.dscode.coredefinitions.ServiceCallSteps.serviceCalls(..))) {
        DiagnosticRuntime.observeCapability("service.scenario");
    }

    before(): execution(public static Object tools.dscode.coredefinitions.ModularScenarios.inlineComponent(..)) {
        DiagnosticRuntime.observeCapability("scenario.component");
    }

    before(String inlineRunKey, String runTypeText, String pluralFlag, String inlineArgs, DataTable dataTable):
            execution(public static void tools.dscode.coredefinitions.ModularScenarios.runScenarios(String, String, String, String, io.cucumber.datatable.DataTable))
            && args(inlineRunKey, runTypeText, pluralFlag, inlineArgs, dataTable) {
        DiagnosticRuntime.observeRunType(runTypeText, dataTable);
    }

    after(Entry entry) returning:
            execution(tools.dscode.common.reporting.logging.Entry tools.dscode.common.reporting.logging.Entry.timestamp(java.time.Instant))
            && this(entry) {
        DiagnosticRuntime.recordEntry(entry, "instant");
    }

    after(Entry entry) returning:
            execution(tools.dscode.common.reporting.logging.Entry tools.dscode.common.reporting.logging.Entry.start(java.time.Instant, String[]))
            && this(entry) {
        DiagnosticRuntime.recordEntry(entry, "start");
    }

    after(Entry entry) returning:
            execution(tools.dscode.common.reporting.logging.Entry tools.dscode.common.reporting.logging.Entry.stop())
            && this(entry) {
        DiagnosticRuntime.recordEntry(entry, "stop");
    }

    after(Entry entry, Status status) returning:
            execution(tools.dscode.common.reporting.logging.Entry tools.dscode.common.reporting.logging.Entry.stop(tools.dscode.common.reporting.logging.Status))
            && this(entry) && args(status) {
        DiagnosticRuntime.recordEntry(entry, "stop");
    }

    after(Entry parent) returning(Entry child):
            execution(private tools.dscode.common.reporting.logging.Entry tools.dscode.common.reporting.logging.Entry.createChild(String))
            && this(parent) {
        if (DiagnosticRuntime.isDiagnostic() && child != null) parent.children.remove(child);
    }

    Entry around(WebDriver driver, String name):
            execution(public tools.dscode.common.reporting.logging.Entry tools.dscode.common.reporting.logging.Entry.screenshot(org.openqa.selenium.WebDriver, String))
            && args(driver, name) {
        Entry entry = (Entry) thisJoinPoint.getThis();
        if (!DiagnosticRuntime.isDiagnostic()) return proceed(driver, name);
        DiagnosticRuntime.captureScreenshot(driver, name);
        return entry;
    }

    Entry around(String name, String base64):
            execution(public tools.dscode.common.reporting.logging.Entry tools.dscode.common.reporting.logging.Entry.attachScreenshot(String, String))
            && args(name, base64) {
        Entry entry = (Entry) thisJoinPoint.getThis();
        if (!DiagnosticRuntime.isDiagnostic()) return proceed(name, base64);
        try {
            DiagnosticRuntime.captureScreenshotBytes(java.util.Base64.getDecoder().decode(base64), name);
        } catch (Throwable error) {
            DiagnosticRuntime.recordFilteredLog(Level.ERROR,
                    "Failed to decode explicit screenshot '" + name + "': " + error.getMessage());
        }
        return entry;
    }

    before(String message): execution(public static tools.dscode.common.reporting.logging.Entry tools.dscode.common.reporting.logging.LogForwarder.logTrace(String)) && args(message) {
        captureFiltered(Level.TRACE, message);
    }
    before(String message): execution(public static tools.dscode.common.reporting.logging.Entry tools.dscode.common.reporting.logging.LogForwarder.logDebug(String)) && args(message) {
        captureFiltered(Level.DEBUG, message);
    }
    before(String message): execution(public static tools.dscode.common.reporting.logging.Entry tools.dscode.common.reporting.logging.LogForwarder.logInfo(String)) && args(message) {
        captureFiltered(Level.INFO, message);
    }
    before(String message): execution(public static tools.dscode.common.reporting.logging.Entry tools.dscode.common.reporting.logging.LogForwarder.logWarn(String)) && args(message) {
        captureFiltered(Level.WARN, message);
    }
    before(String message): execution(public static tools.dscode.common.reporting.logging.Entry tools.dscode.common.reporting.logging.LogForwarder.logError(String)) && args(message) {
        captureFiltered(Level.ERROR, message);
    }
    before(String message): execution(public static tools.dscode.common.reporting.logging.Entry tools.dscode.common.reporting.logging.LogForwarder.logSkip(String)) && args(message) {
        captureFiltered(Level.WARN, message);
    }
    before(String message): execution(public static tools.dscode.common.reporting.logging.Entry tools.dscode.common.reporting.logging.LogForwarder.logFail(String)) && args(message) {
        captureFiltered(Level.ERROR, message);
    }

    private void captureFiltered(Level level, String message) {
        if (DiagnosticRuntime.isDiagnostic()
                && !tools.dscode.common.reporting.logging.LogForwarder.shouldLog(level)) {
            DiagnosticRuntime.recordFilteredLog(level, message);
        }
    }

    Entry around(Entry entry, java.util.List converters):
            execution(public tools.dscode.common.reporting.logging.Entry tools.dscode.common.reporting.logging.Entry.on(java.util.List))
            && this(entry) && args(converters) {
        return DiagnosticRuntime.isDiagnostic() ? entry : proceed(entry, converters);
    }

    Entry around(Entry entry, BaseConverter[] converters):
            execution(public tools.dscode.common.reporting.logging.Entry tools.dscode.common.reporting.logging.Entry.on(tools.dscode.common.reporting.logging.BaseConverter[]))
            && this(entry) && args(converters) {
        return DiagnosticRuntime.isDiagnostic() ? entry : proceed(entry, converters);
    }

    void around(): execution(void tools.dscode.common.reporting.logging.BaseConverter+.onStart(..)) {
        if (!DiagnosticRuntime.isDiagnostic()) proceed();
    }

    void around(): execution(void tools.dscode.common.reporting.logging.BaseConverter+.onTimestamp(..)) {
        if (!DiagnosticRuntime.isDiagnostic()) proceed();
    }

    void around(): execution(void tools.dscode.common.reporting.logging.BaseConverter+.onStop(..)) {
        if (!DiagnosticRuntime.isDiagnostic()) proceed();
    }

    Entry around(): execution(public tools.dscode.common.reporting.logging.Entry tools.dscode.common.reporting.logging.Entry.close()) {
        Entry entry = (Entry) thisJoinPoint.getThis();
        return DiagnosticRuntime.isDiagnostic() ? entry : proceed();
    }

    CompletableFuture around():
            execution(public java.util.concurrent.CompletableFuture tools.dscode.common.reporting.logging.Entry.cleanupScenarioConverters()) {
        return DiagnosticRuntime.isDiagnostic() ? CompletableFuture.completedFuture(null) : proceed();
    }
}
