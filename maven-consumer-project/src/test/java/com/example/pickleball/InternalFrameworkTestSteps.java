package com.example.pickleball;

import com.example.pickleball.support.InternalJavaTestRunner;
import io.cucumber.core.runner.ScenarioStepChecks;
import io.cucumber.core.runner.ScenarioStepDataChecks;
import io.cucumber.java.en.Given;
import tools.dscode.common.dataelements.DataElementPhaseFiveChecks;
import tools.dscode.common.dataelements.DataElementPhaseFourAndSixChecks;
import tools.dscode.common.dataelements.DataElementPhaseOneChecks;
import tools.dscode.common.dataelements.DataElementPhaseThreeChecks;
import tools.dscode.common.dataelements.DataElementPhaseTwoChecks;
import tools.dscode.common.driver.ChromeHeadlessConfigChecks;
import tools.dscode.common.mappings.MappingDataRefactorChecks;
import tools.dscode.common.mappings.QuoteParserChecks;
import tools.dscode.common.reporting.diagnostic.Diagnostic213CompletionChecks;
import tools.dscode.common.reporting.diagnostic.DiagnosticReportingChecks;
import tools.dscode.common.reporting.diagnostic.InvestigationHandoffChecks;
import tools.dscode.common.reporting.diagnostic.PickleballGuidanceChecks;
import tools.dscode.common.reporting.diagnostic.ReportRetentionPolicy;
import tools.dscode.common.util.datetime.BusinessTemporalDeltaChecks;
import tools.dscode.common.util.datetime.BusinessTimePostModifierChecks;
import tools.dscode.coredefinitions.ModularScenariosChecks;
import tools.dscode.parallelutilities.ParallelCountEstimatorChecks;
import tools.dscode.testengine.PkbPropertyValueNormalizerChecks;
import tools.dscode.testengine.ProfileConfigurationChecks;

import java.util.List;

import static tools.dscode.common.reporting.logging.LogForwarder.logInfo;

public final class InternalFrameworkTestSteps {
    private InternalFrameworkTestSteps() {
    }

    @Given("^RUN QUOTE PARSER JAVA TESTS$")
    public static void runQuoteParserJavaTests() {
        runAndAssert(QuoteParserChecks.class);
    }

    @Given("^RUN INTERNAL PICKLEBALL JAVA TESTS$")
    public static void runInternalPickleballJavaTests() {
        runAndAssert(
                ScenarioStepChecks.class,
                ScenarioStepDataChecks.class,
                MappingDataRefactorChecks.class,
                QuoteParserChecks.class,
                ModularScenariosChecks.class,
                BusinessTemporalDeltaChecks.class,
                BusinessTimePostModifierChecks.class,
                PkbPropertyValueNormalizerChecks.class,
                ProfileConfigurationChecks.class,
                ParallelCountEstimatorChecks.class,
                ChromeHeadlessConfigChecks.class,
                DynamicControlApiChecks.class,
                ControlRuntimeObserverChecks.class
        );
    }

    @Given("^RUN STEP OVERRIDE JAVA TESTS$")
    public static void runStepOverrideJavaTests() {
        runAndAssert(StepOverrideChecks.class);
    }

    @Given("^RUN DIAGNOSTIC REPORTING JAVA TESTS$")
    public static void runDiagnosticReportingJavaTests() {
        try {
            runAndAssert(
                    DiagnosticReportingChecks.class,
                    Diagnostic213CompletionChecks.class,
                    PickleballGuidanceChecks.class,
                    InvestigationHandoffChecks.class
            );
        } finally {
            ReportRetentionPolicy.clearThreadOverride();
        }
    }

    @Given("^RUN DATA ELEMENT PHASE 1 JAVA TESTS$")
    public static void runDataElementPhaseOneJavaTests() {
        runAndAssert(DataElementPhaseOneChecks.class);
    }

    @Given("^RUN DATA ELEMENT PHASE 2 JAVA TESTS$")
    public static void runDataElementPhaseTwoJavaTests() {
        runAndAssert(DataElementPhaseTwoChecks.class);
    }

    @Given("^RUN DATA ELEMENT PHASE 3 JAVA TESTS$")
    public static void runDataElementPhaseThreeJavaTests() {
        runAndAssert(DataElementPhaseThreeChecks.class);
    }

    @Given("^RUN DATA ELEMENT PHASE 4 AND 6 JAVA TESTS$")
    public static void runDataElementPhaseFourAndSixJavaTests() {
        runAndAssert(DataElementPhaseFourAndSixChecks.class);
    }

    @Given("^RUN DATA ELEMENT PHASE 5 JAVA TESTS$")
    public static void runDataElementPhaseFiveJavaTests() {
        runAndAssert(DataElementPhaseFiveChecks.class);
    }

    private static void runAndAssert(Class<?>... testClasses) {
        List<InternalJavaTestRunner.Result> results =
                InternalJavaTestRunner.run(testClasses);

        results.forEach(result -> logInfo(result.display()));
        List<InternalJavaTestRunner.Result> failures = results.stream()
                .filter(result -> !result.passed())
                .toList();

        if (!failures.isEmpty()) {
            throw new AssertionError(
                    failures.size() + " internal Pickleball Java test(s) failed: "
                            + failures.stream()
                            .map(InternalJavaTestRunner.Result::display)
                            .toList()
            );
        }
    }
}
