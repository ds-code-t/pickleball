package io.cucumber.core.runner;


import io.cucumber.core.runner.Runner;
import io.cucumber.core.runner.CachingGlue;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.runner.TestCase;
import tools.dscode.common.util.Reflect;


public aspect RunnerAfterCreateTestCaseAspect {

    // Access Runner's private 'glue' field
    private CachingGlue Runner.__ajc_getGlue() {
        return (CachingGlue) Reflect.getProperty(this, "glue");
    }

    // Intercept Runner.createTestCaseForPickle(Pickle)
    pointcut createTestCase(Runner runner, Pickle pickle) :
            execution(private io.cucumber.core.runner.TestCase
                    io.cucumber.core.runner.Runner.createTestCaseForPickle(
                    io.cucumber.core.gherkin.Pickle))
                    && this(runner)
                    && args(pickle);

    // Run AFTER the method completes successfully
    after(Runner runner, Pickle pickle) returning (TestCase testCase) :
            createTestCase(runner, pickle) {

        CachingGlue glue = runner.__ajc_getGlue();
        testCase.currentScenarioState.scenarioRunner = runner;
        testCase.currentScenarioState.cachingGlue = glue;
        System.out.printf(
                "@@afterCreateTestCase: runner=%s glue=%s pickle=%s testCase=%s%n",
                runner,
                glue,
                pickle.getUri(),
                testCase
        );
    }
}
