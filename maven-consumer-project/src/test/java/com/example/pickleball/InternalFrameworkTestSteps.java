package com.example.pickleball;

import com.example.pickleball.support.InternalJavaTestRunner;
import io.cucumber.core.runner.ScenarioStepChecks;
import io.cucumber.core.runner.ScenarioStepDataChecks;
import io.cucumber.java.en.Given;
import tools.dscode.common.mappings.MappingDataRefactorChecks;
import tools.dscode.common.util.datetime.BusinessTemporalDeltaChecks;
import tools.dscode.common.util.datetime.BusinessTimePostModifierChecks;
import tools.dscode.coredefinitions.DataTableConversionChecks;
import tools.dscode.coredefinitions.ModularScenariosChecks;

import java.util.List;

import static tools.dscode.common.reporting.logging.LogForwarder.logInfo;

public final class InternalFrameworkTestSteps {
    private InternalFrameworkTestSteps() {
    }

    @Given("^RUN INTERNAL PICKLEBALL JAVA TESTS$")
    public static void runInternalPickleballJavaTests() {
        List<InternalJavaTestRunner.Result> results =
                InternalJavaTestRunner.run(
                        ScenarioStepChecks.class,
                        ScenarioStepDataChecks.class,
                        ModularScenariosChecks.class,
                        MappingDataRefactorChecks.class,
                        DataTableConversionChecks.class,
                        BusinessTemporalDeltaChecks.class,
                        BusinessTimePostModifierChecks.class
                );
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
