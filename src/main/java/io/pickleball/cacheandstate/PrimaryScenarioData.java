package io.pickleball.cacheandstate;

import io.cucumber.core.runner.Runner;
import io.cucumber.core.runner.TestCase;

import static io.pickleball.cacheandstate.GlobalCache.getState;

public class PrimaryScenarioData {

    private ScenarioContext primaryScenario;
    private ScenarioContext currentScenario;

    private final Runner runner;
    private final ScenarioContext scenarioContext;


    public PrimaryScenarioData(Runner runner, TestCase testCase) {
        this.runner = runner;
        this.scenarioContext = testCase.scenarioContext;
        this.currentScenario  = testCase.scenarioContext;
    }


//    public PrimaryScenarioData(Runner runner, TestCase testCase) {
//
//    }

    public static ScenarioContext getPrimaryScenario() {
        return getState().primaryScenario;
    }

    public static void setPrimaryScenario(ScenarioContext primaryScenario) {

//        if (getState().primaryScenario != null)
//            throw new RuntimeException("primaryScenario value already set2");
        getState().primaryScenario = primaryScenario;
//        getState().currentScenario = primaryScenario;
    }


    public static ScenarioContext getCurrentScenario() {
        return getState().currentScenario;
    }

    public static void setCurrentScenario(ScenarioContext currentScenario) {
        getState().currentScenario = currentScenario;
    }


    public static Runner getRunner() {
        return  getState().runner;
    }
}
